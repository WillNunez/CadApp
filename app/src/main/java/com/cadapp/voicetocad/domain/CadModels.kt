package com.cadapp.voicetocad.domain

import java.util.UUID

enum class EdgeTarget(val displayName: String) {
  TOP("Top Edges"),
  BOTTOM("Bottom Edges"),
  VERTICAL("Vertical Edges"),
  ALL("All Edges"),
}

enum class FaceTarget(val displayName: String) {
  TOP("Top Face (+Z)"),
  BOTTOM("Bottom Face (-Z)"),
  FRONT("Front Face (+Y)"),
  BACK("Back Face (-Y)"),
  LEFT("Left Face (-X)"),
  RIGHT("Right Face (+X)"),
  CENTER("Center Axis"),
}

sealed interface CadFeature {
  val id: String
  val name: String
  val summary: String

  data class BlockFeature(
    override val id: String = UUID.randomUUID().toString(),
    override val name: String = "Base Block",
    val widthMm: Float = 60f,
    val depthMm: Float = 40f,
    val heightMm: Float = 30f,
  ) : CadFeature {
    override val summary: String
      get() = "${fmt(widthMm)} × ${fmt(depthMm)} × ${fmt(heightMm)} mm"
  }

  data class CylinderFeature(
    override val id: String = UUID.randomUUID().toString(),
    override val name: String = "Base Cylinder",
    val diameterMm: Float = 50f,
    val heightMm: Float = 40f,
    val segments: Int = 36,
  ) : CadFeature {
    override val summary: String
      get() = "Ø${fmt(diameterMm)} × ${fmt(heightMm)} mm"
  }

  data class HexPrismFeature(
    override val id: String = UUID.randomUUID().toString(),
    override val name: String = "Hex Prism / Bolt Stock",
    val acrossFlatsMm: Float = 40f,
    val heightMm: Float = 25f,
  ) : CadFeature {
    override val summary: String
      get() = "AF ${fmt(acrossFlatsMm)} × H ${fmt(heightMm)} mm"
  }

  data class EdgeBlendFeature(
    override val id: String = UUID.randomUUID().toString(),
    override val name: String = "Edge Blend (Fillet)",
    val radiusMm: Float = 5f,
    val targetEdges: EdgeTarget = EdgeTarget.TOP,
    val toleranceMm: Float = 0.01f,
  ) : CadFeature {
    override val summary: String
      get() = "R${fmt(radiusMm)} mm on ${targetEdges.displayName}"
  }

  data class ChamferFeature(
    override val id: String = UUID.randomUUID().toString(),
    override val name: String = "Edge Chamfer",
    val distanceMm: Float = 3f,
    val targetEdges: EdgeTarget = EdgeTarget.TOP,
  ) : CadFeature {
    override val summary: String
      get() = "${fmt(distanceMm)} mm × 45° on ${targetEdges.displayName}"
  }

  data class HoleFeature(
    override val id: String = UUID.randomUUID().toString(),
    override val name: String = "Axial Hole / Bore",
    val diameterMm: Float = 12f,
    val depthMm: Float = 30f,
    val throughAll: Boolean = true,
    val targetFace: FaceTarget = FaceTarget.TOP,
  ) : CadFeature {
    override val summary: String
      get() =
        if (throughAll) "Ø${fmt(diameterMm)} mm Through-All (${targetFace.displayName})"
        else "Ø${fmt(diameterMm)} × ${fmt(depthMm)} mm Blind (${targetFace.displayName})"
  }

  data class ShellFeature(
    override val id: String = UUID.randomUUID().toString(),
    override val name: String = "Hollow Shell",
    val wallThicknessMm: Float = 2.5f,
    val openFace: FaceTarget = FaceTarget.TOP,
  ) : CadFeature {
    override val summary: String
      get() = "${fmt(wallThicknessMm)} mm wall (${openFace.displayName} open)"
  }
}

private fun fmt(v: Float): String =
  if (v == v.toLong().toFloat()) v.toLong().toString() else "%.1f".format(v)

enum class DimensionField {
  BLOCK_WIDTH,
  BLOCK_DEPTH,
  BLOCK_HEIGHT,
  CYLINDER_DIAMETER,
  CYLINDER_HEIGHT,
  HEX_ACROSS_FLATS,
  HEX_HEIGHT,
  FILLET_RADIUS,
  CHAMFER_DISTANCE,
  HOLE_DIAMETER,
  HOLE_DEPTH,
  SHELL_THICKNESS,
}

/**
 * Represents a named, searchable, and editable parametric dimension belonging to a [CadFeature].
 */
