package com.chase.tidetoolvoicelog.media

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Live transcript for a voice note, using the phone's own speech service.
 *
 * This is best-effort by design: on Android 10+ the speech service is a separate
 * app, and while it holds the microphone the recorder may get nothing. If the
 * service errors out (busy, no match, no service) the note goes quiet and the
 * audio is still recorded - the screen says so, and the note is transcribed on
 * the device after saving instead.
 */
class LiveSpeech(
    private val context: Context,
    private val onText: (finals: String, interim: String) -> Unit,
    private val onNote: (String) -> Unit
) {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var want = false
    private val finals = StringBuilder()

    var available: Boolean = SpeechRecognizer.isRecognitionAvailable(context)
        private set
    var failed: Boolean = false
        private set

    fun start() {
        if (!available) {
            onNote("This phone has no speech recognition service. The audio is still being recorded - it transcribes on the device after you save.")
            return
        }
        want = true
        finals.setLength(0)
        main.post { createAndListen() }
    }

    private fun createAndListen() {
        if (!want) return
        if (recognizer == null) {
            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(object : android.speech.RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}

                        override fun onError(error: Int) {
                            val msg = when (error) {
                                SpeechRecognizer.ERROR_AUDIO -> "the microphone was taken by the speech service"
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission missing"
                                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "no network for the speech service"
                                SpeechRecognizer.ERROR_NO_MATCH -> "no words recognised"
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "the speech service was busy"
                                SpeechRecognizer.ERROR_SERVER -> "speech service error"
                                else -> "speech service error $error"
                            }
                            failed = true
                            want = false
                            onNote("Live transcription stopped ($msg). The audio is still being recorded - it transcribes on the device after you save.")
                        }

                        override fun onResults(results: Bundle?) {
                            val text = results
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                                ?.trim()
                                .orEmpty()
                            if (text.isNotEmpty()) {
                                if (finals.isNotEmpty()) finals.append(' ')
                                finals.append(text)
                            }
                            onText(finals.toString(), "")
                            if (want) main.postDelayed({ if (want) listen() }, 350)
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val text = partialResults
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                                ?.trim()
                                .orEmpty()
                            if (text.isNotEmpty()) onText(finals.toString(), text)
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
            } catch (e: Exception) {
                available = false
                onNote("The speech service could not start. The audio is still being recorded.")
                return
            }
        }
        listen()
    }

    private fun listen() {
        val r = recognizer ?: return
        if (!want) return
        try {
            r.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                }
            )
        } catch (e: Exception) {
            failed = true
            onNote("Live transcription could not start. The audio is still being recorded.")
        }
    }

    /** @return the text that will not change any more */
    fun stop(): String {
        want = false
        main.post {
            runCatching { recognizer?.stopListening() }
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
        return finals.toString().trim()
    }

    fun cancel() {
        want = false
        main.post {
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
    }
}
