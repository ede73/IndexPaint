package fi.iki.ede.IndexPaint.libraries

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.util.Log
import fi.iki.ede.IndexPaint.DrawingTransport
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID

/** Bluetooth Classic transport and message framing used by the two IndexPaint peers. */
class BluetoothTransport(
    private val handler: Handler,
    private val serviceUuid: UUID,
    private val serviceName: String,
) : DrawingTransport {
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    @Volatile private var state = STATE_NONE
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var connectionThread: Thread? = null
    @Volatile private var ioThread: Thread? = null
    @Volatile private var serverSocket: BluetoothServerSocket? = null

    fun getState(): Int = state
    override val isConnected: Boolean get() = state == STATE_CONNECTED
    fun getAdapter(): BluetoothAdapter? = adapter

    @Synchronized private fun setState(value: Int) {
        state = value
        handler.obtainMessage(IPC_MESSAGE_STATE_CHANGE, value, -1).sendToTarget()
    }

    @SuppressLint("MissingPermission")
    @Synchronized fun startListening() {
        if (state == STATE_CONNECTED || acceptThread?.isAlive == true) return
        acceptThread = Thread({
            try {
                adapter?.listenUsingRfcommWithServiceRecord(serviceName, serviceUuid)?.use { server ->
                    serverSocket = server
                    setState(STATE_LISTEN)
                    val incoming = server.accept()
                    if (incoming != null) connectSocket(incoming)
                }
            } catch (error: IOException) {
                Log.e(TAG, "Bluetooth listener failed", error)
                notifyToast("Unable to listen for IndexPaint devices")
            } finally {
                serverSocket = null
                if (state != STATE_CONNECTED) setState(STATE_NONE)
            }
        }, "IndexPaint-accept").also { it.start() }
    }

    @SuppressLint("MissingPermission")
    @Synchronized fun connect(device: BluetoothDevice) {
        cancelConnection()
        setState(STATE_CONNECTING)
        connectionThread = Thread({
            try {
                adapter?.cancelDiscovery()
                val outgoingSocket = device.createRfcommSocketToServiceRecord(serviceUuid)
                outgoingSocket.connect()
                connectSocket(outgoingSocket)
            } catch (error: IOException) {
                Log.e(TAG, "Bluetooth connection failed", error)
                if (state != STATE_CONNECTED) {
                    notifyToast("Unable to connect to device")
                    setState(STATE_NONE)
                }
            }
        }, "IndexPaint-connect").also { it.start() }
    }

    @SuppressLint("MissingPermission")
    @Synchronized private fun connectSocket(connectedSocket: BluetoothSocket) {
        // Both peers may initiate RFCOMM at once. Keep the first usable link;
        // replacing it with the second accepted/outgoing socket breaks mirroring.
        if (state == STATE_CONNECTED && socket?.isConnected == true) {
            runCatching { connectedSocket.close() }
            return
        }
        closeSocket()
        socket = connectedSocket
        setState(STATE_CONNECTED)
        val data = Bundle().apply { putString(DEVICE_NAME, connectedSocket.remoteDevice.name ?: "IndexPaint device") }
        handler.obtainMessage(IPC_MESSAGE_DEVICE_NAME).apply { this.data = data }.sendToTarget()
        ioThread = Thread({ readLoop(connectedSocket) }, "IndexPaint-protocol").also { it.start() }
    }

    private fun readLoop(connectedSocket: BluetoothSocket) {
        try {
            val input = DataInputStream(connectedSocket.inputStream)
            while (!Thread.currentThread().isInterrupted) {
                val length = input.readUnsignedByte()
                if (length == 0) continue
                val payload = ByteArray(length)
                input.readFully(payload)
                handler.obtainMessage(IPC_MESSAGE_READ, length, -1, payload).sendToTarget()
            }
        } catch (error: Exception) {
            if (state == STATE_CONNECTED) {
                Log.i(TAG, "Bluetooth connection ended", error)
                notifyToast("Device connection was lost")
                setState(STATE_NONE)
            } else {
                Log.d(TAG, "Bluetooth socket closed", error)
            }
        } finally {
            synchronized(this) { if (socket === connectedSocket) closeSocket() }
        }
    }

    @Synchronized @Throws(IOException::class)
    override fun send(payload: ByteArray) {
        require(payload.size in 1..MAX_PACKET_SIZE) { "Protocol messages must be 1–255 bytes" }
        val connectedSocket = checkNotNull(socket) { "No connected device" }
        val output = DataOutputStream(connectedSocket.outputStream)
        output.writeByte(payload.size)
        output.write(payload)
        output.flush()
        handler.obtainMessage(IPC_MESSAGE_WRITE, -1, -1, payload).sendToTarget()
    }

    @Synchronized fun stop() {
        acceptThread?.interrupt(); acceptThread = null
        connectionThread?.interrupt(); connectionThread = null
        ioThread?.interrupt(); ioThread = null
        try { serverSocket?.close() } catch (error: IOException) { Log.w(TAG, "Listener close failed", error) }
        serverSocket = null
        closeSocket()
        setState(STATE_NONE)
    }

    override fun disconnect() = stop()

    private fun cancelConnection() {
        connectionThread?.interrupt()
        connectionThread = null
    }

    private fun closeSocket() {
        try { socket?.close() } catch (error: IOException) { Log.w(TAG, "Socket close failed", error) }
        socket = null
    }

    private fun notifyToast(message: String) {
        val bundle = Bundle().apply { putString(TOAST, message) }
        handler.obtainMessage(IPC_MESSAGE_TOAST).apply { data = bundle }.sendToTarget()
    }

    companion object {
        const val IPC_MESSAGE_STATE_CHANGE = 1
        const val IPC_MESSAGE_READ = 2
        const val IPC_MESSAGE_WRITE = 3
        const val IPC_MESSAGE_DEVICE_NAME = 4
        const val IPC_MESSAGE_TOAST = 5
        const val STATE_NONE = 0
        const val STATE_LISTEN = 1
        const val STATE_CONNECTING = 2
        const val STATE_CONNECTED = 3
        const val DEVICE_NAME = "device_name"
        const val TOAST = "toast"
        const val MAX_PACKET_SIZE = 255
        val SERVICE_UUID: UUID = UUID.fromString("45dbf623-e161-4c6a-a47c-d6a5c2f63aa0")

        private const val TAG = "BluetoothTransport"

        fun hasBluetooth() = BluetoothAdapter.getDefaultAdapter() != null
        @SuppressLint("MissingPermission") fun isBluetoothEnabled() = BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
        fun enableBluetooth(activity: Activity, requestCode: Int) = activity.startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), requestCode)

        @SuppressLint("MissingPermission")
        fun ensureDiscoverability(activity: Activity, requestCode: Int): Boolean {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
            if (adapter.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) return true
            activity.startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300), requestCode)
            return false
        }
    }
}
