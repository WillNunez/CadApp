package com.cadapp.voicetocad.data

import com.cadapp.voicetocad.domain.AppSettings
import com.cadapp.voicetocad.domain.PrinterTelemetryState
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class PrinterControlAction(val label: String) {
  START_PRINT("Start Print Job"),
  PAUSE_RESUME("Pause / Resume Print"),
  CANCEL_PRINT("Stop / Cancel Print"),
  HOME_XYZ("Home X/Y/Z Axes"),
  PREHEAT_PLA("Preheat PLA (205°C / 60°C)"),
  PREHEAT_PETG("Preheat PETG (235°C / 75°C)"),
  COOLDOWN("Cooldown Extruder & Bed (0°C)"),
  RESTART_OCTOPRINT("Restart OctoPrint Service (SSH)"),
}

data class SshTestResult(
  val summary: String,
  val rawOutput: String,
  val discoveredApiKey: String?,
)

/**
 * Provides direct SSH / SFTP access to OctoPi (Raspberry Pi) via JSch
 * as well as full 3D printer control (Start, Pause, Stop/Cancel, Home, Preheat, Telemetry)
 * via OctoPrint REST API with automatic SSH localhost fallback.
 */
object OctoPiSshAndPrinterService {

  private fun openSshSession(settings: AppSettings): Session {
    val host = settings.octoPiSshHost.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
    if (host.isBlank()) throw IllegalArgumentException("OctoPi SSH host is empty")
    val jsch = JSch()
    val session = jsch.getSession(settings.octoPiSshUser.trim().ifEmpty { "pi" }, host, settings.octoPiSshPort)
    session.setPassword(settings.octoPiSshPassword)
    val config = Properties().apply {
      put("StrictHostKeyChecking", "no")
      put("PreferredAuthentications", "password,keyboard-interactive,publickey")
    }
    session.setConfig(config)
    session.connect(7000)
    return session
  }

  suspend fun runSshCommand(
    settings: AppSettings,
    command: String,
  ): Result<String> =
    withContext(Dispatchers.IO) {
      runCatching {
        val session = openSshSession(settings)
        try {
          execOnSession(session, command)
        } finally {
          session.disconnect()
        }
      }
    }

  private fun execOnSession(session: Session, command: String): String {
    val channel = (session.openChannel("exec") as ChannelExec).apply {
      setCommand(command)
      setInputStream(null)
    }
    val outBuf = ByteArrayOutputStream()
    val errBuf = ByteArrayOutputStream()
    channel.outputStream = outBuf
    channel.setErrStream(errBuf)
    channel.connect(6000)

    val start = System.currentTimeMillis()
    while (!channel.isClosed && (System.currentTimeMillis() - start) < 10000) {
      Thread.sleep(60)
    }
    channel.disconnect()
    val outStr = outBuf.toString(Charsets.UTF_8.name()).trim()
    val errStr = errBuf.toString(Charsets.UTF_8.name()).trim()
    return when {
      outStr.isNotEmpty() && errStr.isNotEmpty() -> "$outStr\n$errStr"
      outStr.isNotEmpty() -> outStr
      errStr.isNotEmpty() -> errStr
      else -> "(Command exited with code ${channel.exitStatus})"
    }
  }

  /**
   * Connects via SSH to OctoPi, checks CPU temp / uptime / OctoPrint status,
   * and automatically reads the OctoPrint REST API key from ~/.octoprint/config.yaml if present.
   */
  suspend fun testSshAndDiscoverApiKey(settings: AppSettings): Result<SshTestResult> =
    withContext(Dispatchers.IO) {
      runCatching {
        val session = openSshSession(settings)
        try {
          val cmd =
            """
            echo "HOST_IP:$(hostname -I 2>/dev/null | awk '{print $1}')"
            echo "UPTIME:$(uptime -p 2>/dev/null)"
            echo "TEMP:$(vcgencmd measure_temp 2>/dev/null || echo 'N/A')"
            echo "SERVICE:$(systemctl is-active octoprint 2>/dev/null || echo 'unknown')"
            echo "API_KEY:$(grep -A3 '^api:' ~/.octoprint/config.yaml 2>/dev/null | grep 'key:' | head -n1 | awk '{print $2}' | tr -d '\"'\''')"
            """.trimIndent()
          val raw = execOnSession(session, cmd)
          val lines = raw.lines()
          fun findVal(prefix: String): String =
            lines.firstOrNull { it.startsWith(prefix) }?.substringAfter(prefix)?.trim().orEmpty()

          val ip = findVal("HOST_IP:").ifEmpty { settings.octoPiSshHost }
          val temp = findVal("TEMP:").replace("temp=", "")
          val service = findVal("SERVICE:")
          val apiKey = findVal("API_KEY:").takeIf { it.length >= 16 }

          val summary =
            buildString {
              append("Connected to ${settings.octoPiSshUser}@$ip")
              if (temp.isNotEmpty() && temp != "N/A") append(" • Pi Temp: $temp")
              if (service.isNotEmpty()) append(" • octoprint: $service")
              if (apiKey != null) append(" • API Key auto-discovered!")
            }
          SshTestResult(
            summary = summary,
            rawOutput = raw,
            discoveredApiKey = apiKey,
          )
        } finally {
          session.disconnect()
        }
      }
    }

