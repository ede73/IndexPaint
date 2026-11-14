package fi.iki.ede.IndexPaint

import android.app.Activity
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.widget.Toast
import android.util.Base64
import fi.iki.ede.IndexPaint.libraries.BluetoothTransport
import fi.iki.ede.IndexPaint.libraries.DeviceListActivity
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import java.security.SecureRandom
import java.math.BigInteger

class PaintApp : Application() {
    var paperSize: PaperSize = PaperSize.A4
        private set
    var localBitmap: Bitmap = createPaperBitmap(paperSize)
        private set
    var view: MyView? = null
        private set
    private var currentActivity: Activity? = null
    private var bluetoothTransport: BluetoothTransport? = null
    private var transport: DrawingTransport? = null
    private var connectedDeviceName: String? = null
    var connectionStatus: String = "Not connected"
        private set
    private val participantId = UUID.randomUUID().toString()
    private var peerParticipantId: String? = null
    var sessionMode: SessionMode? = null
        private set
    private var localSessionModeChoice: SessionMode? = null
    private var peerSessionModeChoice: SessionMode? = null
    private var localFoldProposal: DrawingMessage.FoldProposal? = null
    private var peerFoldProposal: DrawingMessage.FoldProposal? = null
    private var foldHelloSent = false
    private var localFoldCursorX = 0f
    private var localFoldCursorY = 0f
    private var localFoldCursorDrawing = false
    var foldSessionActive: Boolean = false
        private set
    var foldTurnEnded: Boolean = false
        private set
    var foldRevealed: Boolean = false
        private set
    var foldIndex: Int = 0
        private set
    var foldPreviousBoundary: Float = 0f
        private set
    /** Bounds of the most recent committed turn, retained for the origin's handoff view. */
    var lastFoldStartBoundary: Float = 0f
        private set
    var lastFoldEndBoundary: Float = 0f
        private set
    var foldBoundary: Float = 0.5f
        private set
    var foldFirstPlayerId: String = ""
        private set
    var foldActivePlayerId: String = ""
        private set
    var peerFoldPreview: DrawingMessage.FoldPreview? = null
        private set
    var foldFinishPending: Boolean = false
        private set
    @Volatile var foldYieldPending: Boolean = false
        private set
    private var yieldAttemptId = 0
    private var pendingYieldMessageId: String? = null
    private var pendingYieldRetryCount = 0
    private var pendingYieldBoundary: Float = 0f
    private var pendingYieldBitmap: Bitmap? = null
    private var lastCommittedYieldMessageId: String? = null
    private var lastCommittedYieldIndex: Int = -1
    private var lastCommittedYieldBoundary: Float = Float.NaN
    private var peerProtocolVersion: Int? = null
    val foldLayers = sortedMapOf<Int, Bitmap>()
    private val foldLayerFiles = sortedMapOf<Int, File>()
    private val imageTransfers = mutableMapOf<String, ImageTransfer>()
    private val foldImageTransfers = mutableMapOf<String, ImageTransfer>()
    private val foldHandoffTransfers = mutableMapOf<String, ImageTransfer>()
    private val incomingHandoffAnnotations = mutableMapOf<Int, Bitmap>()
    private val yieldRetryRunnable = Runnable { retryPendingYield() }

    private data class ImageTransfer(
        val foldIndex: Int,
        val chunks: Array<String?>,
        var encodedChars: Int = 0,
        var lastUpdatedMs: Long = System.currentTimeMillis(),
    )

