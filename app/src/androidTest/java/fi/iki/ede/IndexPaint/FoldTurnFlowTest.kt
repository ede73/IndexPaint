package fi.iki.ede.IndexPaint

import android.graphics.Color
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class FoldTurnFlowTest {
    @Test
    fun endTurnOffersAnnotationOrRevealAndDonePassesTheAnnotatedTurn() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PaintApp
        val transport = RecordingTransport()
        val scenario = ActivityScenario.launch(IndexPaint::class.java)

        configureLocalTurn(scenario, app, transport)
        onView(withText("End Turn")).perform(click())
        onView(withText("Add handoff note")).check(matches(isDisplayed()))
        onView(withText("Add a note for Test Partner, then tap Done to pass the turn.")).check(matches(isDisplayed()))
        onView(withText("Finish drawing & reveal")).check(matches(isDisplayed()))

        onView(withText("Add handoff note")).perform(click())
        assertTrue(app.foldTurnEnded)
        onView(withText("Done")).check(matches(isDisplayed()))

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            app.view!!.addTextObject("Look here", 24f, Color.RED, "sans-serif")
        }
        onView(withText("Done")).perform(click())
        assertTrue("Done should start the handoff", app.foldYieldPending)

        val yield = awaitMessage<DrawingMessage.FoldYield>(transport) { it is DrawingMessage.FoldYield }
        assertTrue(transport.messages.any { it is DrawingMessage.FoldHandoffChunk })
        deliverToApp(app, DrawingMessage.FoldYieldAck(yield.messageId, yield.foldIndex, accepted = true))
        assertFalse(app.foldYieldPending)
        assertEquals(1, app.foldIndex)
        assertFalse("The peer owns the next turn", app.isLocalFoldTurn())

        configureLocalTurn(scenario, app, transport)
        onView(withText("End Turn")).perform(click())
        onView(withText("Finish drawing & reveal")).perform(click())
        assertTrue("Choosing reveal should send the final drawing", app.foldFinishPending)
        assertTrue(transport.messages.any { it == DrawingMessage.FoldFinishRequest })
        deliverToApp(app, DrawingMessage.FoldFinishResponse(accepted = true))
        assertTrue(app.foldRevealed)
        assertFalse(app.foldSessionActive)

        scenario.close()
    }

    private fun configureLocalTurn(
        scenario: ActivityScenario<IndexPaint>,
        app: PaintApp,
        transport: RecordingTransport,
    ) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val localId = app.localParticipantId()
            setField(app, "transport", transport)
            setField(app, "connectedDeviceName", "Test Partner")
            setField(app, "peerParticipantId", "test-peer-id")
            setField(app, "sessionMode", SessionMode.FOLD_GAME)
            setField(app, "foldSessionActive", true)
            setField(app, "foldTurnEnded", false)
            setField(app, "foldRevealed", false)
            setField(app, "foldIndex", 0)
            setField(app, "foldPreviousBoundary", 0f)
            setField(app, "foldBoundary", 0.5f)
            setField(app, "foldFirstPlayerId", localId)
            setField(app, "foldActivePlayerId", localId)
            setField(app, "foldFinishPending", false)
            setField(app, "foldYieldPending", false)
            app.foldLayers.values.forEach { if (!it.isRecycled) it.recycle() }
            app.foldLayers.clear()
            app.localBitmap.eraseColor(Color.TRANSPARENT)
            app.view!!.beginFoldSession()
        }
        scenario.onActivity { it.onFoldSessionChanged() }
    }

    private fun deliverToApp(app: PaintApp, message: DrawingMessage) {
        val payload = DrawingProtocol.encode(message)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            PaintApp::class.java.getDeclaredMethod("handleCommand", ByteArray::class.java).apply {
                isAccessible = true
                invoke(app, payload)
            }
        }
    }

    private inline fun <reified T : DrawingMessage> awaitMessage(
        transport: RecordingTransport,
        crossinline predicate: (DrawingMessage) -> Boolean,
    ): T {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            @Suppress("UNCHECKED_CAST")
            transport.messages.firstOrNull(predicate)?.let { return it as T }
            SystemClock.sleep(50)
        }
        throw AssertionError("Timed out waiting for ${T::class.java.simpleName}; got ${transport.messages}")
    }

    private fun setField(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private class RecordingTransport : DrawingTransport {
        override val isConnected = true
        val messages = CopyOnWriteArrayList<DrawingMessage>()

        override fun send(payload: ByteArray) {
            val decoded = DrawingProtocol.decode(payload)
            assertNotNull("Invalid protocol payload", decoded)
            messages += decoded!!
        }

        override fun disconnect() = Unit
    }
}
