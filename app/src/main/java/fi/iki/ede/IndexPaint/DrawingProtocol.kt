package fi.iki.ede.IndexPaint

enum class BrushStyle { PEN, MARKER, PENCIL }
enum class SessionMode { FREE_DRAW, FOLD_GAME }

/** Transport-independent events exchanged by two drawing peers. */
sealed interface DrawingMessage {
    data object Initialize : DrawingMessage
    data object Undo : DrawingMessage
    data object Redo : DrawingMessage
    data class TouchStart(val x: Float, val y: Float, val pressure: Float) : DrawingMessage
    data class TouchMove(val x: Float, val y: Float, val pressure: Float) : DrawingMessage
    data object TouchUp : DrawingMessage
    data class SetColor(val color: Int) : DrawingMessage
    data class SetBrush(val brush: BrushStyle) : DrawingMessage
    data class SetWidth(val width: Float) : DrawingMessage
    data class SetAlpha(val alpha: Int) : DrawingMessage
    data object ClearBlendMode : DrawingMessage
    data class SetBlendMode(val mode: String, val alpha: Int) : DrawingMessage
    data class SetMask(val mask: String) : DrawingMessage
    data class SetPaperSize(val paperSize: PaperSize) : DrawingMessage
    data class ImageChunk(val transferId: String, val index: Int, val total: Int, val base64: String) : DrawingMessage
    data class TextUpdate(val id: String, val text64: String, val x: Float, val y: Float, val size: Float, val color: Int, val font: String, val deleted: Boolean) : DrawingMessage
    data class FoldHello(val participantId: String) : DrawingMessage
    data class ProtocolVersion(val version: Int) : DrawingMessage
    data class FoldProposal(val randomSeedHex: String) : DrawingMessage
    data class SessionModeChoice(val mode: SessionMode) : DrawingMessage
    data class FoldPreview(
        val foldIndex: Int,
        val boundary: Float,
        val x: Float,
        val y: Float,
        val drawing: Boolean,
        val color: Int,
        val brush: BrushStyle,
        val width: Float,
        val erasing: Boolean,
        val undoCount: Int,
        val redoCount: Int,
    ) : DrawingMessage
    data class FoldLayerChunk(val transferId: String, val foldIndex: Int, val index: Int, val total: Int, val base64: String) : DrawingMessage
    data class FoldHandoffChunk(val transferId: String, val foldIndex: Int, val index: Int, val total: Int, val base64: String) : DrawingMessage
    data class FoldYield(val messageId: String, val foldIndex: Int, val boundary: Float) : DrawingMessage
    data class FoldYieldAck(val messageId: String, val foldIndex: Int, val accepted: Boolean) : DrawingMessage
    data object FoldFinishRequest : DrawingMessage
    data class FoldFinishResponse(val accepted: Boolean) : DrawingMessage
    data object FoldCancel : DrawingMessage
}

/** Encodes drawing events independently of Bluetooth, TCP, UDP, or any other transport. */
object DrawingProtocol {
    const val MASK_CLEAR = "clear"
    const val MASK_BLUR = "blur"
    const val MASK_EMBOSS = "emboss"
    fun encode(message: DrawingMessage): ByteArray = encodeText(message).toByteArray(Charsets.UTF_8)

    fun decode(payload: ByteArray): DrawingMessage? = decodeText(payload.toString(Charsets.UTF_8))

    fun encodeText(message: DrawingMessage): String = when (message) {
        DrawingMessage.Initialize -> "init:0"
        DrawingMessage.Undo -> "undo:0"
        DrawingMessage.Redo -> "redo:0"
        is DrawingMessage.TouchStart -> "touch_down:${message.x},${message.y},${message.pressure}"
        is DrawingMessage.TouchMove -> "touch_move:${message.x},${message.y},${message.pressure}"
        DrawingMessage.TouchUp -> "touch_up:0"
        is DrawingMessage.SetColor -> "color:${message.color}"
        is DrawingMessage.SetBrush -> "brush:${message.brush.name}"
        is DrawingMessage.SetWidth -> "width:${message.width}"
        is DrawingMessage.SetAlpha -> "alpha:${message.alpha}"
        DrawingMessage.ClearBlendMode -> "xferclear:0"
        is DrawingMessage.SetBlendMode -> "duff:${message.mode},${message.alpha}"
        is DrawingMessage.SetMask -> "mask:${message.mask}"
        is DrawingMessage.SetPaperSize -> "paper:${message.paperSize.name}"
        is DrawingMessage.ImageChunk -> "image:${message.transferId},${message.index},${message.total},${message.base64}"
        is DrawingMessage.TextUpdate -> "text:${message.id},${message.text64},${message.x},${message.y},${message.size},${message.color},${message.font},${message.deleted}"
        is DrawingMessage.FoldHello -> "fold_hello:${message.participantId}"
        is DrawingMessage.ProtocolVersion -> "protocol_version:${message.version}"
        is DrawingMessage.FoldProposal -> "fold_proposal:${message.randomSeedHex}"
        is DrawingMessage.SessionModeChoice -> "session_mode:${message.mode.name}"
        is DrawingMessage.FoldPreview -> "fold_preview:${message.foldIndex},${message.boundary},${message.x},${message.y},${message.drawing},${message.color},${message.brush.name},${message.width},${message.erasing},${message.undoCount},${message.redoCount}"
        is DrawingMessage.FoldLayerChunk -> "fold_layer:${message.transferId},${message.foldIndex},${message.index},${message.total},${message.base64}"
        is DrawingMessage.FoldHandoffChunk -> "fold_meta:${message.transferId},${message.foldIndex},${message.index},${message.total},${message.base64}"
        is DrawingMessage.FoldYield -> "fold_yield:${message.messageId},${message.foldIndex},${message.boundary}"
        is DrawingMessage.FoldYieldAck -> "fold_yield_ack:${message.messageId},${message.foldIndex},${message.accepted}"
        DrawingMessage.FoldFinishRequest -> "fold_finish_request:0"
        is DrawingMessage.FoldFinishResponse -> "fold_finish_response:${message.accepted}"
        DrawingMessage.FoldCancel -> "fold_cancel:0"
    }

