package com.lifthelper.feedback

import com.lifthelper.domain.feedback.IFeedbackEmitter
import com.lifthelper.domain.model.FeedbackEvent

/**
 * Test double for [IFeedbackEmitter] that records all emitted events
 * for assertions in unit/integration tests.
 */
class FakeFeedbackEmitter : IFeedbackEmitter {

    val emittedEvents = mutableListOf<FeedbackEvent>()
    var audioEnabled = true
        private set
    var hapticEnabled = true
        private set
    var speechStopped = false
        private set
    var isShutdown = false
        private set

    override fun emit(event: FeedbackEvent) {
        emittedEvents.add(event)
    }

    override fun setAudioEnabled(enabled: Boolean) {
        audioEnabled = enabled
    }

    override fun setHapticEnabled(enabled: Boolean) {
        hapticEnabled = enabled
    }

    override fun stopCurrentSpeech() {
        speechStopped = true
    }

    override fun shutdown() {
        isShutdown = true
    }

    fun clear() {
        emittedEvents.clear()
        speechStopped = false
    }

    inline fun <reified T : FeedbackEvent> eventsOfType(): List<T> =
        emittedEvents.filterIsInstance<T>()
}
