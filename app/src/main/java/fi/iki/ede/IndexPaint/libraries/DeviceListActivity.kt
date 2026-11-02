/*
 * Portions adapted from the Android Open Source Project BluetoothChat sample.
 * Copyright (C) 2009 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0; see LICENSE-APACHE-2.0.
 * Modernized and adapted for Index Paint.
 */
package fi.iki.ede.IndexPaint.libraries

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.os.ParcelUuid
import android.view.View
import android.view.Window
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.ContextCompat
import fi.iki.ede.IndexPaint.PaintApp
import fi.iki.ede.IndexPaint.R
import java.util.ArrayDeque

/** Lists only Bluetooth devices advertising IndexPaint's RFCOMM service UUID. */
class DeviceListActivity : Activity() {
    private val adapter: BluetoothAdapter? get() = BluetoothAdapter.getDefaultAdapter()
    private lateinit var pairedDevicesAdapter: ArrayAdapter<String>
    private lateinit var newDevicesAdapter: ArrayAdapter<String>
    private val pairedAddresses = mutableListOf<String>()
    private val discoveredAddresses = mutableSetOf<String>()
    private val checkedAddresses = mutableSetOf<String>()
    private val candidateDevices = linkedMapOf<String, BluetoothDevice>()
    private val serviceLookupQueue = ArrayDeque<BluetoothDevice>()
    private val newDeviceAddresses = mutableListOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectionMonitor = object : Runnable {
        override fun run() {
            if ((application as? PaintApp)?.isConnectedToPeer() == true) {
                setResult(RESULT_CANCELED)
                finish()
            } else {
                mainHandler.postDelayed(this, CONNECTION_MONITOR_INTERVAL_MS)
            }
        }
    }
    private var activeLookupAddress: String? = null
    private var activeLookupDevice: BluetoothDevice? = null
    private var discoveryActive = false
    private var scanWasRun = false
    private lateinit var scanButton: Button
    private val lookupTimeout = Runnable {
        activeLookupDevice?.takeIf { it.bondState == BluetoothDevice.BOND_BONDED }?.let(::addPairedDevice)
        activeLookupDevice = null
        activeLookupAddress = null
        requestNextServiceRecord()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.bluetoothDevice() ?: return
                    candidateDevices.putIfAbsent(device.address, device)
                }
                BluetoothDevice.ACTION_UUID -> {
                    val device = intent.bluetoothDevice() ?: return
                    val uuids = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
                        ?.filterIsInstance<ParcelUuid>()
                        .orEmpty()
                    if (uuids.any { it.uuid == BluetoothTransport.SERVICE_UUID }) addIndexPaintDevice(device)
                    if (activeLookupAddress == device.address) {
                        if (uuids.none { it.uuid == BluetoothTransport.SERVICE_UUID } && device.bondState == BluetoothDevice.BOND_BONDED) {
                            addPairedDevice(device)
                        }
                        activeLookupAddress = null
                        activeLookupDevice = null
                        mainHandler.removeCallbacks(lookupTimeout)
                        requestNextServiceRecord()
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    discoveryActive = false
                    setProgressBarIndeterminateVisibility(false)
                    setTitle(R.string.select_device)
                    candidateDevices.values.forEach(::enqueueServiceRecord)
                    candidateDevices.clear()
                    requestNextServiceRecord()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS)
        setContentView(R.layout.device_list)
        setResult(RESULT_CANCELED)
        pairedDevicesAdapter = ArrayAdapter(this, R.layout.device_name)
        newDevicesAdapter = ArrayAdapter(this, R.layout.device_name)
        findViewById<ListView>(R.id.paired_devices).apply {
            adapter = pairedDevicesAdapter
            onItemClickListener = deviceClickListener
        }
        findViewById<ListView>(R.id.new_devices).apply {
            adapter = newDevicesAdapter
            onItemClickListener = deviceClickListener
        }
        scanButton = findViewById<Button>(R.id.button_scan)
        scanButton.setOnClickListener {
            startDiscovery()
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_UUID)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)