data class CadDimensionParameter(
  val id: String,
  val featureId: String,
  val featureName: String,
  val field: DimensionField,
  val symbolName: String,
  val displayName: String,
  val valueMm: Float,
  val minMm: Float,
  val maxMm: Float,
  val stepMm: Float = 1.0f,
  val unit: String = "mm",
)

object CadDimensionRegistry {

  /**
   * Extracts all active parametric dimensions from the solid's feature tree
   * with human-readable names and CAD variable symbols.
   */
  fun extractDimensions(features: List<CadFeature>): List<CadDimensionParameter> {
    val (baseW, baseD, baseH) = CadIntentParser.getBaseBoundingBox(features)
    val shortest = kotlin.math.min(baseW, kotlin.math.min(baseD, baseH))
    val minHoriz = kotlin.math.min(baseW, baseD)
    val maxFillet = (shortest * 0.42f).coerceAtLeast(1.0f)
    val maxHole = (minHoriz - 4.0f).coerceAtLeast(2.0f)
    val maxShell = (shortest * 0.35f).coerceAtLeast(1.2f)

    val params = mutableListOf<CadDimensionParameter>()
    features.forEach { feat ->
      when (feat) {
        is CadFeature.BlockFeature -> {
          params.add(
            CadDimensionParameter(
              id = "${feat.id}:width",
              featureId = feat.id,
              featureName = feat.name,
              field = DimensionField.BLOCK_WIDTH,
              symbolName = "block_width_x",
              displayName = "Base Block Width (X)",
              valueMm = feat.widthMm,
              minMm = 10f,
              maxMm = 300f,
              stepMm = 5f,
            )
          )
          params.add(
            CadDimensionParameter(
              id = "${feat.id}:depth",
              featureId = feat.id,
              featureName = feat.name,
              field = DimensionField.BLOCK_DEPTH,
              symbolName = "block_depth_y",
              displayName = "Base Block Depth (Y)",
              valueMm = feat.depthMm,
              minMm = 10f,
              maxMm = 300f,
              stepMm = 5f,
            )
          )
          params.add(
            CadDimensionParameter(
              id = "${feat.id}:height",
              featureId = feat.id,
              featureName = feat.name,
              field = DimensionField.BLOCK_HEIGHT,
              symbolName = "block_height_z",
              displayName = "Base Block Height (Z)",
              valueMm = feat.heightMm,
              minMm = 5f,
              maxMm = 300f,
              stepMm = 5f,
            )
          )
        }
        is CadFeature.CylinderFeature -> {
          params.add(
            CadDimensionParameter(
              id = "${feat.id}:diameter",
              featureId = feat.id,
              featureName = feat.name,
              field = DimensionField.CYLINDER_DIAMETER,
              symbolName = "cylinder_diameter",
              displayName = "Cylinder Outer Diameter (Ø)",
              valueMm = feat.diameterMm,
              minMm = 8f,
              maxMm = 300f,
              stepMm = 2f,
            )
          )
          params.add(
            CadDimensionParameter(
              id = "${feat.id}:height",
              featureId = feat.id,
              featureName = feat.name,
              field = DimensionField.CYLINDER_HEIGHT,
              symbolName = "cylinder_height_z",
              displayName = "Cylinder Height (Z)",
              valueMm = feat.heightMm,
              minMm = 5f,
              maxMm = 300f,
              stepMm = 5f,
            )
          )
        }
        is CadFeature.HexPrismFeature -> {
          params.add(
            CadDimensionParameter(
              id = "${feat.id}:af",
              featureId = feat.id,
              featureName = feat.name,
              field = DimensionField.HEX_ACROSS_FLATS,
              symbolName = "hex_across_flats",
              displayName = "Hex Across Flats (AF)",
              valueMm = feat.acrossFlatsMm,
              minMm = 8f,
              maxMm = 250f,
              stepMm = 2f,
            )
          )
          params.add(
            CadDimensionParameter(
              id = "${feat.id}:height",
              featureId = feat.id,
              featureName = feat.name,
              field = DimensionField.HEX_HEIGHT,
              symbolName = "hex_height_z",
              displayName = "Hex Prism Height (Z)",
              valueMm = feat.heightMm,
              minMm = 4f,
              maxMm = 250f,
              stepMm = 2f,
            )
          )
        }
        is CadFeature.EdgeBlendFeature -> {
          val prefix = feat.targetEdges.name.lowercase()
          params.add(
            CadDimensionParameter(
              id = "${feat.id}:radius",
              featureId = feat.id,
              featureName = feat.name,
              field = DimensionField.FILLET_RADIUS,
              symbolName = "${prefix}_fillet_radius",
              displayName = "${feat.targetEdges.displayName} Fillet Radius (R)",
              valueMm = feat.radiusMm,
              minMm = 0.5f,
              maxMm = maxFillet,
              stepMm = 0.5f,
            )
          )
        }
        is CadFeature.ChamferFeature -> {
          val prefix = feat.targetEdges.name.lowercase()
          params.add(
            CadDimensionParameter(
              id = "${feat.id}:distance",
              featureId = feat.id,
              featureName = feat.name,
              field = DimensionField.CHAMFER_DISTANCE,
              symbolName = "${prefix}_chamfer_dist",
              displayName = "${feat.targetEdges.displayName} Chamfer Distance",
              valueMm = feat.distanceMm,
              minMm = 0.5f,
              maxMm = maxFillet,
              stepMm = 0.5f,
            )
          )
        }
        is CadFeature.HoleFeature -> {
          params.add(
            CadDimensionParameter(
              id = "${feat.id}:diameter",
              featureId = feat.id,
              featureName = feat.name,
              field = DimensionField.HOLE_DIAMETER,
              symbolName = "hole_bore_diameter",
              displayName = "Center Bore Diameter (Ø)",
              valueMm = feat.diameterMm,
              minMm = 1.5f,
              maxMm = maxHole,
              stepMm = 1.0f,
            )
          )
          params.add(
            CadDimensionParameter(
              id = "${feat.id}:depth",
              featureId = feat.id,
              featureName = feat.name,
              field = DimensionField.HOLE_DEPTH,
              symbolName = "hole_bore_depth",
              displayName = if (feat.throughAll) "Hole Depth (Through-All)" else "Blind Hole Depth (Z)",
              valueMm = if (feat.throughAll) baseH else feat.depthMm,
              minMm = 2.0f,
              maxMm = baseH,
              stepMm = 1.0f,
            )
          )
        }
        is CadFeature.ShellFeature -> {
          params.add(
            CadDimensionParameter(
              id = "${feat.id}:wall",
              featureId = feat.id,
              featureName = feat.name,
              field = DimensionField.SHELL_THICKNESS,
              symbolName = "shell_wall_thickness",
              displayName = "Hollow Shell Wall Thickness",
              valueMm = feat.wallThicknessMm,
              minMm = 0.8f,
              maxMm = maxShell,
              stepMm = 0.5f,
            )
          )
        }
      }
    }
    return params
  }