  /**
   * Uploads binary STL or STEP file directly to OctoPi via SFTP (e.g. /home/pi/.octoprint/watched).
   */
  suspend fun uploadFileViaSftp(
    bytes: ByteArray,
    remoteFileName: String,
    settings: AppSettings,
  ): Result<String> =
    withContext(Dispatchers.IO) {
      runCatching {
        val session = openSshSession(settings)
        try {
          val remoteDir = settings.octoPiRemoteDir.trim().ifEmpty { "/home/pi/.octoprint/watched" }.trimEnd('/')
          execOnSession(session, "mkdir -p \"$remoteDir\"")
          val sftp = (session.openChannel("sftp") as ChannelSftp).apply {
            connect(6000)
          }
          try {
            val fullPath = "$remoteDir/$remoteFileName"
            sftp.put(ByteArrayInputStream(bytes), fullPath, ChannelSftp.OVERWRITE)
            "Uploaded $remoteFileName (${bytes.size / 1024} KB) via SFTP to $fullPath"
          } finally {
            sftp.disconnect()
          }
        } finally {
          session.disconnect()
        }
      }
    }

  /**
   * Executes a printer control action (Start, Pause/Resume, Cancel/Stop, Home XYZ, Preheat, Cooldown).
   * Tries OctoPrint HTTP API first; if no API key is configured or HTTP fails, executes via SSH on OctoPi.
   */
  suspend fun executePrinterAction(
    action: PrinterControlAction,
    settings: AppSettings,
  ): Result<String> =
    withContext(Dispatchers.IO) {
      runCatching {
        if (action == PrinterControlAction.RESTART_OCTOPRINT) {
          val out = runSshCommand(settings, "sudo -n systemctl restart octoprint || systemctl restart octoprint").getOrThrow()
          return@runCatching "Restarted OctoPrint service over SSH ($out)"
        }

        val (endpointPath, jsonPayload) =
          when (action) {
            PrinterControlAction.START_PRINT ->
              Pair("/api/job", JSONObject().put("command", "start"))
            PrinterControlAction.PAUSE_RESUME ->
              Pair("/api/job", JSONObject().put("command", "pause").put("action", "toggle"))
            PrinterControlAction.CANCEL_PRINT ->
              Pair("/api/job", JSONObject().put("command", "cancel"))
            PrinterControlAction.HOME_XYZ ->
              Pair(
                "/api/printer/printhead",
                JSONObject().put("command", "home").put("axes", JSONArray().put("x").put("y").put("z")),
              )
            PrinterControlAction.PREHEAT_PLA -> {
              sendEndpointCommand("/api/printer/tool", JSONObject().put("command", "target").put("targets", JSONObject().put("tool0", 205)), settings)
              Pair("/api/printer/bed", JSONObject().put("command", "target").put("target", 60))
            }
            PrinterControlAction.PREHEAT_PETG -> {
              sendEndpointCommand("/api/printer/tool", JSONObject().put("command", "target").put("targets", JSONObject().put("tool0", 235)), settings)
              Pair("/api/printer/bed", JSONObject().put("command", "target").put("target", 75))
            }
            PrinterControlAction.COOLDOWN -> {
              sendEndpointCommand("/api/printer/tool", JSONObject().put("command", "target").put("targets", JSONObject().put("tool0", 0)), settings)
              Pair("/api/printer/bed", JSONObject().put("command", "target").put("target", 0))
            }
            PrinterControlAction.RESTART_OCTOPRINT -> Pair("/api/job", JSONObject())
          }

        sendEndpointCommand(endpointPath, jsonPayload, settings)
        "Printer command sent: ${action.label}"
      }
    }

