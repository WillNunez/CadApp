package com.cadapp.voicetocad.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Deterministic natural language CAD intent parser & mechanical design guardrail engine.
 * Parses voice transcripts into parametric CAD operations and validates geometry limits.
 */
object CadIntentParser {

  private val numberRegex = Regex("""(\d+(?:\.\d+)?)\s*(?:mm|millimeters?|millimetres?|cm)?""", RegexOption.IGNORE_CASE)
  private val threeDimRegex =
    Regex("""(\d+(?:\.\d+)?)\s*(?:mm)?\s*(?:x|by|\*)\s*(\d+(?:\.\d+)?)\s*(?:mm)?\s*(?:x|by|\*)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
  private val twoDimRegex =
    Regex("""(\d+(?:\.\d+)?)\s*(?:mm)?\s*(?:x|by|\*)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

  fun parseVoiceCommand(
    transcript: String,
    currentFeatures: List<CadFeature>,
  ): ParsedCadIntent {
    val clean = transcript.trim()
    val lower = clean.lowercase()
    val numbers = numberRegex.findAll(lower).mapNotNull { it.groupValues[1].toFloatOrNull() }.toList()

    // Check reset / clear / undo
    if (lower.contains("reset") || lower.contains("clear") || lower.contains("start over")) {
      val defaultBlock = CadFeature.BlockFeature(widthMm = 60f, depthMm = 40f, heightMm = 30f)
      return ParsedCadIntent(
        rawTranscript = clean,
        intentTitle = "Reset Workspace to Default Block",
        updatedFeatures = listOf(defaultBlock),
      )
    }

    if (lower.contains("undo") || lower.contains("remove last") || lower.contains("delete last")) {
      val popped = if (currentFeatures.size > 1) currentFeatures.dropLast(1) else currentFeatures
      return ParsedCadIntent(
        rawTranscript = clean,
        intentTitle = "Undo Last Feature",
        updatedFeatures = popped,
      )
    }

    val updated = currentFeatures.toMutableList()
    if (updated.isEmpty()) {
      updated.add(CadFeature.BlockFeature())
    }

    val edgeTarget = parseEdgeTarget(lower)
    val faceTarget = parseFaceTarget(lower)

    // 1. Base Primitive Creation: Block / Cube / Box
    if (
      (lower.contains("block") || lower.contains("box") || lower.contains("cube") || lower.contains("rectangular")) &&
        !lower.contains("fillet") &&
        !lower.contains("blend") &&
        !lower.contains("chamfer") &&
        !lower.contains("hole") &&
        !lower.contains("shell")
    ) {
      val threeMatch = threeDimRegex.find(lower)
      val (w, d, h) =
        when {
          threeMatch != null ->
            Triple(
              threeMatch.groupValues[1].toFloat(),
              threeMatch.groupValues[2].toFloat(),
              threeMatch.groupValues[3].toFloat(),
            )
          lower.contains("cube") && numbers.isNotEmpty() ->
            Triple(numbers[0], numbers[0], numbers[0])
          numbers.size >= 3 ->
            Triple(numbers[0], numbers[1], numbers[2])
          numbers.size == 2 ->
            Triple(numbers[0], numbers[0], numbers[1])
          numbers.size == 1 ->
            Triple(numbers[0], numbers[0] * 0.75f, numbers[0] * 0.5f)
          else -> Triple(60f, 40f, 30f)
        }
      val clampedW = w.coerceIn(5f, 500f)
      val clampedD = d.coerceIn(5f, 500f)
      val clampedH = h.coerceIn(5f, 500f)
      val newBlock = CadFeature.BlockFeature(widthMm = clampedW, depthMm = clampedD, heightMm = clampedH)
      replaceOrSetBasePrimitive(updated, newBlock)
      val sanitized = enforceAllGuardrails(updated)
      return ParsedCadIntent(
        rawTranscript = clean,
        intentTitle = "Create Block (${newBlock.summary})",
        updatedFeatures = sanitized.first,
        warnings = sanitized.second,
      )
    }

    // 2. Base Primitive Creation: Cylinder / Rod / Disk / Shaft
    if (lower.contains("cylinder") || lower.contains("shaft") || lower.contains("disk") || lower.contains("disc") || lower.contains("rod")) {
      val twoMatch = twoDimRegex.find(lower)
      val (diam, height) =
        when {
          twoMatch != null -> Pair(twoMatch.groupValues[1].toFloat(), twoMatch.groupValues[2].toFloat())
          numbers.size >= 2 -> {
            val first = if (lower.contains("radius")) numbers[0] * 2f else numbers[0]
            Pair(first, numbers[1])
          }
          numbers.size == 1 -> {
            val d = if (lower.contains("radius")) numbers[0] * 2f else numbers[0]
            Pair(d, d * 0.8f)
          }
          else -> Pair(50f, 40f)
        }
      val newCyl = CadFeature.CylinderFeature(
        diameterMm = diam.coerceIn(6f, 500f),
        heightMm = height.coerceIn(4f, 500f),
      )
      replaceOrSetBasePrimitive(updated, newCyl)
      val sanitized = enforceAllGuardrails(updated)
      return ParsedCadIntent(
        rawTranscript = clean,
        intentTitle = "Create Cylinder (${newCyl.summary})",
        updatedFeatures = sanitized.first,
        warnings = sanitized.second,
      )
    }

    // 3. Base Primitive Creation: Hex Prism / Nut / Hex Stock / Bolt
    if (lower.contains("hex") || lower.contains("nut") || lower.contains("bolt") || lower.contains("polygon")) {
      val af = numbers.getOrNull(0)?.coerceIn(8f, 400f) ?: 40f
      val h = numbers.getOrNull(1)?.coerceIn(4f, 400f) ?: 25f
      val newHex = CadFeature.HexPrismFeature(acrossFlatsMm = af, heightMm = h)
      replaceOrSetBasePrimitive(updated, newHex)
      val sanitized = enforceAllGuardrails(updated)
      return ParsedCadIntent(
        rawTranscript = clean,
        intentTitle = "Create Hex Stock (${newHex.summary})",
        updatedFeatures = sanitized.first,
        warnings = sanitized.second,
      )
    }

    // 4. Edge Blend / Fillet (Core NX Open feature from voice_to_cad_export.md)
    if (lower.contains("fillet") || lower.contains("blend") || lower.contains("round")) {
      val requestedRadius = numbers.firstOrNull() ?: 5f
      updated.removeAll {
        (it is CadFeature.EdgeBlendFeature && it.targetEdges == edgeTarget) ||
          (it is CadFeature.ChamferFeature && it.targetEdges == edgeTarget)
      }
      updated.add(
        CadFeature.EdgeBlendFeature(
          radiusMm = requestedRadius,
          targetEdges = edgeTarget,
        )
      )
      val sanitized = enforceAllGuardrails(updated)
      return ParsedCadIntent(
        rawTranscript = clean,
        intentTitle = "Apply R${requestedRadius}mm Edge Blend (${edgeTarget.displayName})",
        updatedFeatures = sanitized.first,
        warnings = sanitized.second,
      )
    }

    // 5. Chamfer / Bevel
    if (lower.contains("chamfer") || lower.contains("bevel")) {
      val requestedDist = numbers.firstOrNull() ?: 3f
      updated.removeAll {
        (it is CadFeature.ChamferFeature && it.targetEdges == edgeTarget) ||
          (it is CadFeature.EdgeBlendFeature && it.targetEdges == edgeTarget)
      }
      updated.add(
        CadFeature.ChamferFeature(
          distanceMm = requestedDist,
          targetEdges = edgeTarget,
        )
      )
      val sanitized = enforceAllGuardrails(updated)
      return ParsedCadIntent(
        rawTranscript = clean,
        intentTitle = "Apply ${requestedDist}mm Chamfer (${edgeTarget.displayName})",
        updatedFeatures = sanitized.first,
        warnings = sanitized.second,
      )
    }

    // 6. Hole / Bore / Drill
    if (lower.contains("hole") || lower.contains("bore") || lower.contains("drill") || lower.contains("cutout")) {
      val rawDiam = numbers.firstOrNull() ?: 12f
      val diam = if (lower.contains("radius")) rawDiam * 2f else rawDiam
      val blind = lower.contains("blind") || lower.contains("deep") || numbers.size >= 2
      val depth = if (numbers.size >= 2) numbers[1] else getBaseBoundingBox(updated).third
      updated.removeAll { it is CadFeature.HoleFeature }
      updated.add(
        CadFeature.HoleFeature(
          diameterMm = diam,
          depthMm = depth,
          throughAll = !blind,
          targetFace = faceTarget,
        )
      )
      val sanitized = enforceAllGuardrails(updated)
      return ParsedCadIntent(
        rawTranscript = clean,
        intentTitle = "Create Ø${diam}mm Hole (${if (!blind) "Through-All" else "${depth}mm Deep"})",
        updatedFeatures = sanitized.first,
        warnings = sanitized.second,
      )
    }

    // 7. Shell / Hollow / Wall Thickness
    if (lower.contains("shell") || lower.contains("hollow") || lower.contains("wall")) {
      val thickness = numbers.firstOrNull() ?: 2.5f
      updated.removeAll { it is CadFeature.ShellFeature }
      updated.add(
        CadFeature.ShellFeature(
          wallThicknessMm = thickness,
          openFace = faceTarget,
        )
      )
      val sanitized = enforceAllGuardrails(updated)
      return ParsedCadIntent(
        rawTranscript = clean,
        intentTitle = "Shell Part (${thickness}mm Wall)",
        updatedFeatures = sanitized.first,
        warnings = sanitized.second,
      )
    }

    // 8. Scale / Resize width, depth, or height by parameter name
    if (
      (lower.contains("width") || lower.contains("depth") || lower.contains("length") ||
        lower.contains("tall") || lower.contains("height") || lower.contains("extrude") ||
        lower.contains("across flats")) && numbers.isNotEmpty()
    ) {
      val newVal = numbers.first().coerceIn(5f, 500f)
      val base = updated.firstOrNull()
      val updatedLabel: String
      when {
        lower.contains("width") && base is CadFeature.BlockFeature -> {
          updated[0] = base.copy(widthMm = newVal)
          updatedLabel = "Set Base Block Width (X) to ${newVal}mm"
        }
        (lower.contains("depth") || lower.contains("length")) && base is CadFeature.BlockFeature -> {
          updated[0] = base.copy(depthMm = newVal)
          updatedLabel = "Set Base Block Depth (Y) to ${newVal}mm"
        }
        lower.contains("across flats") && base is CadFeature.HexPrismFeature -> {
          updated[0] = base.copy(acrossFlatsMm = newVal)
          updatedLabel = "Set Hex Across Flats (AF) to ${newVal}mm"
        }
        else -> {
          when (base) {
            is CadFeature.BlockFeature -> updated[0] = base.copy(heightMm = newVal)
            is CadFeature.CylinderFeature -> updated[0] = base.copy(heightMm = newVal)
            is CadFeature.HexPrismFeature -> updated[0] = base.copy(heightMm = newVal)
            else -> {}
          }
          updatedLabel = "Set Part Height (Z) to ${newVal}mm"
        }
      }
      val sanitized = enforceAllGuardrails(updated)
      return ParsedCadIntent(
        rawTranscript = clean,
        intentTitle = updatedLabel,
        updatedFeatures = sanitized.first,
        warnings = sanitized.second,
      )
    }

    // Default fallback if user speaks a single number or general modification
    if (numbers.isNotEmpty()) {
      val r = numbers.first()
      updated.removeAll { it is CadFeature.EdgeBlendFeature && it.targetEdges == EdgeTarget.TOP }
      updated.add(CadFeature.EdgeBlendFeature(radiusMm = r, targetEdges = EdgeTarget.TOP))
      val sanitized = enforceAllGuardrails(updated)
      return ParsedCadIntent(
        rawTranscript = clean,
        intentTitle = "Apply R${r}mm Edge Blend (Top Edges)",
        updatedFeatures = sanitized.first,
        warnings = sanitized.second,
      )
    }

    return ParsedCadIntent(
      rawTranscript = clean,
      intentTitle = "Inspected Model (${updated.size} active features)",
      updatedFeatures = updated,
    )
  }

  private fun parseEdgeTarget(lower: String): EdgeTarget =
    when {
      lower.contains("all edge") || lower.contains("every edge") || lower.contains("all side") -> EdgeTarget.ALL
      lower.contains("bottom") || lower.contains("base") || lower.contains("lower") -> EdgeTarget.BOTTOM
      lower.contains("vertical") || lower.contains("corner") || lower.contains("side") -> EdgeTarget.VERTICAL
      else -> EdgeTarget.TOP
    }

  private fun parseFaceTarget(lower: String): FaceTarget =
    when {
      lower.contains("bottom") -> FaceTarget.BOTTOM
      lower.contains("front") -> FaceTarget.FRONT
      lower.contains("back") -> FaceTarget.BACK
      lower.contains("left") -> FaceTarget.LEFT
      lower.contains("right") -> FaceTarget.RIGHT
      else -> FaceTarget.TOP
    }

  private fun replaceOrSetBasePrimitive(list: MutableList<CadFeature>, newBase: CadFeature) {
    val idx = list.indexOfFirst {
      it is CadFeature.BlockFeature || it is CadFeature.CylinderFeature || it is CadFeature.HexPrismFeature
    }
    if (idx >= 0) {
      list[idx] = newBase
    } else {
      list.add(0, newBase)
    }
  }

  fun getBaseBoundingBox(features: List<CadFeature>): Triple<Float, Float, Float> {
    val base = features.firstOrNull {
      it is CadFeature.BlockFeature || it is CadFeature.CylinderFeature || it is CadFeature.HexPrismFeature
    }
    return when (base) {
      is CadFeature.BlockFeature -> Triple(base.widthMm, base.depthMm, base.heightMm)
      is CadFeature.CylinderFeature -> Triple(base.diameterMm, base.diameterMm, base.heightMm)
      is CadFeature.HexPrismFeature -> Triple(base.acrossFlatsMm, base.acrossFlatsMm, base.heightMm)
      else -> Triple(60f, 40f, 30f)
    }
  }

  /**
   * Deterministic mechanical engineering guardrails:
   * - Edge blends & chamfers cannot exceed 42% of the shortest part dimension.
   * - Holes must leave at least 2.0mm minimum wall thickness on each side.
   * - Shell walls must be >= 0.8mm and <= 35% of shortest dimension.
   */
  fun enforceAllGuardrails(
    features: List<CadFeature>,
  ): Pair<List<CadFeature>, List<GuardrailWarning>> {
    val (w, d, h) = getBaseBoundingBox(features)
    val shortestDim = min(w, min(d, h))
    val minHorizontal = min(w, d)
    val maxBlendRadius = (shortestDim * 0.42f).coerceAtLeast(1.0f)
    val maxHoleDiam = max(2.0f, minHorizontal - 4.0f)
    val maxShellWall = (shortestDim * 0.35f).coerceAtLeast(1.2f)

    val warnings = mutableListOf<GuardrailWarning>()
    val result = features.map { feat ->
      when (feat) {
        is CadFeature.EdgeBlendFeature -> {
          if (feat.radiusMm > maxBlendRadius) {
            val clamped = (maxBlendRadius * 10f).roundToInt() / 10f
            warnings.add(
              GuardrailWarning(
                ruleName = "B-Rep Fillet Tangency Limit",
                message = "Blend radius clamped to 42% of shortest edge (${shortestDim}mm) to prevent self-intersecting faces",
                originalValueMm = feat.radiusMm,
                clampedValueMm = clamped,
              )
            )
            feat.copy(radiusMm = clamped)
          } else {
            feat
          }
        }
        is CadFeature.ChamferFeature -> {
          if (feat.distanceMm > maxBlendRadius) {
            val clamped = (maxBlendRadius * 10f).roundToInt() / 10f
            warnings.add(
              GuardrailWarning(
                ruleName = "Chamfer Edge Overflow Guardrail",
                message = "Chamfer distance clamped to preserve adjacent face topology",
                originalValueMm = feat.distanceMm,
                clampedValueMm = clamped,
              )
            )
            feat.copy(distanceMm = clamped)
          } else {
            feat
          }
        }
        is CadFeature.HoleFeature -> {
          var updatedFeat = feat
          if (feat.diameterMm > maxHoleDiam) {
            val clamped = (maxHoleDiam * 10f).roundToInt() / 10f
            warnings.add(
              GuardrailWarning(
                ruleName = "Minimum Wall Thickness (2.0mm)",
                message = "Hole diameter clamped so minimum wall thickness >= 2.0mm on ${minHorizontal}mm stock",
                originalValueMm = feat.diameterMm,
                clampedValueMm = clamped,
              )
            )
            updatedFeat = updatedFeat.copy(diameterMm = clamped)
          }
          if (!updatedFeat.throughAll && updatedFeat.depthMm > h) {
            warnings.add(
              GuardrailWarning(
                ruleName = "Blind Bore Depth Limit",
                message = "Blind hole depth exceeded stock height (${h}mm); converted to Through-All",
                originalValueMm = updatedFeat.depthMm,
                clampedValueMm = h,
              )
            )
            updatedFeat = updatedFeat.copy(depthMm = h, throughAll = true)
          }
          updatedFeat
        }
        is CadFeature.ShellFeature -> {
          if (feat.wallThicknessMm > maxShellWall) {
            val clamped = (maxShellWall * 10f).roundToInt() / 10f
            warnings.add(
              GuardrailWarning(
                ruleName = "Interior Cavity Clearance",
                message = "Shell thickness clamped to preserve interior hollow cavity",
                originalValueMm = feat.wallThicknessMm,
                clampedValueMm = clamped,
              )
            )
            feat.copy(wallThicknessMm = clamped)
          } else if (feat.wallThicknessMm < 0.8f) {
            warnings.add(
              GuardrailWarning(
                ruleName = "FDM/CNC Minimum Wall Rule",
                message = "Wall thickness raised to 0.8mm minimum manufacturable threshold",
                originalValueMm = feat.wallThicknessMm,
                clampedValueMm = 0.8f,
              )
            )
            feat.copy(wallThicknessMm = 0.8f)
          } else {
            feat
          }
        }
        else -> feat
      }
    }

    return Pair(result, warnings)
  }
}
