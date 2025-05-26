package com.ivip.brainstormia.utils

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class SpeechRecognizerHelper(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onError: (Int) -> Unit,
    private val onStartListening: () -> Unit,
    private val onEndListening: () -> Unit
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isListening = false

    private val speechRecognizerIntent: Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    init {
        Log.d(TAG, "Initializing SpeechRecognizerHelper")
        createSpeechRecognizer()
    }

    private fun createSpeechRecognizer() {
        mainHandler.post {
            try {
                if (SpeechRecognizer.isRecognitionAvailable(context)) {
                    Log.d(TAG, "Speech recognition is available")

                    // Ensure we're on the main thread
                    if (Looper.myLooper() != Looper.getMainLooper()) {
                        Log.e(TAG, "Not on main thread!")
                        return@post
                    }

                    speechRecognizer?.destroy()
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).apply {
                        setRecognitionListener(createRecognitionListener())
                    }
                    Log.d(TAG, "SpeechRecognizer created successfully")
                } else {
                    Log.e(TAG, "Speech recognition not available on this device")
                    onError(ERROR_NOT_AVAILABLE)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating speech recognizer", e)
                onError(SpeechRecognizer.ERROR_CLIENT)
            }
        }
    }

    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "onReadyForSpeech")
            mainHandler.post {
                isListening = true
                onStartListening()
            }
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "onBeginningOfSpeech")
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Log.v(TAG, "onRmsChanged: $rmsdB")
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            Log.d(TAG, "onBufferReceived")
        }

        override fun onEndOfSpeech() {
            Log.d(TAG, "onEndOfSpeech")
            mainHandler.post {
                isListening = false
                onEndListening()
            }
        }

        override fun onError(error: Int) {
            Log.e(TAG, "onError: $error (${getErrorName(error)})")
            mainHandler.post {
                isListening = false
                onError(error)
                onEndListening()

                // Handle specific errors
                when (error) {
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        Log.w(TAG, "Recognizer busy, recreating...")
                        recreateSpeechRecognizer()
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        Log.e(TAG, "Insufficient permissions")
                    }
                    SpeechRecognizer.ERROR_NO_MATCH -> {
                        Log.w(TAG, "No speech match")
                    }
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        Log.w(TAG, "Speech timeout")
                    }
                }
            }
        }

        override fun onResults(results: Bundle?) {
            Log.d(TAG, "onResults")
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                Log.d(TAG, "Recognized text: ${matches[0]}")
                mainHandler.post {
                    onResult(matches[0])
                    isListening = false
                    onEndListening()
                }
            } else {
                Log.w(TAG, "No matches in results")
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            Log.d(TAG, "onPartialResults")
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                Log.d(TAG, "Partial result: ${matches[0]}")
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            Log.d(TAG, "onEvent: $eventType")
        }
    }

    private fun recreateSpeechRecognizer() {
        destroy()
        createSpeechRecognizer()
    }

    fun startListening() {
        Log.d(TAG, "startListening called")
        mainHandler.post {
            try {
                if (isListening) {
                    Log.w(TAG, "Already listening")
                    return@post
                }

                if (speechRecognizer == null) {
                    Log.w(TAG, "SpeechRecognizer is null, creating...")
                    createSpeechRecognizer()
                    // Wait a bit for the recognizer to be created
                    mainHandler.postDelayed({
                        startListeningInternal()
                    }, 100)
                } else {
                    startListeningInternal()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in startListening", e)
                onError(SpeechRecognizer.ERROR_CLIENT)
            }
        }
    }

    private fun startListeningInternal() {
        try {
            speechRecognizer?.startListening(speechRecognizerIntent)
            Log.d(TAG, "startListening called on SpeechRecognizer")
        } catch (e: Exception) {
            Log.e(TAG, "Error calling startListening", e)
            onError(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    fun stopListening() {
        Log.d(TAG, "stopListening called")
        mainHandler.post {
            try {
                if (isListening) {
                    speechRecognizer?.stopListening()
                    isListening = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping listening", e)
            }
        }
    }

    fun destroy() {
        Log.d(TAG, "destroy called")
        mainHandler.post {
            try {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
                speechRecognizer = null
                isListening = false
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying recognizer", e)
            }
        }
    }

    private fun getErrorName(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            ERROR_NOT_AVAILABLE -> "ERROR_NOT_AVAILABLE"
            else -> "UNKNOWN_ERROR ($error)"
        }
    }

    companion object {
        private const val TAG = "SpeechRecognizerHelper"
        private const val ERROR_NOT_AVAILABLE = 999

        fun isAvailable(context: Context): Boolean {
            return SpeechRecognizer.isRecognitionAvailable(context)
        }
    }
}