    val actionBarHeightPx: Int
        get() = (currentActivity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.height ?: 0

    private val commandHandler: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            when (message.what) {
                BluetoothTransport.IPC_MESSAGE_STATE_CHANGE -> when (message.arg1) {
                    BluetoothTransport.STATE_NONE -> {
                        foldYieldPending = false
                        yieldAttemptId++
                        pendingYieldMessageId = null
                        pendingYieldRetryCount = 0
                        pendingYieldBitmap?.takeIf { !it.isRecycled }?.recycle()
                        pendingYieldBitmap = null
                        endFoldAfterDisconnect()
                        imageTransfers.clear()
                        foldImageTransfers.clear()
                        foldHandoffTransfers.clear()
                        incomingHandoffAnnotations.values.forEach { if (!it.isRecycled) it.recycle() }
                        incomingHandoffAnnotations.clear()
                        connectedDeviceName = null
                        peerProtocolVersion = null
                        peerParticipantId = null
                        foldHelloSent = false
                        sessionMode = null
                        localSessionModeChoice = null
                        peerSessionModeChoice = null
                        localFoldProposal = null
                        peerFoldProposal = null
                        updateConnectionStatus("Not connected")
                        (currentActivity as? IndexPaint)?.onSessionDisconnected()
                    }
                    BluetoothTransport.STATE_LISTEN -> updateConnectionStatus("Waiting for peer")
                    BluetoothTransport.STATE_CONNECTING -> updateConnectionStatus("Connecting")
                    BluetoothTransport.STATE_CONNECTED -> updateConnectionStatus("Connected")
                }
                BluetoothTransport.IPC_MESSAGE_READ -> handleCommand(message.obj as ByteArray)
                BluetoothTransport.IPC_MESSAGE_DEVICE_NAME -> {
                    connectedDeviceName = message.data.getString(BluetoothTransport.DEVICE_NAME)
                    updateConnectionStatus("Connected to ${connectedDeviceName ?: "IndexPaint device"}")
                    if (!foldHelloSent) {
                        foldHelloSent = true
                        sendMessage(DrawingMessage.FoldHello(participantId))
                        sendMessage(DrawingMessage.ProtocolVersion(CURRENT_PROTOCOL_VERSION))
                    }
                }
                BluetoothTransport.IPC_MESSAGE_TOAST -> toast(message.data.getString(BluetoothTransport.TOAST).orEmpty())
            }
        }
    }

    private fun handleCommand(payload: ByteArray) {
        val command = DrawingProtocol.decode(payload) ?: return
        if (isModeNegotiation(command)) {
            // These messages are what allow the two peers to reach agreement.
        } else if (isFoldControl(command)) {
            if (sessionMode != SessionMode.FOLD_GAME) return
        } else if (!isSharedDrawingEnabled()) {
            return
        }
        when (command) {
            is DrawingMessage.FoldHello -> {
                peerParticipantId = command.participantId
                maybeShowSessionModeChoice()
                return
            }
            is DrawingMessage.ProtocolVersion -> {
                peerProtocolVersion = command.version
                Log.i(TAG, "Peer protocol version ${command.version}")
                return
            }
            is DrawingMessage.SessionModeChoice -> {
                peerSessionModeChoice = command.mode
                if (command.mode != SessionMode.FOLD_GAME) peerFoldProposal = null
                updateSessionModeDialog()
                maybeAgreeSessionMode()
                return
            }
            is DrawingMessage.FoldProposal -> {
                if (peerSessionModeChoice != SessionMode.FOLD_GAME ||
                    !command.randomSeedHex.matches(Regex("[0-9a-fA-F]{64}"))
                ) return
                peerFoldProposal = command
                maybeStartFoldSession()
                return
            }
            is DrawingMessage.FoldPreview -> {
                if (!validFoldPreview(command)) return
                peerFoldPreview = command
                updateFoldAccessibility()
                view?.invalidate()
                return
            }
            is DrawingMessage.FoldLayerChunk -> {
                receiveFoldLayerChunk(command)
                return
            }
            is DrawingMessage.FoldHandoffChunk -> {
                receiveFoldHandoffChunk(command)
                return
            }
            is DrawingMessage.FoldYield -> {
                // Commit once per transaction ID. A duplicate commit is only re-acked.
                val duplicate = command.messageId == lastCommittedYieldMessageId &&
                    command.foldIndex == lastCommittedYieldIndex &&
                    kotlin.math.abs(command.boundary - lastCommittedYieldBoundary) < 0.00001f
                if (duplicate) {
                    if (!sendMessage(DrawingMessage.FoldYieldAck(command.messageId, command.foldIndex, true))) {
                        Log.w(TAG, "Could not re-send acknowledgement for handoff ${command.messageId}")
                    }
                    return
                }
                val valid = command.messageId.matches(Regex("[A-Za-z0-9-]{1,64}")) &&
                    !isLocalFoldTurn() && command.foldIndex == foldIndex &&
                    command.boundary.isFinite() && command.boundary >= foldPreviousBoundary && command.boundary <= 1f &&
                    command.foldIndex in foldLayers && incomingHandoffAnnotations[command.foldIndex]?.isRecycled == false
                if (valid) {
                    advanceFold(command.foldIndex, command.boundary)
                    lastCommittedYieldMessageId = command.messageId
                    lastCommittedYieldIndex = command.foldIndex
                    lastCommittedYieldBoundary = command.boundary
                } else {
                    Log.w(TAG, "Rejected Yield ${command.messageId} for fold ${command.foldIndex}: current fold=$foldIndex, localTurn=${isLocalFoldTurn()}, layer=${command.foldIndex in foldLayers}, metadata=${incomingHandoffAnnotations[command.foldIndex]?.isRecycled == false}, boundary=${command.boundary}")
                }
                if (!sendMessage(DrawingMessage.FoldYieldAck(command.messageId, command.foldIndex, valid))) {
                    Log.w(TAG, "Could not acknowledge handoff ${command.messageId}")
                }
                return
            }
            is DrawingMessage.FoldYieldAck -> {
                if (!foldYieldPending || command.messageId != pendingYieldMessageId || command.foldIndex != foldIndex) return
                if (!command.accepted) {
                    Log.w(TAG, "Peer rejected handoff ${command.messageId}; retrying transaction")
                    commandHandler.removeCallbacks(yieldRetryRunnable)
                    commandHandler.post(yieldRetryRunnable)
                    return
                }
                foldYieldPending = false
                yieldAttemptId++
                pendingYieldMessageId = null
                pendingYieldRetryCount = 0
                commandHandler.removeCallbacks(yieldRetryRunnable)
                val handoff = pendingYieldBitmap
                pendingYieldBitmap = null
                if (handoff != null) {
                    advanceFold(command.foldIndex, pendingYieldBoundary, handoff)
                    toast("Handoff received by your partner")
                } else {
                    toast("Handoff confirmation arrived without the saved notes. Please reconnect.")
                }
                return
            }
            DrawingMessage.FoldFinishRequest -> {
                // The drawer ending the session is the one who decides. Chunks for
                // the final layer arrive before this command on the ordered stream.
                val revealed = revealFoldLayers()
                sendMessage(DrawingMessage.FoldFinishResponse(revealed))
                return
            }
            is DrawingMessage.FoldFinishResponse -> {
                if (!foldFinishPending) return
                foldFinishPending = false
                if (!command.accepted || !revealFoldLayers()) {
                    toast("The whole drawing could not be revealed. Keep the session open and try again.")
                    (currentActivity as? IndexPaint)?.onFoldSessionChanged()
                }
                return
            }
            DrawingMessage.FoldCancel -> {
                if (foldSessionActive) return
                sessionMode = null
                localSessionModeChoice = null
                peerSessionModeChoice = null
                localFoldProposal = null
                peerFoldProposal = null
                maybeShowSessionModeChoice()
                return
            }
            else -> Unit
        }
        val activeView = view ?: return
        try {
            when (command) {
                DrawingMessage.Initialize -> activeView.clearAndInitialize(silent = true)
                DrawingMessage.Undo -> activeView.undo(silent = true)
                DrawingMessage.Redo -> activeView.redo(silent = true)
                is DrawingMessage.TouchStart -> activeView.remoteDraw.touchStart(command.x, command.y, command.pressure)
                is DrawingMessage.TouchMove -> activeView.remoteDraw.touchMove(command.x, command.y, command.pressure)
                DrawingMessage.TouchUp -> activeView.remoteDraw.touchUp()
                is DrawingMessage.SetColor -> activeView.remoteDraw.setColor(command.color)
                is DrawingMessage.SetBrush -> activeView.remoteDraw.setBrush(command.brush)
                is DrawingMessage.SetWidth -> activeView.remoteDraw.setDrawWidth(command.width)
                is DrawingMessage.SetAlpha -> activeView.remoteDraw.setAlpha(command.alpha)
                is DrawingMessage.SetPaperSize -> changePaperSize(command.paperSize, notifyPeer = false)
                DrawingMessage.ClearBlendMode -> activeView.remoteDraw.clearXfermode()
                is DrawingMessage.SetMask -> when (command.mask) {
                    DrawingProtocol.MASK_CLEAR -> activeView.remoteDraw.clearMask()
                    DrawingProtocol.MASK_BLUR -> activeView.remoteDraw.toggleBlur()
                    DrawingProtocol.MASK_EMBOSS -> activeView.remoteDraw.toggleEmboss()
                }
                is DrawingMessage.SetBlendMode -> activeView.remoteDraw.setPorterDuffMode(PorterDuff.Mode.valueOf(command.mode), command.alpha)
                is DrawingMessage.ImageChunk -> receiveImageChunk(command)
                is DrawingMessage.TextUpdate -> activeView.applyRemoteText(command)
                else -> Unit // Fold messages were handled above.
            }
            activeView.invalidate()
        } catch (error: Exception) { Log.w(TAG, "Ignoring malformed peer command", error) }
    }

    fun onMainActivityVisible() = maybeShowSessionModeChoice()

    private fun maybeShowSessionModeChoice() {
        if (connectionStatus.startsWith("Connected") && peerParticipantId != null &&
            !foldSessionActive && sessionMode == null
        ) (currentActivity as? IndexPaint)?.showSessionModeChoice(
            connectedDeviceName ?: "peer", localSessionModeChoice, peerSessionModeChoice,
        )
    }

    fun localParticipantId(): String = participantId
    fun peerParticipantId(): String? = peerParticipantId
    fun peerDisplayName(): String = connectedDeviceName?.takeIf { it.isNotBlank() } ?: "peer"
    fun chooseSessionMode(mode: SessionMode) {
        if (peerParticipantId == null || sessionMode != null || foldSessionActive) return
        if (mode == SessionMode.FOLD_GAME && peerProtocolVersion != CURRENT_PROTOCOL_VERSION) {
            toast("Both devices need the same IndexPaint version to play Fold Game")
            return
        }
        localSessionModeChoice = mode
        if (mode == SessionMode.FOLD_GAME) {
            val entropy = ByteArray(FOLD_SEED_BYTES).also(secureRandom::nextBytes)
            val seed = entropy.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            localFoldProposal = DrawingMessage.FoldProposal(seed)
        } else {
            localFoldProposal = null
            peerFoldProposal = null
        }
        sendMessage(DrawingMessage.SessionModeChoice(mode))
        if (mode == SessionMode.FOLD_GAME) localFoldProposal?.let(::sendMessage)
        updateSessionModeDialog()
        maybeAgreeSessionMode()
    }

    private fun updateSessionModeDialog() {
        (currentActivity as? IndexPaint)?.updateSessionModeChoices(
            connectedDeviceName ?: "peer", localSessionModeChoice, peerSessionModeChoice,
        )
    }

    private fun maybeAgreeSessionMode() {
        val localChoice = localSessionModeChoice ?: return
        if (localChoice != peerSessionModeChoice || sessionMode != null) return
        sessionMode = localChoice
        (currentActivity as? IndexPaint)?.onSessionModeAgreed(localChoice)
        view?.invalidate()
        if (localChoice == SessionMode.FOLD_GAME) maybeStartFoldSession()
        else toast("Free draw agreed")
    }

    private fun maybeStartFoldSession() {
        val local = localFoldProposal ?: return
        val peer = peerFoldProposal ?: return
        if (foldSessionActive || sessionMode != SessionMode.FOLD_GAME) return
        val localSeed = BigInteger(local.randomSeedHex, 16)
        val peerSeed = BigInteger(peer.randomSeedHex, 16)
        val firstPlayer = when {
            localSeed > peerSeed -> participantId
            peerSeed > localSeed -> checkNotNull(peerParticipantId)
            else -> minOf(participantId, checkNotNull(peerParticipantId))
        }
        foldIndex = 0
        foldPreviousBoundary = 0f
        lastFoldStartBoundary = 0f
        lastFoldEndBoundary = 0f
        foldBoundary = DEFAULT_FOLD_FRACTION
        foldFirstPlayerId = firstPlayer
        foldActivePlayerId = foldFirstPlayerId
        foldRevealed = false
        foldFinishPending = false
        foldTurnEnded = false
        commandHandler.removeCallbacks(yieldRetryRunnable)
        pendingYieldMessageId = null
        pendingYieldRetryCount = 0
        lastCommittedYieldMessageId = null
        lastCommittedYieldIndex = -1
        lastCommittedYieldBoundary = Float.NaN
        peerFoldPreview = null
        foldLayers.values.forEach { if (!it.isRecycled) it.recycle() }
        foldLayers.clear()
        clearFoldLayerFiles()
        foldImageTransfers.clear()
        foldHandoffTransfers.clear()
        incomingHandoffAnnotations.values.forEach { if (!it.isRecycled) it.recycle() }
        incomingHandoffAnnotations.clear()
        localBitmap.eraseColor(Color.TRANSPARENT)
        view?.beginFoldSession()
        foldSessionActive = true
        (currentActivity as? IndexPaint)?.onFoldSessionChanged()
        updateFoldAccessibility()
        toast(if (firstPlayer == participantId) "Random draw: you go first" else "Random draw: peer goes first")
        publishFoldPreview()
        updateFoldAccessibility()
    }

    fun isLocalFoldTurn(): Boolean = foldSessionActive && foldActivePlayerId == participantId

    fun isConnectedToPeer(): Boolean = transport?.isConnected == true

    fun isSharedDrawingEnabled(): Boolean = !isConnectedToPeer() ||
        sessionMode == SessionMode.FREE_DRAW || (sessionMode == SessionMode.FOLD_GAME && foldRevealed)

    fun foldProgressPercent(): Int {
        val remaining = (1f - foldPreviousBoundary).coerceAtLeast(0.0001f)
        return (((foldBoundary - foldPreviousBoundary) / remaining) * 100f).toInt().coerceIn(5, 100)
    }

    fun setFoldProgressPercent(percent: Int) {
        if (!isLocalFoldTurn() || foldFinishPending) return
        val fraction = percent.coerceIn(5, 100) / 100f
        foldBoundary = foldPreviousBoundary + (1f - foldPreviousBoundary) * fraction
        publishFoldPreview()
        view?.invalidate()
        (currentActivity as? IndexPaint)?.onFoldSessionChanged()
        updateFoldAccessibility()
    }

    fun endTurnOrYield() {
        if (!isLocalFoldTurn() || foldFinishPending || foldYieldPending || !isConnectedToPeer()) return
        if (!foldTurnEnded) {
            if (foldBoundary >= 1f) {
                requestFoldFinish()
                return
            }
            foldLayers[foldIndex]?.takeIf { !it.isRecycled }?.recycle()
            foldLayers[foldIndex] = checkNotNull(view).snapshotDrawingLayer()
            foldTurnEnded = true
            view?.beginHandoffMetadata()
            toast("Add a note with your finger or Text, then tap Done to pass the turn. Notes stay off the final picture.")
            (currentActivity as? IndexPaint)?.onFoldSessionChanged()
            updateFoldAccessibility()
            return
        }
        val drawingView = view ?: return
        if (!drawingView.hasPendingHandoffMarks()) {
            toast("Add a note with your finger or Text before tapping Done")
            return
        }
        val layer = foldLayers[foldIndex] ?: localBitmap.copy(Bitmap.Config.ARGB_8888, true)
        foldLayers[foldIndex] = layer
        val handoffMetadata = drawingView.copyPendingHandoffBitmap()
        val committedBoundary = foldBoundary
        val committedIndex = foldIndex
        foldYieldPending = true
        yieldAttemptId++
        val messageId = UUID.randomUUID().toString()
        pendingYieldMessageId = messageId
        pendingYieldRetryCount = 0
        pendingYieldBoundary = committedBoundary
        pendingYieldBitmap = handoffMetadata
        (currentActivity as? IndexPaint)?.onFoldSessionChanged()
        updateFoldAccessibility()
        toast("Sending drawing and handoff notes…")
        transmitPendingYield(messageId, committedIndex, committedBoundary, layer, handoffMetadata)
    }

    fun isHandoffMetadataPhase(): Boolean = foldSessionActive && foldTurnEnded && isLocalFoldTurn()

    fun requestFoldFinish() {
        if (!isLocalFoldTurn() || foldFinishPending || foldYieldPending || !isConnectedToPeer()) return
        val layer = checkNotNull(view).snapshotDrawingLayer()
        foldLayers[foldIndex]?.takeIf { !it.isRecycled }?.recycle()
        foldLayers[foldIndex] = layer
        foldFinishPending = true
        (currentActivity as? IndexPaint)?.onFoldSessionChanged()
        if (!sendFoldLayer(foldIndex, layer)) {
            foldFinishPending = false
            toast("Could not send drawing")
            (currentActivity as? IndexPaint)?.onFoldSessionChanged()
            return
        }
        if (!sendMessage(DrawingMessage.FoldFinishRequest)) {
            foldFinishPending = false
            toast("Could not send the end-session request")
        } else {
            toast("Ending session and revealing the whole drawing…")
        }
        (currentActivity as? IndexPaint)?.onFoldSessionChanged()
        updateFoldAccessibility()
    }

    private fun retryPendingYield() {
        val messageId = pendingYieldMessageId ?: return
        val index = foldIndex
        val layer = foldLayers[index]
        val metadata = pendingYieldBitmap
        if (!foldYieldPending || layer == null || metadata == null || metadata.isRecycled || !isConnectedToPeer()) {
            failPendingYield("Handoff could not be retried. Check the connection and try again.")
            return
        }
        if (pendingYieldRetryCount >= MAX_YIELD_RETRIES) {
            failPendingYield("The other device did not confirm the handoff. Your turn is still yours.")
            return
        }
        pendingYieldRetryCount++
        transmitPendingYield(messageId, index, pendingYieldBoundary, layer, metadata)
    }

    private fun transmitPendingYield(messageId: String, index: Int, boundary: Float, layer: Bitmap, metadata: Bitmap) {
        Thread({
            val sent = sendFoldLayer(index, layer, "$messageId-d") &&
                sendFoldHandoffAnnotation(index, metadata, "$messageId-m") &&
                sendMessage(DrawingMessage.FoldYield(messageId, index, boundary))
            commandHandler.post {
                if (pendingYieldMessageId != messageId || !foldYieldPending || foldIndex != index) return@post
                if (!sent) Log.w(TAG, "Handoff $messageId send failed; queued retry ${pendingYieldRetryCount + 1}")
                val delay = (YIELD_RETRY_BASE_MS * (1L shl pendingYieldRetryCount.coerceAtMost(3))).coerceAtMost(YIELD_RETRY_MAX_MS)
                commandHandler.removeCallbacks(yieldRetryRunnable)
                commandHandler.postDelayed(yieldRetryRunnable, delay)
            }
        }, "IndexPaint-yield-$messageId").start()
    }

    private fun failPendingYield(message: String) {
        commandHandler.removeCallbacks(yieldRetryRunnable)
        foldYieldPending = false
        pendingYieldMessageId = null
        pendingYieldRetryCount = 0
        pendingYieldBitmap?.takeIf { !it.isRecycled }?.recycle()
        pendingYieldBitmap = null
        toast(message)
        (currentActivity as? IndexPaint)?.onFoldSessionChanged()
        updateFoldAccessibility()
    }

    private fun sendFoldLayer(index: Int, layer: Bitmap, transferId: String = UUID.randomUUID().toString()): Boolean {
        return runCatching {
            val output = ByteArrayOutputStream()
            check(layer.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG encoder rejected fold layer" }
            val encoded = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            val total = (encoded.length + FOLD_IMAGE_CHUNK_CHARS - 1) / FOLD_IMAGE_CHUNK_CHARS
            Log.i(TAG, "Sending fold layer $transferId: ${layer.width}x${layer.height}, ${output.size()} bytes, $total chunks")
            for (chunkIndex in 0 until total) {
                val start = chunkIndex * FOLD_IMAGE_CHUNK_CHARS
                check(sendMessage(DrawingMessage.FoldLayerChunk(
                    transferId, index, chunkIndex, total,
                    encoded.substring(start, minOf(start + FOLD_IMAGE_CHUNK_CHARS, encoded.length)),
                )))
            }
            true
        }.onFailure { Log.e(TAG, "Could not send hidden fold", it) }.getOrDefault(false)
    }

    private fun sendFoldHandoffAnnotation(index: Int, annotation: Bitmap, transferId: String = UUID.randomUUID().toString()): Boolean {
        return runCatching {
            val output = ByteArrayOutputStream()
            check(annotation.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG encoder rejected handoff notes" }
            val encoded = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            val total = (encoded.length + FOLD_IMAGE_CHUNK_CHARS - 1) / FOLD_IMAGE_CHUNK_CHARS
            Log.i(TAG, "Sending handoff notes $transferId: ${annotation.width}x${annotation.height}, ${output.size()} bytes, $total chunks")
            for (chunkIndex in 0 until total) {
                val start = chunkIndex * FOLD_IMAGE_CHUNK_CHARS
                check(sendMessage(DrawingMessage.FoldHandoffChunk(
                    transferId, index, chunkIndex, total,
                    encoded.substring(start, minOf(start + FOLD_IMAGE_CHUNK_CHARS, encoded.length)),
                )))
            }
            true
        }.onFailure { Log.e(TAG, "Could not send handoff metadata", it) }.getOrDefault(false)
    }

    private fun receiveFoldLayerChunk(chunk: DrawingMessage.FoldLayerChunk) {
        if (!foldSessionActive || chunk.foldIndex !in 0..foldIndex + 1) return
        val bitmap = addChunk(foldImageTransfers, chunk.transferId, chunk.foldIndex, chunk.index, chunk.total, chunk.base64, FOLD_IMAGE_CHUNK_CHARS) ?: return
        Log.i(TAG, "Received fold layer ${chunk.transferId}: ${bitmap.width}x${bitmap.height}")
        storeFoldLayer(chunk.foldIndex, bitmap)
        view?.invalidate()
    }

    private fun receiveFoldHandoffChunk(chunk: DrawingMessage.FoldHandoffChunk) {
        if (!foldSessionActive || chunk.foldIndex !in foldIndex..foldIndex + 1) return
        val bitmap = addChunk(foldHandoffTransfers, chunk.transferId, chunk.foldIndex, chunk.index, chunk.total, chunk.base64, FOLD_IMAGE_CHUNK_CHARS) ?: return
        Log.i(TAG, "Received handoff notes ${chunk.transferId}: ${bitmap.width}x${bitmap.height}")
        incomingHandoffAnnotations.remove(chunk.foldIndex)?.takeIf { !it.isRecycled }?.recycle()
        incomingHandoffAnnotations[chunk.foldIndex] = bitmap
    }

    private fun addChunk(
        transfers: MutableMap<String, ImageTransfer>, id: String, foldIndex: Int, index: Int,
        total: Int, data: String, chunkChars: Int,
    ): Bitmap? {
        val now = System.currentTimeMillis()
        transfers.entries.removeAll { now - it.value.lastUpdatedMs > TRANSFER_TIMEOUT_MS }
        val maxChunks = (MAX_ENCODED_IMAGE_CHARS + chunkChars - 1) / chunkChars
        if (id.length !in 1..64 || !id.all { it.isLetterOrDigit() || it == '-' } ||
            total !in 1..maxChunks || index !in 0 until total || data.length !in 1..chunkChars ||
            !data.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
        ) {
            Log.w(TAG, "Rejected chunk id=$id index=$index/$total chars=${data.length}")
            return null
        }
        val existing = transfers[id]
        // A new ID starts a retry/replacement. Dropping the old partial assembly
        // avoids pinning the channel after a truncated send while keeping one bounded assembly per kind.
        if (existing == null && transfers.isNotEmpty()) transfers.clear()
        val transfer = existing ?: ImageTransfer(foldIndex, arrayOfNulls(total)).also { transfers[id] = it }
        if (transfer.foldIndex != foldIndex || transfer.chunks.size != total) {
            transfers.remove(id)
            return null
        }
        transfer.lastUpdatedMs = now
        val old = transfer.chunks[index]
        val nextSize = transfer.encodedChars - (old?.length ?: 0) + data.length
        if (nextSize > MAX_ENCODED_IMAGE_CHARS) { transfers.remove(id); return null }
        transfer.chunks[index] = data
        transfer.encodedChars = nextSize
        if (transfer.chunks.any { it == null }) return null
        transfers.remove(id)
        return runCatching {
            val encoded = transfer.chunks.joinToString(separator = "") { checkNotNull(it) }
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            if (bytes.size > MAX_IMAGE_BYTES) {
                Log.w(TAG, "Rejected completed image transfer $id: ${bytes.size} bytes exceeds limit")
                return@runCatching null
            }
            decodeDocumentBitmap(bytes).also { bitmap ->
                if (bitmap == null) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    Log.w(TAG, "Rejected completed image transfer $id: ${bounds.outWidth}x${bounds.outHeight}, ${bytes.size} bytes")
                }
            }
        }.onFailure { Log.w(TAG, "Ignoring invalid image transfer", it) }.getOrNull()
    }

    private fun decodeDocumentBitmap(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth != DrawingDocument.RASTER_WIDTH ||
            bounds.outHeight !in 1..DrawingDocument.heightFor(PaperSize.A4) ||
            bounds.outWidth.toLong() * bounds.outHeight > MAX_DOCUMENT_PIXELS
        ) return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    private fun storeFoldLayer(index: Int, bitmap: Bitmap) {
        foldLayers.remove(index)?.takeIf { !it.isRecycled && it !== bitmap }?.recycle()
        foldLayerFiles.remove(index)?.delete()
        foldLayers[index] = bitmap
    }

    private fun compactFoldLayers(keepIndex: Int) {
        foldLayers.keys.toList().filter { it < keepIndex }.forEach { index ->
            val bitmap = foldLayers.remove(index) ?: return@forEach
            val file = runCatching { File.createTempFile("indexpaint-fold-", ".png", cacheDir) }.getOrNull()
            if (file == null) {
                foldLayers[index] = bitmap
                return@forEach
            }
            val saved = runCatching { FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }.getOrDefault(false)
            if (saved) {
                foldLayerFiles[index] = file
                if (!bitmap.isRecycled) bitmap.recycle()
            } else {
                file.delete()
                foldLayers[index] = bitmap
            }
        }
    }

    private fun clearFoldLayerFiles() {
        foldLayerFiles.values.forEach(File::delete)
        foldLayerFiles.clear()
    }

    private fun endFoldAfterDisconnect() {
        if (!foldSessionActive) return
        if (isLocalFoldTurn() && !foldTurnEnded && view != null) {
            runCatching { storeFoldLayer(foldIndex, checkNotNull(view).snapshotDrawingLayer()) }
        }
        val result = Bitmap.createBitmap(localBitmap.width, localBitmap.height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val canvas = Canvas(result)
        for (index in 0..foldIndex) {
            val layer = foldLayers[index] ?: foldLayerFiles[index]?.let { file ->
                BitmapFactory.decodeFile(file.absolutePath)
            }
            layer?.let { canvas.drawBitmap(it, 0f, 0f, null); if (it !== foldLayers[index]) it.recycle() }
        }
        localBitmap.recycle()
        localBitmap = result
        foldSessionActive = false
        foldTurnEnded = false
        foldFinishPending = false
        peerFoldPreview = null
        foldLayers.values.forEach { if (!it.isRecycled) it.recycle() }
        foldLayers.clear()
        clearFoldLayerFiles()
        incomingHandoffAnnotations.values.forEach { if (!it.isRecycled) it.recycle() }
        incomingHandoffAnnotations.clear()
        imageTransfers.clear(); foldImageTransfers.clear(); foldHandoffTransfers.clear()
        view?.clearHandoffAnnotations()
        view?.clearFoldTextObjects()
        view?.onDocumentBitmapReplaced()
        (currentActivity as? IndexPaint)?.onFoldSessionChanged()
        toast("Connection lost. Fold game ended; known drawing kept on this device.")
    }

    private fun validFoldPreview(preview: DrawingMessage.FoldPreview): Boolean =
        foldSessionActive && preview.foldIndex == foldIndex && !isLocalFoldTurn() &&
            preview.boundary.isFinite() && preview.boundary in foldPreviousBoundary..1f &&
            preview.x.isFinite() && preview.x in 0f..1f && preview.y.isFinite() && preview.y in 0f..1f &&
            preview.width.isFinite() && preview.width in 0.1f..DrawingDocument.RASTER_WIDTH.toFloat() &&
            preview.undoCount >= 0 && preview.redoCount >= 0

    private fun advanceFold(committedIndex: Int, boundary: Float, localHandoff: Bitmap? = null) {
        if (!foldSessionActive || committedIndex != foldIndex) return
        val handoff = localHandoff ?: incomingHandoffAnnotations.remove(committedIndex)
        handoff?.let { view?.setLastHandoffAnnotation(it) }
        lastFoldStartBoundary = foldPreviousBoundary
        lastFoldEndBoundary = boundary.coerceIn(foldPreviousBoundary, 1f)
        foldPreviousBoundary = boundary.coerceIn(foldPreviousBoundary, 1f)
        compactFoldLayers(committedIndex)
        foldIndex = committedIndex + 1
        foldActivePlayerId = if (foldActivePlayerId == participantId) checkNotNull(peerParticipantId) else participantId
        foldBoundary = foldPreviousBoundary + (1f - foldPreviousBoundary) * DEFAULT_FOLD_FRACTION
        foldTurnEnded = false
        peerFoldPreview = null
        localBitmap.eraseColor(Color.TRANSPARENT)
        view?.beginNextFold()
        (currentActivity as? IndexPaint)?.onFoldSessionChanged()
        publishFoldPreview()
        updateFoldAccessibility()
    }

    private fun revealFoldLayers(): Boolean {
        if ((0..foldIndex).any { it !in foldLayers && it !in foldLayerFiles }) {
            Log.w(TAG, "Reveal is waiting for missing hidden layers")
            return false
        }
        val finalBitmap = Bitmap.createBitmap(localBitmap.width, localBitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(finalBitmap).apply {
            drawColor(Color.WHITE)
            for (index in 0..foldIndex) {
                val bitmap = foldLayers[index] ?: BitmapFactory.decodeFile(checkNotNull(foldLayerFiles[index]).absolutePath)
                    ?: error("Could not decode fold $index")
                drawBitmap(bitmap, 0f, 0f, null)
                if (foldLayers[index] == null) bitmap.recycle()
            }
        }
        localBitmap.recycle()
        localBitmap = finalBitmap
        foldSessionActive = false
        foldRevealed = true
        peerFoldPreview = null
        foldLayers.values.forEach { if (!it.isRecycled) it.recycle() }
        foldLayers.clear()
        clearFoldLayerFiles()
        view?.clearHandoffAnnotations()
        view?.clearFoldTextObjects()
        view?.onDocumentBitmapReplaced()
        (currentActivity as? IndexPaint)?.onFoldSessionChanged()
        updateFoldAccessibility()
        toast("All folds revealed")
        return true
    }

    private fun updateFoldAccessibility() {
        val canvasView = view ?: return
        if (!foldSessionActive) {
            canvasView.contentDescription = "Index Paint canvas. Drawing revealed."
            return
        }
        val metadataVisible = if (view?.hasLastHandoffMetadata() == true) " Handoff metadata is visible." else ""
        canvasView.contentDescription = when {
            !isLocalFoldTurn() -> "Index Paint canvas. Waiting for ${peerDisplayName()}. Turn ${foldIndex + 1}.$metadataVisible"
            foldTurnEnded -> "Index Paint canvas. Add a handoff note with your finger or Text, then tap Done to pass the turn. ${view?.pendingHandoffMarkCount() ?: 0} notes added.$metadataVisible"
            else -> "Index Paint canvas. Your turn to draw. Turn ${foldIndex + 1}. Tap End Turn to add a note for your partner and pass, or finish and reveal the drawing.$metadataVisible"
        }
    }


    fun publishFoldPreview(
        x: Float = localFoldCursorX,
        y: Float = localFoldCursorY,
        drawing: Boolean = localFoldCursorDrawing,
    ) {
        if (!foldSessionActive || !isLocalFoldTurn()) return
        val drawingState = view
        val draw = drawingState?.localDraw ?: return
        val width = localBitmap.width.coerceAtLeast(1)
        val height = localBitmap.height.coerceAtLeast(1)
        val preview = DrawingMessage.FoldPreview(
            foldIndex, foldBoundary, (x / width).coerceIn(0f, 1f), (y / height).coerceIn(0f, 1f), drawing,
            draw.getColor(), draw.getBrushStyle(), draw.getDrawWidth(), draw.isEraserEnabled(),
            drawingState.undoCount(), drawingState.redoCount(),
        )
        peerFoldPreview = preview
        sendMessage(preview)
        view?.invalidate()
    }

    private fun isModeNegotiation(message: DrawingMessage): Boolean = when (message) {
        is DrawingMessage.FoldHello, is DrawingMessage.ProtocolVersion, is DrawingMessage.SessionModeChoice,
        is DrawingMessage.FoldProposal, DrawingMessage.FoldCancel -> true
        else -> false
    }

    private fun isFoldControl(message: DrawingMessage): Boolean = when (message) {
        is DrawingMessage.FoldPreview, is DrawingMessage.FoldLayerChunk,
        is DrawingMessage.FoldHandoffChunk, is DrawingMessage.FoldYield,
        is DrawingMessage.FoldYieldAck,
        DrawingMessage.FoldFinishRequest, is DrawingMessage.FoldFinishResponse -> true
        else -> false
    }

    fun sendMessage(message: DrawingMessage): Boolean {
        if (isConnectedToPeer() && !isModeNegotiation(message)) {
            if (isFoldControl(message)) {
                if (sessionMode != SessionMode.FOLD_GAME) return false
            } else if (!isSharedDrawingEnabled() && !foldSessionActive) {
                return false
            }
        }
        if (foldSessionActive) {
            when (message) {
                is DrawingMessage.TouchStart -> {
                    localFoldCursorX = message.x
                    localFoldCursorY = message.y
                    localFoldCursorDrawing = true
                    publishFoldPreview()
                    return true
                }
                is DrawingMessage.TouchMove -> {
                    localFoldCursorX = message.x
                    localFoldCursorY = message.y
                    localFoldCursorDrawing = true
                    publishFoldPreview()
                    return true
                }
                DrawingMessage.TouchUp -> {
                    localFoldCursorDrawing = false
                    publishFoldPreview()
                    return true
                }
                DrawingMessage.Undo, DrawingMessage.Redo,
                is DrawingMessage.SetColor, is DrawingMessage.SetBrush, is DrawingMessage.SetWidth,
                is DrawingMessage.SetAlpha, DrawingMessage.ClearBlendMode, is DrawingMessage.SetBlendMode,
                is DrawingMessage.SetMask -> {
                    publishFoldPreview()
                    return true
                }
                is DrawingMessage.FoldHello, is DrawingMessage.ProtocolVersion, is DrawingMessage.FoldProposal, is DrawingMessage.FoldPreview,
                is DrawingMessage.FoldLayerChunk, is DrawingMessage.FoldHandoffChunk, is DrawingMessage.FoldYield, DrawingMessage.FoldCancel,
                is DrawingMessage.FoldYieldAck,
                DrawingMessage.FoldFinishRequest, is DrawingMessage.FoldFinishResponse -> Unit
                else -> return true
            }
        }
        return try {
            val activeTransport = transport ?: return false
            if (!activeTransport.isConnected) return false
            activeTransport.send(DrawingProtocol.encode(message))
            true
        } catch (error: Exception) { Log.e(TAG, "Failed to send peer message", error); false }
    }

    fun shareBitmap() {
        if (transport?.isConnected != true) return
        try {
            val output = ByteArrayOutputStream()
            val snapshot = view?.snapshotDrawingLayer()
            (snapshot ?: localBitmap).compress(Bitmap.CompressFormat.PNG, 100, output)
            snapshot?.recycle()
            val encoded = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            val transferId = UUID.randomUUID().toString()
            val total = (encoded.length + IMAGE_CHUNK_CHARS - 1) / IMAGE_CHUNK_CHARS
            for (index in 0 until total) {
                val start = index * IMAGE_CHUNK_CHARS
                sendMessage(DrawingMessage.ImageChunk(transferId, index, total, encoded.substring(start, minOf(start + IMAGE_CHUNK_CHARS, encoded.length))))
            }
        } catch (error: Exception) { Log.e(TAG, "Could not share bitmap", error) }
    }

    fun changePaperSize(size: PaperSize, notifyPeer: Boolean = true) {
        if (size == paperSize) return
        val replacement = createPaperBitmap(size)
        Canvas(replacement).drawBitmap(localBitmap, null, Rect(0, 0, replacement.width, replacement.height), Paint(Paint.FILTER_BITMAP_FLAG))
        localBitmap.recycle()
        localBitmap = replacement
        paperSize = size
        view?.onDocumentBitmapReplaced()
        view?.invalidate()
        if (notifyPeer) sendMessage(DrawingMessage.SetPaperSize(size))
    }

    private fun receiveImageChunk(chunk: DrawingMessage.ImageChunk) {
        addChunk(imageTransfers, chunk.transferId, -1, chunk.index, chunk.total, chunk.base64, IMAGE_CHUNK_CHARS)?.let { bitmap ->
            view?.drawBitmap(bitmap)
            bitmap.recycle()
        }
    }

    fun saveBitmap() {
        val folder = getExternalFilesDir("Pictures") ?: filesDir
        val file = File(folder, String.format(Locale.US, "IndexPaint-%d.png", Calendar.getInstance().timeInMillis))
        try {
            val snapshot = view?.snapshotDrawingLayer()
            FileOutputStream(file).use { (snapshot ?: localBitmap).compress(Bitmap.CompressFormat.PNG, 100, it) }
            snapshot?.recycle()
            toast("Saved to ${file.absolutePath}")
        } catch (error: Exception) { Log.e(TAG, "Unable to save drawing", error); toast("Could not save drawing") }
    }

    fun setCurrentActivity(activity: Activity) {
        currentActivity = activity
        if (view == null) view = MyView(this)
    }

    private fun updateConnectionStatus(status: String) {
        connectionStatus = status
        (currentActivity as? IndexPaint)?.showConnectionStatus(status)
    }

    fun clearActivity(activity: Activity) { if (currentActivity === activity) currentActivity = null }

    fun connect() {
        if (!BluetoothTransport.hasBluetooth()) { toast("Bluetooth is not available"); return }
        if (bluetoothTransport == null) {
            bluetoothTransport = BluetoothTransport(commandHandler, SERVICE_UUID, SERVICE_NAME)
            transport = bluetoothTransport
        }
        BluetoothTransport.enableBluetooth(checkNotNull(currentActivity), REQUEST_INITIATE_CONNECTION)
    }

    fun disconnect() { transport?.disconnect() }

    fun onConnectionResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val activity = currentActivity ?: return
        when (requestCode) {
            REQUEST_INITIATE_CONNECTION -> if (BluetoothTransport.isBluetoothEnabled()) {
                if (BluetoothTransport.ensureDiscoverability(activity, REQUEST_DISCOVERABLE)) listenAndScan()
            } else BluetoothTransport.enableBluetooth(activity, REQUEST_ENABLE_BT)
            REQUEST_ENABLE_BT -> if (resultCode == Activity.RESULT_OK) {
                if (BluetoothTransport.ensureDiscoverability(activity, REQUEST_DISCOVERABLE)) listenAndScan()
            } else disconnect()
            REQUEST_DISCOVERABLE -> if (resultCode > 0) listenAndScan() else disconnect()
            REQUEST_DEVICE_LIST -> if (resultCode == Activity.RESULT_OK) {
                val address = data?.getStringExtra(DeviceListActivity.EXTRA_DEVICE_ADDRESS) ?: return
                val device: BluetoothDevice = bluetoothTransport?.getAdapter()?.getRemoteDevice(address) ?: return
                if (bluetoothTransport?.isConnected != true) bluetoothTransport?.connect(device)
            }
        }
    }

    private fun listenAndScan() {
        bluetoothTransport?.startListening()
        currentActivity?.startActivityForResult(Intent(currentActivity, DeviceListActivity::class.java), REQUEST_DEVICE_LIST)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "PaintApp"
        const val REQUEST_INITIATE_CONNECTION = 1
        const val REQUEST_ENABLE_BT = 1232
        const val REQUEST_DISCOVERABLE = 1233
        const val REQUEST_DEVICE_LIST = 1234
        private const val IMAGE_CHUNK_CHARS = 150
        private const val FOLD_IMAGE_CHUNK_CHARS = 180
        private const val CURRENT_PROTOCOL_VERSION = 2
        private const val MAX_YIELD_RETRIES = 6
        private const val YIELD_RETRY_BASE_MS = 750L
        private const val YIELD_RETRY_MAX_MS = 6_000L
        private const val MAX_IMAGE_BYTES = 15 * 1024 * 1024
        private const val MAX_ENCODED_IMAGE_CHARS = 20 * 1024 * 1024
        // Match createPaperBitmap(): the A4 height is derived from the float aspect ratio
        // and truncation, which yields 2172 pixels. Keep the transfer validation in sync.
        private val MAX_DOCUMENT_PIXELS = DrawingDocument.RASTER_WIDTH.toLong() *
            DrawingDocument.heightFor(PaperSize.A4)
        private const val TRANSFER_TIMEOUT_MS = 30_000L
        private const val FOLD_SEED_BYTES = 32
        private const val DEFAULT_FOLD_FRACTION = 0.5f
        private const val SERVICE_NAME = "IndexPaintService"
        private val SERVICE_UUID = BluetoothTransport.SERVICE_UUID
        private val secureRandom = SecureRandom()

        private fun createPaperBitmap(size: PaperSize) = Bitmap.createBitmap(
            DrawingDocument.RASTER_WIDTH,
            DrawingDocument.heightFor(size),
            Bitmap.Config.ARGB_8888,
        ).apply { eraseColor(Color.WHITE) }
    }
}
