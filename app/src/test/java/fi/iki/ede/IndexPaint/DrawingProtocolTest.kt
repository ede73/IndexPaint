package fi.iki.ede.IndexPaint

import org.junit.Assert.assertEquals
import org.junit.Test

class DrawingProtocolTest {
    @Test
    fun yieldTransactionAndAcknowledgementKeepTheirMessageId() {
        val id = "1c946e65-1b68-42ea-b915-45bb4aeaf101"
        val yield = DrawingMessage.FoldYield(id, 3, 0.625f)
        val ack = DrawingMessage.FoldYieldAck(id, 3, true)

        assertEquals(yield, DrawingProtocol.decode(DrawingProtocol.encode(yield)))
        assertEquals(ack, DrawingProtocol.decode(DrawingProtocol.encode(ack)))
    }

    @Test
    fun protocolVersionIsRecognized() {
        val version = DrawingMessage.ProtocolVersion(2)
        assertEquals(version, DrawingProtocol.decode(DrawingProtocol.encode(version)))
    }
}