    fun decodeText(text: String): DrawingMessage? = runCatching {
        val (command, raw) = text.split(':', limit = 2).let { require(it.size == 2); it[0] to it[1] }
        val args = raw.split(',')
        when (command) {
            "init" -> DrawingMessage.Initialize
            "undo" -> DrawingMessage.Undo
            "redo" -> DrawingMessage.Redo
            "touch_down" -> DrawingMessage.TouchStart(args[0].toFloat(), args[1].toFloat(), args[2].toFloat())
            "touch_move" -> DrawingMessage.TouchMove(args[0].toFloat(), args[1].toFloat(), args[2].toFloat())
            "touch_up" -> DrawingMessage.TouchUp
            "color" -> DrawingMessage.SetColor(args[0].toLong().toInt())
            "brush" -> DrawingMessage.SetBrush(BrushStyle.valueOf(args[0]))
            "width" -> DrawingMessage.SetWidth(args[0].toFloat())
            "alpha" -> DrawingMessage.SetAlpha(args[0].toInt())
            "xferclear" -> DrawingMessage.ClearBlendMode
            "duff" -> DrawingMessage.SetBlendMode(args[0], args[1].toInt())
            "mask" -> DrawingMessage.SetMask(args[0])
            "paper" -> DrawingMessage.SetPaperSize(PaperSize.valueOf(args[0]))
            "image" -> DrawingMessage.ImageChunk(args[0], args[1].toInt(), args[2].toInt(), args[3])
            "text" -> DrawingMessage.TextUpdate(args[0], args[1], args[2].toFloat(), args[3].toFloat(), args[4].toFloat(), args[5].toInt(), args[6], args[7].toBooleanStrict())
            "fold_hello" -> DrawingMessage.FoldHello(raw)
            "protocol_version" -> DrawingMessage.ProtocolVersion(raw.toInt())
            "fold_proposal" -> DrawingMessage.FoldProposal(raw)
            "session_mode" -> DrawingMessage.SessionModeChoice(SessionMode.valueOf(raw))
            "fold_preview" -> DrawingMessage.FoldPreview(
                args[0].toInt(), args[1].toFloat(), args[2].toFloat(), args[3].toFloat(),
                args[4].toBooleanStrict(), args[5].toInt(), BrushStyle.valueOf(args[6]),
                args[7].toFloat(), args[8].toBooleanStrict(), args[9].toInt(), args[10].toInt(),
            )
            "fold_layer" -> DrawingMessage.FoldLayerChunk(args[0], args[1].toInt(), args[2].toInt(), args[3].toInt(), args[4])
            "fold_meta" -> DrawingMessage.FoldHandoffChunk(args[0], args[1].toInt(), args[2].toInt(), args[3].toInt(), args[4])
            "fold_yield" -> DrawingMessage.FoldYield(args[0], args[1].toInt(), args[2].toFloat())
            "fold_yield_ack" -> DrawingMessage.FoldYieldAck(args[0], args[1].toInt(), args[2].toBooleanStrict())
            "fold_finish_request" -> DrawingMessage.FoldFinishRequest
            "fold_finish_response" -> DrawingMessage.FoldFinishResponse(args[0].toBooleanStrict())
            "fold_cancel" -> DrawingMessage.FoldCancel
            else -> null
        }
    }.getOrNull()
}

/** Byte-message delivery contract. A transport moves protocol payloads without interpreting them. */
interface DrawingTransport {
    val isConnected: Boolean
    fun send(payload: ByteArray)
    fun disconnect()
}
