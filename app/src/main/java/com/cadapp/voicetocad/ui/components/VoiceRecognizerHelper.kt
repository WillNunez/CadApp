package com.cadapp.voicetocad.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.cadapp.voicetocad.domain.AppSettings
import com.cadapp.voicetocad.domain.SttEngineMode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Speech-to-Text controller optimized for Pixel 9 Pro:
 * 1. Pixel 9 Pro Tensor G4 On-Device Offline Speech (EXTRA_PREFER_OFFLINE + CAD vocabulary biasing)
 * 2. Open-Source Whisper (16kHz PCM WAV recorder -> faster-whisper / whisper.cpp server)
 */
class VoiceRecognizerHelper(
  private val context: Context,
  private val scope: CoroutineScope,
  private val onListeningStateChanged: (Boolean) -> Unit,
  private val onPartialResult: (String) -> Unit,
  private val onFinalResult: (String) -> Unit,
  private val onErrorMessage: (String) -> Unit,
) {
  private var androidRecognizer: SpeechRecognizer? = null
  private var whisperRecordJob: Job? = null
  @Volatile private var isRecordingWhisper = false

  fun isVoskModelInstalled(): Boolean = true

  fun downloadVoskModel(onProgress: (String) -> Unit, onComplete: (Boolean) -> Unit) {
    onProgress("Pixel 9 Pro Tensor G4 on-device model is built-in and ready!")
    onComplete(true)
  }

  fun startListening(settings: AppSettings) {
    when (settings.sttEngine) {
      SttEngineMode.PIXEL_TENSOR_OFFLINE -> {
        startAndroidSpeechRecognizer(preferOffline = settings.preferOfflinePixelSpeech)
      }
      SttEngineMode.WHISPER_OPEN_SOURCE -> {
        startWhisperRecording(settings.whisperEndpointUrl)
      }
    }
  }

  fun stopListening(settings: AppSettings) {
    when (settings.sttEngine) {
      SttEngineMode.WHISPER_OPEN_SOURCE -> {
        if (isRecordingWhisper) {
          isRecordingWhisper = false
          return
        }
      }
      else -> {}
    }
    stopAllImmediate()
  }

  fun stopAllImmediate() {
    isRecordingWhisper = false
    whisperRecordJob?.cancel()
    whisperRecordJob = null
    runCatching {
      androidRecognizer?.stopListening()
      androidRecognizer?.destroy()
    }
    androidRecognizer = null
    onListeningStateChanged(false)
  }

  @SuppressLint("MissingPermission")
  private fun startWhisperRecording(whisperUrl: String) {
    stopAllImmediate()
    val sampleRate = 16000
    val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(4096)

    isRecordingWhisper = true
    onListeningStateChanged(true)
    onPartialResult("Recording 16kHz PCM for Whisper... tap STOP when finished speaking")

    whisperRecordJob =
      scope.launch(Dispatchers.IO) {
        val pcmOut = ByteArrayOutputStream()
        val recorder =
          AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            minBuf,
          )
        try {
          recorder.startRecording()
          val buf = ByteArray(2048)
          val startMs = System.currentTimeMillis()
          while (isActive && isRecordingWhisper && (System.currentTimeMillis() - startMs < 15000)) {
            val read = recorder.read(buf, 0, buf.size)
            if (read > 0) {
              pcmOut.write(buf, 0, read)
            }
          }
        } finally {
          runCatching {
            recorder.stop()
            recorder.release()
          }
        }

        withContext(Dispatchers.Main) {
          onListeningStateChanged(false)
          onPartialResult("Transcribing audio via open-source Whisper...")
        }

        val pcmBytes = pcmOut.toByteArray()
        if (pcmBytes.size < 1600) {
          withContext(Dispatchers.Main) {
            onErrorMessage("Audio recording too short")
          }
          return@launch
        }

        val wavBytes = wrapPcmInWavHeader(pcmBytes, sampleRate)
        runCatching {
          postWavToWhisperServer(wavBytes, whisperUrl)
        }.onSuccess { transcript ->
          withContext(Dispatchers.Main) {
            if (transcript.isNotBlank()) {
              onFinalResult(transcript)
            } else {
              onErrorMessage("Whisper returned empty transcript")
            }
          }
        }.onFailure { err ->
          withContext(Dispatchers.Main) {
            onErrorMessage("Whisper server error (${err.localizedMessage}) — check URL in AI & STT tab")
          }
        }
      }
  }

  private fun wrapPcmInWavHeader(pcmData: ByteArray, sampleRate: Int): ByteArray {
    val totalDataLen = pcmData.size + 36
    val byteRate = sampleRate * 2
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray(Charsets.US_ASCII))
    header.putInt(totalDataLen)
    header.put("WAVE".toByteArray(Charsets.US_ASCII))
    header.put("fmt ".toByteArray(Charsets.US_ASCII))
    header.putInt(16)
    header.putShort(1)
    header.putShort(1)
    header.putInt(sampleRate)
    header.putInt(byteRate)
    header.putShort(2)
    header.putShort(16)
    header.put("data".toByteArray(Charsets.US_ASCII))
    header.putInt(pcmData.size)

    val out = ByteArray(44 + pcmData.size)
    System.arraycopy(header.array(), 0, out, 0, 44)
    System.arraycopy(pcmData, 0, out, 44, pcmData.size)
    return out
  }

  private fun postWavToWhisperServer(wavBytes: ByteArray, endpointUrl: String): String {
    val boundary = "----WhisperCadBoundary${System.currentTimeMillis()}"
    val conn = (URL(endpointUrl.trim()).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = 6000
      readTimeout = 15000
      doOutput = true
      setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
    }

    DataOutputStream(conn.outputStream).use { out ->
      out.writeBytes("--$boundary\r\n")
      out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"cad_voice.wav\"\r\n")
      out.writeBytes("Content-Type: audio/wav\r\n\r\n")
      out.write(wavBytes)
      out.writeBytes("\r\n")

      out.writeBytes("--$boundary\r\n")
      out.writeBytes("Content-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-1\r\n")

      out.writeBytes("--$boundary\r\n")
      out.writeBytes(
        "Content-Disposition: form-data; name=\"prompt\"\r\n\r\n" +
          "Apply 5mm fillet to top edge, chamfer, through hole, cylinder, hex prism, shell wall\r\n"
      )
      out.writeBytes("--$boundary--\r\n")
      out.flush()
    }

    val code = conn.responseCode
    if (code !in 200..299) {
      throw IllegalStateException("HTTP $code from $endpointUrl")
    }
    val body = conn.inputStream.bufferedReader().use { it.readText() }
    return runCatching { JSONObject(body).optString("text", body).trim() }.getOrDefault(body.trim())
  }

  private fun startAndroidSpeechRecognizer(preferOffline: Boolean = true) {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
      onErrorMessage("SpeechRecognizer service not available on this device.")
      onListeningStateChanged(false)
      return
    }

    stopAllImmediate()

    val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
    androidRecognizer = speechRecognizer
    speechRecognizer.setRecognitionListener(
      object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
          onListeningStateChanged(true)
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
          onListeningStateChanged(false)
        }

        override fun onError(error: Int) {
          onListeningStateChanged(false)
          val msg =
            when (error) {
              SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
              SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
              SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized — try speaking clearly"
              else -> "Speech recognizer status ($error)"
            }
          onErrorMessage(msg)
        }

        override fun onResults(results: Bundle?) {
          onListeningStateChanged(false)
          val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
          val top = matches?.firstOrNull()?.trim()
          if (!top.isNullOrEmpty()) {
            onFinalResult(top)
          }
        }

        override fun onPartialResults(partialResults: Bundle?) {
          val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
          val top = matches?.firstOrNull()?.trim()
          if (!top.isNullOrEmpty()) {
            onPartialResult(top)
          }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
      }
    )

    val intent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
          putStringArrayListExtra(
            RecognizerIntent.EXTRA_BIASING_STRINGS,
            arrayListOf(
              "fillet",
              "edge blend",
              "chamfer",
              "through hole",
              "blind hole",
              "millimeter",
              "millimeters",
              "hex prism",
              "cylinder",
              "shell wall",
              "block width",
              "block depth",
              "block height",
            ),
          )
        }
      }

    runCatching { speechRecognizer.startListening(intent) }
      .onFailure {
        onListeningStateChanged(false)
        onErrorMessage("Could not start microphone: ${it.localizedMessage}")
      }
  }
}