  /**
   * Updates a specific dimension in the feature tree and returns the updated features.
   */
  fun updateDimension(
    features: List<CadFeature>,
    featureId: String,
    field: DimensionField,
    newValueMm: Float,
  ): List<CadFeature> {
    val safeVal = newValueMm.coerceAtLeast(0.5f)
    return features.map { feat ->
      if (feat.id != featureId) {
        feat
      } else {
        when (feat) {
          is CadFeature.BlockFeature ->
            when (field) {
              DimensionField.BLOCK_WIDTH -> feat.copy(widthMm = safeVal.coerceIn(5f, 500f))
              DimensionField.BLOCK_DEPTH -> feat.copy(depthMm = safeVal.coerceIn(5f, 500f))
              DimensionField.BLOCK_HEIGHT -> feat.copy(heightMm = safeVal.coerceIn(5f, 500f))
              else -> feat
            }
          is CadFeature.CylinderFeature ->
            when (field) {
              DimensionField.CYLINDER_DIAMETER -> feat.copy(diameterMm = safeVal.coerceIn(6f, 500f))
              DimensionField.CYLINDER_HEIGHT -> feat.copy(heightMm = safeVal.coerceIn(4f, 500f))
              else -> feat
            }
          is CadFeature.HexPrismFeature ->
            when (field) {
              DimensionField.HEX_ACROSS_FLATS -> feat.copy(acrossFlatsMm = safeVal.coerceIn(8f, 400f))
              DimensionField.HEX_HEIGHT -> feat.copy(heightMm = safeVal.coerceIn(4f, 400f))
              else -> feat
            }
          is CadFeature.EdgeBlendFeature ->
            if (field == DimensionField.FILLET_RADIUS) feat.copy(radiusMm = safeVal) else feat
          is CadFeature.ChamferFeature ->
            if (field == DimensionField.CHAMFER_DISTANCE) feat.copy(distanceMm = safeVal) else feat
          is CadFeature.HoleFeature ->
            when (field) {
              DimensionField.HOLE_DIAMETER -> feat.copy(diameterMm = safeVal)
              DimensionField.HOLE_DEPTH -> {
                val (_, _, baseH) = CadIntentParser.getBaseBoundingBox(features)
                val isThrough = safeVal >= baseH - 0.1f
                feat.copy(depthMm = safeVal.coerceAtMost(baseH), throughAll = isThrough)
              }
              else -> feat
            }
          is CadFeature.ShellFeature ->
            if (field == DimensionField.SHELL_THICKNESS) feat.copy(wallThicknessMm = safeVal) else feat
        }
      }
    }
  }
}

