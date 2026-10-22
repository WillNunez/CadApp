package com.cadapp.voicetocad.data

import com.cadapp.voicetocad.domain.AiMode
import com.cadapp.voicetocad.domain.AppSettings
import com.cadapp.voicetocad.domain.BackendSource
import com.cadapp.voicetocad.domain.CadFeature
import com.cadapp.voicetocad.domain.CadIntentParser
import com.cadapp.voicetocad.domain.EdgeTarget
import com.cadapp.voicetocad.domain.FaceTarget
import com.cadapp.voicetocad.domain.ParsedCadIntent
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Handles LLM intent resolution (Gemini Cloud API & Local LAN Workstation LLM)
 * as well as fabrication exports to OctoPrint (http://octopi.local) and NX Open workstation webhooks.
 */
object CadNetworkService {

  suspend fun processVoiceIntent(
    transcript: String,
    currentFeatures: List<CadFeature>,
    settings: AppSettings,
  ): ParsedCadIntent =
    withContext(Dispatchers.IO) {
      when (settings.aiMode) {
        AiMode.LOCAL_ONLY -> CadIntentParser.parseVoiceCommand(transcript, currentFeatures)
        AiMode.GEMINI_CLOUD -> {
          if (settings.geminiApiKey.isNotBlank()) {
            runCatching { callGeminiApi(transcript, currentFeatures, settings) }
              .getOrElse { CadIntentParser.parseVoiceCommand(transcript, currentFeatures) }
          } else {
            CadIntentParser.parseVoiceCommand(transcript, currentFeatures)
          }
        }
        AiMode.LAN_WORKSTATION -> {
          runCatching { callLanWorkstationLlm(transcript, currentFeatures, settings) }
            .getOrElse { CadIntentParser.parseVoiceCommand(transcript, currentFeatures) }
        }
        AiMode.HYBRID_AUTO -> {
          if (settings.geminiApiKey.isNotBlank()) {
            runCatching { callGeminiApi(transcript, currentFeatures, settings) }
              .getOrElse { CadIntentParser.parseVoiceCommand(transcript, currentFeatures) }
          } else {
            CadIntentParser.parseVoiceCommand(transcript, currentFeatures)
          }
        }
      }
    }

  private fun buildSystemPrompt(currentFeatures: List<CadFeature>): String {
    val activeSummary = currentFeatures.joinToString("; ") { "${it.name}: ${it.summary}" }
    return """
      You are a Voice-to-CAD mechanical engineering assistant for Siemens NX Open and parametric 3D modeling.
      Current active feature tree: [$activeSummary].
      Convert the user's voice command into a single JSON object (no markdown fences) with these fields:
      {
        "operation": "BLOCK" | "CYLINDER" | "HEX" | "FILLET" | "CHAMFER" | "HOLE" | "SHELL" | "UNDO" | "RESET",
        "intentTitle": "Human readable summary of the operation",
        "widthMm": 60.0,
        "depthMm": 40.0,
        "heightMm": 30.0,
        "diameterMm": 12.0,
        "radiusMm": 5.0,
        "distanceMm": 3.0,
        "wallThicknessMm": 2.5,
        "throughAll": true,
        "edgeTarget": "TOP" | "BOTTOM" | "VERTICAL" | "ALL",
        "faceTarget": "TOP" | "BOTTOM" | "CENTER"
      }
    """.trimIndent()
  }

  private fun callGeminiApi(
    transcript: String,
    currentFeatures: List<CadFeature>,
    settings: AppSettings,
  ): ParsedCadIntent {
    val endpoint =
      "https://generativelanguage.googleapis.com/v1beta/models/${settings.geminiModel.trim()}:generateContent?key=${settings.geminiApiKey.trim()}"
    val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = 8000
      readTimeout = 10000
      doOutput = true
      setRequestProperty("Content-Type", "application/json")
    }

    val payload = JSONObject().apply {
      put(
        "contents",
        JSONArray().put(
          JSONObject().put(
            "parts",
            JSONArray().put(
              JSONObject().put(
                "text",
                "${buildSystemPrompt(currentFeatures)}\n\nUser voice command: \"$transcript\"",
              )
            ),
          )
        ),
      )
      put(
        "generationConfig",
        JSONObject().put("responseMimeType", "application/json").put("temperature", 0.1),
      )
    }

    conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
    val code = conn.responseCode
    if (code !in 200..299) {
      throw IllegalStateException("Gemini API returned HTTP $code")
    }
    val body = conn.inputStream.bufferedReader().use { it.readText() }
    val root = JSONObject(body)
    val text =
      root
        .getJSONArray("candidates")
        .getJSONObject(0)
        .getJSONObject("content")
        .getJSONArray("parts")
        .getJSONObject(0)
        .getString("text")

    return applyStructuredLlmJson(
      rawTranscript = transcript,
      jsonText = text,
      currentFeatures = currentFeatures,
      source = BackendSource.GEMINI_CLOUD,
    )
  }

  private fun callLanWorkstationLlm(
    transcript: String,
    currentFeatures: List<CadFeature>,
    settings: AppSettings,
  ): ParsedCadIntent {
    val conn = (URL(settings.lanEndpointUrl.trim()).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = 6000
      readTimeout = 10000
      doOutput = true
      setRequestProperty("Content-Type", "application/json")
    }

    val messages =
      JSONArray()
        .put(JSONObject().put("role", "system").put("content", buildSystemPrompt(currentFeatures)))
        .put(JSONObject().put("role", "user").put("content", transcript))

    val payload =
      JSONObject()
        .put("model", settings.lanModelName.trim())
        .put("messages", messages)
        .put("temperature", 0.1)

    conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
    val code = conn.responseCode
    if (code !in 200..299) {
      throw IllegalStateException("LAN LLM returned HTTP $code")
    }
    val body = conn.inputStream.bufferedReader().use { it.readText() }
    val root = JSONObject(body)
    val content =
      root
        .getJSONArray("choices")
        .getJSONObject(0)
        .getJSONObject("message")
        .getString("content")

    return applyStructuredLlmJson(
      rawTranscript = transcript,
      jsonText = content,
      currentFeatures = currentFeatures,
      source = BackendSource.LAN_WORKSTATION,
    )
  }

  private fun applyStructuredLlmJson(
    rawTranscript: String,
    jsonText: String,
    currentFeatures: List<CadFeature>,
    source: BackendSource,
  ): ParsedCadIntent {
    val cleaned =
      jsonText
        .trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
    val obj = JSONObject(cleaned)
    val op = obj.optString("operation", "").uppercase()
    val title = obj.optString("intentTitle", "AI CAD Feature Update")
    val edgeTarget =
      runCatching { EdgeTarget.valueOf(obj.optString("edgeTarget", "TOP").uppercase()) }
        .getOrDefault(EdgeTarget.TOP)
    val faceTarget =
      runCatching { FaceTarget.valueOf(obj.optString("faceTarget", "TOP").uppercase()) }
        .getOrDefault(FaceTarget.TOP)

    val list = currentFeatures.toMutableList()
    if (list.isEmpty()) list.add(CadFeature.BlockFeature())

    when (op) {
      "RESET" -> {
        list.clear()
        list.add(CadFeature.BlockFeature())
      }
      "UNDO" -> {
        if (list.size > 1) list.removeAt(list.lastIndex)
      }
      "BLOCK" -> {
        val feat =
          CadFeature.BlockFeature(
            widthMm = obj.optDouble("widthMm", 60.0).toFloat().coerceIn(5f, 500f),
            depthMm = obj.optDouble("depthMm", 40.0).toFloat().coerceIn(5f, 500f),
            heightMm = obj.optDouble("heightMm", 30.0).toFloat().coerceIn(5f, 500f),
          )
        list[0] = feat
      }
      "CYLINDER" -> {
        val feat =
          CadFeature.CylinderFeature(
            diameterMm = obj.optDouble("diameterMm", 50.0).toFloat().coerceIn(6f, 500f),
            heightMm = obj.optDouble("heightMm", 40.0).toFloat().coerceIn(4f, 500f),
          )
        list[0] = feat
      }
      "HEX" -> {
        val feat =
          CadFeature.HexPrismFeature(
            acrossFlatsMm = obj.optDouble("widthMm", 40.0).toFloat().coerceIn(8f, 400f),
            heightMm = obj.optDouble("heightMm", 25.0).toFloat().coerceIn(4f, 400f),
          )
        list[0] = feat
      }
      "FILLET" -> {
        val r = obj.optDouble("radiusMm", 5.0).toFloat()
        list.removeAll {
          (it is CadFeature.EdgeBlendFeature && it.targetEdges == edgeTarget) ||
            (it is CadFeature.ChamferFeature && it.targetEdges == edgeTarget)
        }
        list.add(CadFeature.EdgeBlendFeature(radiusMm = r, targetEdges = edgeTarget))
      }
      "CHAMFER" -> {
        val d = obj.optDouble("distanceMm", 3.0).toFloat()
        list.removeAll {
          (it is CadFeature.ChamferFeature && it.targetEdges == edgeTarget) ||
            (it is CadFeature.EdgeBlendFeature && it.targetEdges == edgeTarget)
        }
        list.add(CadFeature.ChamferFeature(distanceMm = d, targetEdges = edgeTarget))
      }
      "HOLE" -> {
        val diam = obj.optDouble("diameterMm", 12.0).toFloat()
        val depth = obj.optDouble("heightMm", 30.0).toFloat()
        val through = obj.optBoolean("throughAll", true)
        list.removeAll { it is CadFeature.HoleFeature }
        list.add(
          CadFeature.HoleFeature(
            diameterMm = diam,
            depthMm = depth,
            throughAll = through,
            targetFace = faceTarget,
          )
        )
      }
      "SHELL" -> {
        val wall = obj.optDouble("wallThicknessMm", 2.5).toFloat()
        list.removeAll { it is CadFeature.ShellFeature }
        list.add(CadFeature.ShellFeature(wallThicknessMm = wall, openFace = faceTarget))
      }
      else -> {
        return CadIntentParser.parseVoiceCommand(rawTranscript, currentFeatures).copy(source = source)
      }
    }

    val (sanitized, warnings) = CadIntentParser.enforceAllGuardrails(list)
    return ParsedCadIntent(
      rawTranscript = rawTranscript,
      intentTitle = title,
      updatedFeatures = sanitized,
      warnings = warnings,
      source = source,
    )
  }

  /**
   * Uploads Binary STL directly to OctoPrint (e.g. http://octopi.local/api/files/local).
   */
  suspend fun uploadStlToOctoPrint(
    stlBytes: ByteArray,
    fileName: String,
    settings: AppSettings,
  ): Result<String> =
    withContext(Dispatchers.IO) {
      runCatching {
        val baseUrl = settings.octoPrintUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) throw IllegalArgumentException("OctoPrint URL is empty")
        val endpoint = "$baseUrl/api/files/local"
        val boundary = "----VoiceToCadOctoPrintBoundary${System.currentTimeMillis()}"

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
          requestMethod = "POST"
          connectTimeout = 6000
          readTimeout = 10000
          doOutput = true
          setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
          if (settings.octoPrintApiKey.isNotBlank()) {
            setRequestProperty("X-Api-Key", settings.octoPrintApiKey.trim())
          }
        }

        DataOutputStream(conn.outputStream).use { out ->
          // File part
          out.writeBytes("--$boundary\r\n")
          out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n")
          out.writeBytes("Content-Type: application/sla\r\n\r\n")
          out.write(stlBytes)
          out.writeBytes("\r\n")

          if (settings.autoStartPrintOnUpload) {
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"select\"\r\n\r\ntrue\r\n")
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"print\"\r\n\r\ntrue\r\n")
          }
          out.writeBytes("--$boundary--\r\n")
          out.flush()
        }

        val code = conn.responseCode
        if (code in 200..299) {
          "Uploaded $fileName (${stlBytes.size / 1024} KB) to OctoPrint ($baseUrl)"
        } else {
          throw IllegalStateException("OctoPrint HTTP $code from $endpoint")
        }
      }
    }

  /**
   * Sends the generated Siemens NX Open Python script to a local workstation bridge.
   */
  suspend fun dispatchNxScriptToWorkstation(
    pythonScript: String,
    settings: AppSettings,
  ): Result<String> =
    withContext(Dispatchers.IO) {
      runCatching {
        val url = settings.nxWorkstationUrl.trim()
        if (url.isBlank()) throw IllegalArgumentException("Workstation URL is empty")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
          requestMethod = "POST"
          connectTimeout = 5000
          readTimeout = 8000
          doOutput = true
          setRequestProperty("Content-Type", "application/json")
        }
        val payload = JSONObject().put("engine", "NXOpen").put("script", pythonScript)
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code in 200..299) {
          "Executed NX Open script on workstation ($url)"
        } else {
          throw IllegalStateException("Workstation returned HTTP $code")
        }
      }
    }
}
