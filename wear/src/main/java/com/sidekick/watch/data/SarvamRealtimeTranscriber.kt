package com.sidekick.watch.data

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresPermission
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

sealed interface TranscriptionEvent {
    data object Connected : TranscriptionEvent
    data object SpeechStarted : TranscriptionEvent
    data object SpeechEnded : TranscriptionEvent
    data class Level(val rms: Float) : TranscriptionEvent
    data class Partial(val text: String) : TranscriptionEvent
    data class Final(val text: String) : TranscriptionEvent
    data class Error(val message: String) : TranscriptionEvent
}

class SarvamRealtimeTranscriber(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private var webSocket: WebSocket? = null
    private var recordJob: Job? = null
    private val stopping = AtomicBoolean(false)
    private val flushRequested = AtomicBoolean(false)
    private var eventSink: ((TranscriptionEvent) -> Unit)? = null
    private var accumulatedTranscript = ""

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(settings: AgentSettings, onEvent: (TranscriptionEvent) -> Unit) {
        stop()
        stopping.set(false)
        flushRequested.set(false)
        accumulatedTranscript = ""
        eventSink = onEvent

        val token = settings.sttAuthToken.trim()
        if (token.isBlank()) {
            onEvent(TranscriptionEvent.Error("Set Sarvam token"))
            return
        }

        val request = Request.Builder()
            .url(buildUrl(settings))
            .addHeader("Api-Subscription-Key", token)
            .build()

        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    onEvent(TranscriptionEvent.Connected)
                    recordJob = scope.launch(Dispatchers.IO) {
                        streamMicrophone(webSocket, onEvent)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text, onEvent)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleMessage(bytes.utf8(), onEvent)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val responseMessage = response?.let { "HTTP ${it.code}: ${it.message}" }
                    onEvent(TranscriptionEvent.Error(responseMessage ?: t.message ?: "Transcription failed"))
                    stop()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    recordJob?.cancel()
                    if (code != 1000 && reason.isNotBlank()) {
                        onEvent(TranscriptionEvent.Error(reason))
                    }
                }
            },
        )
    }

    fun stop(flush: Boolean = false) {
        val alreadyStopping = stopping.getAndSet(true)
        if (alreadyStopping && !flush) return
        if (flush && flushRequested.get()) return
        recordJob?.cancel()
        recordJob = null
        if (flush) {
            flushRequested.set(true)
            webSocket?.send(JSONObject().put("type", "flush").toString())
            scope.launch {
                delay(FLUSH_TIMEOUT_MS)
                if (flushRequested.get()) {
                    val fallbackTranscript = accumulatedTranscript.trim()
                    if (fallbackTranscript.isNotEmpty()) {
                        Log.i(TAG, "Flush timed out; using latest transcript length=${fallbackTranscript.length}")
                        flushRequested.set(false)
                        eventSink?.invoke(TranscriptionEvent.Final(fallbackTranscript))
                        webSocket?.close(1000, "done")
                    } else {
                        eventSink?.invoke(TranscriptionEvent.Error("Transcription timed out"))
                        webSocket?.close(1000, "timeout")
                    }
                    webSocket = null
                }
            }
            return
        }
        webSocket?.close(1000, "done")
        webSocket = null
        eventSink = null
    }

    @SuppressLint("MissingPermission")
    private fun streamMicrophone(webSocket: WebSocket, onEvent: (TranscriptionEvent) -> Unit) {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) {
            onEvent(TranscriptionEvent.Error("Mic unavailable"))
            return
        }

        val bufferSize = maxOf(minBufferSize, SAMPLE_RATE)
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        val buffer = ByteArray(minBufferSize)
        val pendingAudio = ByteArrayOutputStream()
        var lastAudioSendMs = SystemClock.elapsedRealtime()

        try {
            audioRecord.startRecording()
            while (!stopping.get() && !Thread.currentThread().isInterrupted) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    onEvent(TranscriptionEvent.Level(calculateRms(buffer, read)))
                    pendingAudio.write(buffer, 0, read)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastAudioSendMs >= AUDIO_SEND_INTERVAL_MS) {
                        webSocket.send(buildAudioMessage(pendingAudio.toByteArray()))
                        pendingAudio.reset()
                        lastAudioSendMs = now
                    }
                }
            }
        } catch (ex: Exception) {
            onEvent(TranscriptionEvent.Error(ex.message ?: "Mic failed"))
        } finally {
            if (pendingAudio.size() > 0) {
                runCatching { webSocket.send(buildAudioMessage(pendingAudio.toByteArray())) }
            }
            runCatching { audioRecord.stop() }
            audioRecord.release()
        }
    }

    private fun handleMessage(raw: String, onEvent: (TranscriptionEvent) -> Unit) {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val type = json.optString("type")
        val data = json.optJSONObject("data")
        if (type == "error") {
            val message = data?.optString("error").orEmpty().ifBlank { "Transcription failed" }
            val code = data?.optString("code").orEmpty()
            onEvent(TranscriptionEvent.Error(if (code.isBlank()) message else "$message ($code)"))
            stop()
            return
        }
        if (type == "events") {
            when (data?.optString("signal_type")) {
                "START_SPEECH" -> onEvent(TranscriptionEvent.SpeechStarted)
                "END_SPEECH" -> onEvent(TranscriptionEvent.SpeechEnded)
            }
            return
        }

        val transcript = data?.optString("transcript").orEmpty().ifBlank {
            json.optString("transcript")
        }.trim()

        if (transcript.isBlank()) return
        val fullTranscript = mergeTranscript(transcript)
        if (flushRequested.get() && (type == "data" || type == "transcript")) {
            flushRequested.set(false)
            onEvent(TranscriptionEvent.Final(fullTranscript))
            webSocket?.close(1000, "done")
            webSocket = null
            eventSink = null
        } else {
            onEvent(TranscriptionEvent.Partial(fullTranscript))
        }
    }

    private fun mergeTranscript(transcript: String): String {
        val next = transcript.trim()
        val current = accumulatedTranscript.trim()
        accumulatedTranscript = when {
            current.isBlank() -> next
            next == current -> current
            next.startsWith(current) -> next
            current.endsWith(next) -> current
            else -> {
                val overlap = longestOverlap(current, next)
                if (overlap > 0) {
                    current + next.drop(overlap)
                } else {
                    "$current $next"
                }
            }
        }
        return accumulatedTranscript
    }

    private fun longestOverlap(prefix: String, suffix: String): Int {
        val max = minOf(prefix.length, suffix.length)
        for (length in max downTo 1) {
            if (prefix.endsWith(suffix.take(length))) return length
        }
        return 0
    }

    private fun buildAudioMessage(audioBytes: ByteArray): String {
        val audio = JSONObject()
            .put("data", Base64.encodeToString(audioBytes, Base64.NO_WRAP))
            .put("sample_rate", SAMPLE_RATE.toString())
            .put("encoding", "audio/wav")
        return JSONObject().put("audio", audio).toString()
    }

    private fun calculateRms(buffer: ByteArray, read: Int): Float {
        var sum = 0.0
        var samples = 0
        var index = 0
        while (index + 1 < read) {
            val sample = ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 0xff)).toShort()
            sum += sample * sample
            samples += 1
            index += 2
        }
        if (samples == 0) return 0f
        return (sqrt(sum / samples) / Short.MAX_VALUE * 12f).toFloat()
    }

    private fun buildUrl(settings: AgentSettings): String {
        val base = normalizeBaseUrl(settings.sttBaseUrl).ifBlank { VoiceInputProviders.SARVAM_BASE_URL }
        return "$base/speech-to-text/ws" +
            "?language-code=${url(settings.sttLanguageCode.ifBlank { "unknown" })}" +
            "&model=${url(settings.sttModel.ifBlank { "saaras:v3" })}" +
            "&mode=${url(settings.sttMode.ifBlank { "transcribe" })}" +
            "&sample_rate=$SAMPLE_RATE" +
            "&high_vad_sensitivity=true" +
            "&vad_signals=true" +
            "&flush_signal=true" +
            "&input_audio_codec=pcm_s16le"
    }

    private fun url(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        private const val TAG = "SarvamStt"
        const val SAMPLE_RATE = 16000
        private const val FLUSH_TIMEOUT_MS = 8_000L
        private const val AUDIO_SEND_INTERVAL_MS = 100L
    }
}