data class GuardrailWarning(
  val ruleName: String,
  val message: String,
  val originalValueMm: Float,
  val clampedValueMm: Float,
)

enum class BackendSource(val label: String) {
  LOCAL_DETERMINISTIC("On-Device Engine"),
  GEMINI_CLOUD("Gemini Code Assist API"),
  LAN_WORKSTATION("Local Workstation LLM"),
}

data class ParsedCadIntent(
  val rawTranscript: String,
  val intentTitle: String,
  val updatedFeatures: List<CadFeature>,
  val warnings: List<GuardrailWarning> = emptyList(),
  val source: BackendSource = BackendSource.LOCAL_DETERMINISTIC,
)

data class VoiceCommandLog(
  val id: String = UUID.randomUUID().toString(),
  val timestampLabel: String,
  val transcript: String,
  val intentSummary: String,
  val source: BackendSource,
  val warnings: List<GuardrailWarning> = emptyList(),
)

enum class RenderMode(val label: String) {
  SHADED_EDGES("CAD Shaded"),
  WIREFRAME("Wireframe"),
  XRAY("X-Ray"),
}

enum class SttEngineMode(val label: String, val description: String) {
  PIXEL_TENSOR_OFFLINE(
    "Pixel 9 Pro Tensor G4 Offline Speech (CAD-Biased)",
    "Uses Tensor G4 hardware offline transducer (EXTRA_PREFER_OFFLINE + CAD vocabulary biasing)",
  ),
  WHISPER_OPEN_SOURCE(
    "Open-Source Whisper (whisper.cpp / faster-whisper)",
    "Records 16kHz PCM WAV and transcribes via open-source Whisper server with CAD prompt conditioning",
  ),
}

enum class AiMode(val label: String, val description: String) {
  LOCAL_ONLY(
    "100% On-Device (Pixel 9 Pro Standalone)",
    "Zero-latency offline CAD intent parser & mechanical guardrails — no PC or cloud required",
  ),
  HYBRID_AUTO(
    "Hybrid (Gemini Cloud + On-Device Fallback)",
    "Uses Gemini API when a key is entered, otherwise runs 100% on-device",
  ),
  GEMINI_CLOUD(
    "Gemini API",
    "Uses Google Gemini model for complex phrasing -> OpenCASCADE operations",
  ),
  LAN_WORKSTATION(
    "Optional Local LAN LLM",
    "Optional OpenAI/Ollama endpoint if you ever want to connect a local server",
  ),
}

data class PrinterTelemetryState(
  val printerStateText: String = "Idle / Ready to Poll",
  val nozzleActualC: Float = 24.0f,
  val nozzleTargetC: Float = 0.0f,
  val bedActualC: Float = 23.0f,
  val bedTargetC: Float = 0.0f,
  val jobFileName: String = "voice_to_cad_part.stl",
  val jobProgressPercent: Float = 0.0f,
  val sshConnected: Boolean = false,
  val piSystemSummary: String = "Not connected — enter OctoPi SSH info & tap Test SSH",
  val lastSshOutput: String = "",
)

data class AppSettings(
  val sttEngine: SttEngineMode = SttEngineMode.PIXEL_TENSOR_OFFLINE,
  val useVoskCadGrammar: Boolean = true,
  val preferOfflinePixelSpeech: Boolean = true,
  val whisperEndpointUrl: String = "http://192.168.1.100:8000/v1/audio/transcriptions",
  val aiMode: AiMode = AiMode.LOCAL_ONLY,
  val geminiApiKey: String = "",
  val geminiModel: String = "gemini-2.5-flash",
  val lanEndpointUrl: String = "http://192.168.1.100:11434/v1/chat/completions",
  val lanModelName: String = "qwen2.5-coder:14b",
  val nxWorkstationUrl: String = "http://192.168.1.100:8090/api/nx-execute",
  val octoPrintUrl: String = "http://octopi.local",
  val octoPrintApiKey: String = "",
  val autoStartPrintOnUpload: Boolean = false,
  val octoPiSshHost: String = "octopi.local",
  val octoPiSshPort: Int = 22,
  val octoPiSshUser: String = "pi",
  val octoPiSshPassword: String = "",
  val octoPiRemoteDir: String = "/home/pi/.octoprint/watched",
)
