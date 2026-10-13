package com.liftr.feedback

import com.liftr.domain.feedback.FeedbackLanguage
import com.liftr.domain.feedback.FeedbackVerbosity
import com.liftr.domain.feedback.IFeedbackEmitter
import com.liftr.domain.feedback.TtsEngineInfo
import com.liftr.domain.model.FeedbackEvent

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
    var language: FeedbackLanguage = FeedbackLanguage.DEFAULT
        private set
    var verbosity: FeedbackVerbosity = FeedbackVerbosity.DEFAULT
        private set
    var ttsEngine: String? = null
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

    override fun setLanguage(language: FeedbackLanguage) {
        this.language = language
    }

    override fun setVerbosity(verbosity: FeedbackVerbosity) {
        this.verbosity = verbosity
    }

    override fun setTtsEngine(packageName: String?) {
        this.ttsEngine = packageName
    }

    override fun getAvailableEngines(): List<TtsEngineInfo> = emptyList()

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
