package com.roadmemory.app.sdk

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

/**
 * TextToSpeech + SpeechToText in ON_DEVICE mode (no server keys, works offline,
 * nothing leaves the car). The mic opens only via [listen] — right after a question.
 */
class VoiceIO(context: Context, private val callback: Callback) {

    interface Callback {
        fun onSpeakDone()
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onListenEnded()
        fun onVoiceError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(context.applicationContext, TtsMode.ON_DEVICE)
    private val stt = SpeechToText(context.applicationContext, SttMode.ON_DEVICE)

    var ttsReady = false
        private set
    var sttReady = false
        private set
    var listening = false
        private set

    private var afterSpeak: (() -> Unit)? = null

    private val ttsEvents = object : EventListener {
        override fun onStart() = Unit
        override fun onReady() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onStop() = Unit
        override fun onDone() {
            main.post {
                callback.onSpeakDone()
                afterSpeak?.invoke()
                afterSpeak = null
            }
        }

        override fun onError(errorType: TtsError) {
            main.post {
                callback.onVoiceError("TTS: $errorType")
                afterSpeak?.invoke()
                afterSpeak = null
            }
        }
    }

    private val sttEvents = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() {
            listening = true
        }

        override fun onUpdated(stt: String, completed: Boolean) {
            main.post { if (completed) callback.onFinal(stt) else callback.onPartial(stt) }
        }

        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onEndedRecognition() {
            listening = false
            main.post { callback.onListenEnded() }
        }

        override fun onError(errorType: SttError) {
            listening = false
            main.post { callback.onVoiceError("STT: $errorType") }
        }
    }

    fun start() {
        try {
            tts.initialize(object : TtsInit {
                override fun onSuccess() {
                    ttsReady = true
                    runCatching { tts.setLocale(Locale.KOREA) }
                    tts.addEventListener(ttsEvents)
                }

                override fun onFailure(errorType: TtsError) {
                    Log.w(TAG, "TTS init failed: $errorType")
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "TTS unavailable: ${t.message}")
        }
        try {
            stt.initialize(object : SttInit {
                override fun onSuccess() {
                    sttReady = true
                    runCatching { stt.setLocale(Locale.KOREA) }
                    stt.addListener(sttEvents)
                }

                override fun onFailure(errorType: SttError) {
                    Log.w(TAG, "STT init failed: $errorType")
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "STT unavailable: ${t.message}")
        }
    }

    /** Speak; [then] runs after playback (or immediately if TTS is unavailable). */
    fun speak(text: String, then: (() -> Unit)? = null) {
        if (!ttsReady) {
            then?.let { main.post(it) }
            return
        }
        afterSpeak = then
        runCatching { tts.speak(text, true) }.onFailure {
            afterSpeak = null
            then?.let { main.post(it) }
        }
    }

    fun listen() {
        if (!sttReady) {
            callback.onVoiceError("STT 준비 안 됨")
            return
        }
        runCatching { stt.request() }.onFailure { callback.onVoiceError("STT: ${it.message}") }
    }

    fun stopAll() {
        runCatching { tts.stop() }
        runCatching { stt.stop() }
        listening = false
    }

    fun release() {
        stopAll()
        runCatching { tts.removeEventListener(ttsEvents) }
        runCatching { stt.removeListener(sttEvents) }
        runCatching { tts.release() }
        runCatching { stt.release() }
    }

    companion object {
        private const val TAG = "RM/Voice"
    }
}
