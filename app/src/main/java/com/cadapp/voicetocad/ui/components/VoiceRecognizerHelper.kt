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
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.RecognitionListener as VoskRecognitionListener

/**
 * Multi-engine Speech-to-Text controller supporting:
 * 1. Vosk Open-Source Offline STT (Kaldi-based Apache 2.0 on-device engine + CAD vocabulary grammar)
 * 2. Open-Source Whisper (16kHz PCM WAV recorder -> faster-whisper / whisper.cpp server)
 * 3. Android System SpeechRecognizer
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
  private var voskModel: Model? = null
  private var voskService: SpeechService? = null
  private var whisperRecordJob: Job? = null
  @Volatile private var isRecordingWhisper = false

  private val voskModelDir: File
    get() = File(context.filesDir, "vosk-model-small-en-us")

  fun isVoskModelInstalled(): Boolean =
    voskModelDir.exists() && File(voskModelDir, "conf").exists()

  fun downloadVoskModel(onProgress: (String) -> Unit, onComplete: (Boolean) -> Unit) {
    scope.launch(Dispatchers.IO) {
      runCatching {
        withContext(Dispatchers.Main) {
          onProgress("Downloading open-source Vosk English model (~40 MB)...")
        }
        val modelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        val conn = (URL(modelUrl).openConnection() as HttpURLConnection).apply {
          connectTimeout = 15000
          readTimeout = 60000
        }
        val tmpZip = File(context.cacheDir, "vosk_model.zip")
        conn.inputStream.use { input ->
          FileOutputStream(tmpZip).use { output ->
            input.copyTo(output)
          }
        }
        withContext(Dispatchers.Main) {
          onProgress("Extracting Vosk offline speech model...")
        }
        if (voskModelDir.exists()) voskModelDir.deleteRecursively()
        voskModelDir.mkdirs()

        ZipInputStream(tmpZip.inputStream().buffered()).use { zis ->
          var entry = zis.nextEntry
          while (entry != null) {
            // Strip top-level folder name inside zip
            val relName = entry.name.substringAfter('/', missingDelimiterValue = "")
            if (relName.isNotEmpty()) {
              val outFile = File(voskModelDir, relName)
              if (entry.isDirectory) {
                outFile.mkdirs()
              } else {
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { zis.copyTo(it) }
              }
            }
            zis.closeEntry()
            entry = zis.nextEntry
          }
        }
        tmpZip.delete()
        voskModel = Model(voskModelDir.absolutePath)
        withContext(Dispatchers.Main) {
          onProgress("Vosk offline model ready! Tap MIC to speak offline.")
          onComplete(true)
        }
      }.onFailure { err ->
        withContext(Dispatchers.Main) {
          onErrorMessage("Vosk model download failed: ${err.localizedMessage}")
          onComplete(false)
        }
      }
    }
  }

  fun startListening(settings: AppSettings) {
    when (settings.sttEngine) {
      SttEngineMode.PIXEL_TENSOR_OFFLINE -> {
        startAndroidSpeechRecognizer(preferOffline = settings.preferOfflinePixelSpeech)
      }
      SttEngineMode.VOSK_OFFLINE -> {
        if (isVoskModelInstalled()) {
          startVoskOffline(settings.useVoskCadGrammar)
        } else {
          onErrorMessage("Vosk offline model not downloaded yet (tap 'Download Vosk Model' in AI & LAN tab); using Pixel Tensor STT")
          startAndroidSpeechRecognizer(preferOffline = settings.preferOfflinePixelSpeech)
        }
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
      voskService?.stop()
      voskService?.shutdown()
    }
    voskService = null
    runCatching {
      androidRecognizer?.stopListening()
      androidRecognizer?.destroy()
    }
    androidRecognizer = null
    onListeningStateChanged(false)
  }

  private fun startVoskOffline(useCadGrammar: Boolean) {
    stopAllImmediate()
    scope.launch(Dispatchers.IO) {
      runCatching {
        val model = voskModel ?: Model(voskModelDir.absolutePath).also { voskModel = it }
        val recognizer =
          if (useCadGrammar) {
            Recognizer(model, 16000.0f, buildCadDomainGrammarJson())
          } else {
            Recognizer(model, 16000.0f)
          }
        withContext(Dispatchers.Main) {
          val service = SpeechService(recognizer, 16000.0f)
          voskService = service
          onListeningStateChanged(true)
          service.startListening(
            object : VoskRecognitionListener {
              override fun onPartialResult(hypothesis: String?) {
                val partial = extractVoskField(hypothesis, "partial")
                if (partial.isNotEmpty()) {
                  onPartialResult(partial)
                }
              }

              override fun onResult(hypothesis: String?) {
                val text = extractVoskField(hypothesis, "text")
                if (text.isNotEmpty()) {
                  stopAllImmediate()
                  onFinalResult(text)
                }
              }

              override fun onFinalResult(hypothesis: String?) {
                val text = extractVoskField(hypothesis, "text")
                onListeningStateChanged(false)
                if (text.isNotEmpty()) {
                  onFinalResult(text)
                }
              }

              override fun onError(exception: Exception?) {
                onListeningStateChanged(false)
                onErrorMessage("Vosk STT error: ${exception?.localizedMessage ?: "unknown"}")
              }

              override fun onTimeout() {
                onListeningStateChanged(false)
              }
            }
          )
        }
      }.onFailure { err ->
        withContext(Dispatchers.Main) {
          onListeningStateChanged(false)
          onErrorMessage("Vosk init error: ${err.localizedMessage}")
        }
      }
    }
  }

  private fun extractVoskField(jsonStr: String?, key: String): String {
    if (jsonStr.isNullOrBlank()) return ""
    return runCatching { JSONObject(jsonStr).optString(key, "").trim() }.getOrDefault("")
  }

  /**
   * Builds a Kaldi/Vosk grammar list focused on CAD operations, geometric features, and dimensions.
   */
  private fun buildCadDomainGrammarJson(): String {
    val words = listOf(
      "apply", "create", "make", "add", "drill", "bore", "cut", "shell", "hollow", "extrude",
      "fillet", "blend", "edge", "edges", "chamfer", "bevel", "hole", "through", "blind",
      "block", "box", "cube", "cylinder", "shaft", "rod", "hex", "bolt", "nut", "prism",
      "top", "bottom", "vertical", "all", "center", "current", "part", "wall", "thickness",
      "diameter", "radius", "tall", "deep", "height", "width", "by", "millimeter", "millimeters", "mm",
      "undo", "reset", "clear",
      "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
      "twelve", "fourteen", "fifteen", "sixteen", "twenty", "twenty five", "thirty",
      "thirty five", "forty", "forty five", "fifty", "sixty", "eighty", "hundred", "point",
      "[unk]",
    )
    return JSONArray(words).toString()
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
            onErrorMessage("Whisper server error (${err.localizedMessage}) — check URL in AI & LAN tab")
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
    header.putInt(16) // PCM chunk size
    header.putShort(1) // AudioFormat = 1 (PCM)
    header.putShort(1) // Mono
    header.putInt(sampleRate)
    header.putInt(byteRate)
    header.putShort(2) // Block align
    header.putShort(16) // Bits per sample
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
      // Audio file field (compatible with both OpenAI /v1/audio/transcriptions & whisper.cpp /inference)
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
      onErrorMessage("Android SpeechRecognizer not available — download the Vosk Offline Model in the AI & LAN tab!")
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
              "NX Open",
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