  private fun sendEndpointCommand(
    endpointPath: String,
    payload: JSONObject,
    settings: AppSettings,
  ) {
    // 1. Try direct HTTP REST if API Key is present
    if (settings.octoPrintApiKey.isNotBlank()) {
      val baseUrl = settings.octoPrintUrl.trim().trimEnd('/')
      val conn = (URL("$baseUrl$endpointPath").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 5000
        readTimeout = 6000
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("X-Api-Key", settings.octoPrintApiKey.trim())
      }
      conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
      val code = conn.responseCode
      if (code in 200..299) return
    }

    // 2. Fallback: Tunnel the OctoPrint API call directly over SSH on the Raspberry Pi!
    val session = openSshSession(settings)
    try {
      val escapedJson = payload.toString().replace("'", "'\\''")
      val sshCurl =
        """
        KEY="${settings.octoPrintApiKey.trim()}"
        if [ -z "${'$'}KEY" ]; then
          KEY=${'$'}(grep -A3 '^api:' ~/.octoprint/config.yaml 2>/dev/null | grep 'key:' | head -n1 | awk '{print ${'$'}2}' | tr -d '"' | tr -d "'")
        fi
        curl -sS -X POST "http://127.0.0.1:5000$endpointPath" \
          -H "Content-Type: application/json" \
          -H "X-Api-Key: ${'$'}KEY" \
          -d '$escapedJson'
        """.trimIndent()
      execOnSession(session, sshCurl)
    } finally {
      session.disconnect()
    }
  }

  /**
   * Polls live printer temperatures (Nozzle & Bed) and active Print Job progress (%)
   * via OctoPrint REST API or SSH tunnel fallback.
   */
  suspend fun pollPrinterTelemetry(
    settings: AppSettings,
    current: PrinterTelemetryState,
  ): Result<PrinterTelemetryState> =
    withContext(Dispatchers.IO) {
      runCatching {
        val (printerJsonStr, jobJsonStr) =
          if (settings.octoPrintApiKey.isNotBlank()) {
            val baseUrl = settings.octoPrintUrl.trim().trimEnd('/')
            val pStr = httpGet("$baseUrl/api/printer", settings.octoPrintApiKey.trim())
            val jStr = httpGet("$baseUrl/api/job", settings.octoPrintApiKey.trim())
            Pair(pStr, jStr)
          } else {
            val session = openSshSession(settings)
            try {
              val cmd =
                """
                KEY=${'$'}(grep -A3 '^api:' ~/.octoprint/config.yaml 2>/dev/null | grep 'key:' | head -n1 | awk '{print ${'$'}2}' | tr -d '"' | tr -d "'")
                echo "===PRINTER==="
                curl -s "http://127.0.0.1:5000/api/printer" -H "X-Api-Key: ${'$'}KEY"
                echo ""
                echo "===JOB==="
                curl -s "http://127.0.0.1:5000/api/job" -H "X-Api-Key: ${'$'}KEY"
                """.trimIndent()
              val raw = execOnSession(session, cmd)
              val pPart = raw.substringAfter("===PRINTER===", "").substringBefore("===JOB===").trim()
              val jPart = raw.substringAfter("===JOB===", "").trim()
              Pair(pPart, jPart)
            } finally {
              session.disconnect()
            }
          }

        val pObj = runCatching { JSONObject(printerJsonStr) }.getOrNull()
        val jObj = runCatching { JSONObject(jobJsonStr) }.getOrNull()

        val stateText =
          pObj?.optJSONObject("state")?.optString("text")
            ?: jObj?.optString("state")
            ?: "Connected"

        val tool0 = pObj?.optJSONObject("temperature")?.optJSONObject("tool0")
        val bed = pObj?.optJSONObject("temperature")?.optJSONObject("bed")

        val nozzleAct = tool0?.optDouble("actual", 0.0)?.toFloat() ?: current.nozzleActualC
        val nozzleTgt = tool0?.optDouble("target", 0.0)?.toFloat() ?: current.nozzleTargetC
        val bedAct = bed?.optDouble("actual", 0.0)?.toFloat() ?: current.bedActualC
        val bedTgt = bed?.optDouble("target", 0.0)?.toFloat() ?: current.bedTargetC

        val jobFile =
          jObj?.optJSONObject("job")?.optJSONObject("file")?.optString("name")
            ?.takeIf { it.isNotBlank() } ?: current.jobFileName
        val completion =
          jObj?.optJSONObject("progress")?.optDouble("completion", 0.0)?.toFloat() ?: 0f

        current.copy(
          printerStateText = stateText,
          nozzleActualC = if (nozzleAct.isNaN()) 0f else nozzleAct,
          nozzleTargetC = if (nozzleTgt.isNaN()) 0f else nozzleTgt,
          bedActualC = if (bedAct.isNaN()) 0f else bedAct,
          bedTargetC = if (bedTgt.isNaN()) 0f else bedTgt,
          jobFileName = jobFile,
          jobProgressPercent = if (completion.isNaN()) 0f else completion,
        )
      }
    }

  private fun httpGet(url: String, apiKey: String): String {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      connectTimeout = 4000
      readTimeout = 5000
      setRequestProperty("X-Api-Key", apiKey)
    }
    return conn.inputStream.bufferedReader().use { it.readText() }
  }
}