        val bonded = adapter?.bondedDevices.orEmpty()
        bonded.forEach(::enqueueServiceRecord)
        if (bonded.isEmpty()) pairedDevicesAdapter.add(getString(R.string.none_paired))
        requestNextServiceRecord()
    }

    override fun onResume() {
        super.onResume()
        mainHandler.post(connectionMonitor)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(connectionMonitor)
        super.onPause()
    }

    private val deviceClickListener = AdapterView.OnItemClickListener { parent, _, position, _ ->
        adapter?.cancelDiscovery()
        val address = if (parent.id == R.id.paired_devices) {
            pairedAddresses.getOrNull(position)
        } else {
            newDeviceAddresses.getOrNull(position)
        } ?: return@OnItemClickListener
        setResult(RESULT_OK, Intent().putExtra(EXTRA_DEVICE_ADDRESS, address))
        finish()
    }

    private fun enqueueServiceRecord(device: BluetoothDevice) {
        if (!checkedAddresses.add(device.address)) return
        serviceLookupQueue.addLast(device)
    }

    private fun requestNextServiceRecord() {
        if (discoveryActive || activeLookupAddress != null) return
        val device = serviceLookupQueue.pollFirst()
        if (device == null) {
            showEmptyResultIfReady()
            return
        }
        activeLookupAddress = device.address
        activeLookupDevice = device
        if (device.fetchUuidsWithSdp()) {
            mainHandler.postDelayed(lookupTimeout, UUID_LOOKUP_TIMEOUT_MS)
        } else {
            activeLookupAddress = null
            activeLookupDevice = null
            if (device.bondState == BluetoothDevice.BOND_BONDED) addPairedDevice(device)
            mainHandler.post(::requestNextServiceRecord)
        }
    }

    private fun addIndexPaintDevice(device: BluetoothDevice) {
        if (!discoveredAddresses.add(device.address)) return
        val name = runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: getString(R.string.unnamed_indexpaint_device)
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            addPairedDevice(device, name)
        } else {
            findViewById<View>(R.id.title_new_devices).visibility = View.VISIBLE
            newDeviceAddresses.add(device.address)
            newDevicesAdapter.add(name)
        }
    }

    private fun addPairedDevice(device: BluetoothDevice, name: String? = null) {
        if (device.address in pairedAddresses) return
        if (pairedAddresses.isEmpty() && pairedDevicesAdapter.count == 1 &&
            pairedDevicesAdapter.getItem(0) == getString(R.string.none_paired)
        ) pairedDevicesAdapter.clear()
        val deviceName = name ?: runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: getString(R.string.unnamed_indexpaint_device)
        findViewById<View>(R.id.title_paired_devices).visibility = View.VISIBLE
        pairedAddresses.add(device.address)
        pairedDevicesAdapter.add(deviceName)
    }

    private fun showEmptyResultIfReady() {
        if (discoveryActive || activeLookupAddress != null || serviceLookupQueue.isNotEmpty()) return
        if (pairedAddresses.isEmpty() && pairedDevicesAdapter.count == 0) pairedDevicesAdapter.add(getString(R.string.none_paired))
        if (pairedDevicesAdapter.count > 0) findViewById<View>(R.id.title_paired_devices).visibility = View.VISIBLE
        if (scanWasRun && newDeviceAddresses.isEmpty() && newDevicesAdapter.count == 0) {
            newDevicesAdapter.add(getString(if (pairedAddresses.isEmpty()) R.string.none_found else R.string.none_found_paired))
            findViewById<View>(R.id.title_new_devices).visibility = View.VISIBLE
        }
        scanButton.isEnabled = true
    }

    private fun startDiscovery() {
        checkedAddresses.clear()
        discoveredAddresses.clear()
        candidateDevices.clear()
        serviceLookupQueue.clear()
        scanWasRun = true
        newDeviceAddresses.clear()
        newDevicesAdapter.clear()
        discoveryActive = true
        scanButton.isEnabled = false
        setProgressBarIndeterminateVisibility(true)
        setTitle(R.string.scanning)
        findViewById<View>(R.id.title_new_devices).visibility = View.VISIBLE
        val bluetooth = adapter
        if (bluetooth == null || (!bluetooth.isDiscovering && !bluetooth.startDiscovery())) {
            discoveryActive = false
            setProgressBarIndeterminateVisibility(false)
            showEmptyResultIfReady()
        } else if (bluetooth.isDiscovering) {
            // Android is already scanning; its completion broadcast will drain this list.
        }
    }

    private fun Intent.bluetoothDevice(): BluetoothDevice? =
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    override fun onDestroy() {
        adapter?.cancelDiscovery()
        mainHandler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(receiver) } catch (_: IllegalArgumentException) { }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        private const val UUID_LOOKUP_TIMEOUT_MS = 3_000L
        private const val CONNECTION_MONITOR_INTERVAL_MS = 200L
    }
}
