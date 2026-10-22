package com.cadapp.voicetocad.domain

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class Vec3(val x: Float, val y: Float, val z: Float) {
  operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
  operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
  operator fun times(s: Float) = Vec3(x * s, y * s, z * s)

  fun cross(o: Vec3) =
    Vec3(
      y * o.z - z * o.y,
      z * o.x - x * o.z,
      x * o.y - y * o.x,
    )

  fun normalized(): Vec3 {
    val len = sqrt(x * x + y * y + z * z)
    return if (len > 1e-6f) Vec3(x / len, y / len, z / len) else Vec3(0f, 0f, 1f)
  }
}

enum class FaceGroup {
  BASE_BODY,
  FILLET_BLEND,
  CHAMFER_BEVEL,
  HOLE_BORE,
  SHELL_CAVITY,
}

data class Triangle3D(
  val v0: Vec3,
  val v1: Vec3,
  val v2: Vec3,
  val normal: Vec3,
  val group: FaceGroup = FaceGroup.BASE_BODY,
) {
  val centroid: Vec3 =
    Vec3(
      (v0.x + v1.x + v2.x) / 3f,
      (v0.y + v1.y + v2.y) / 3f,
      (v0.z + v1.z + v2.z) / 3f,
    )
}

data class CadMesh(
  val triangles: List<Triangle3D>,
  val featureEdges: List<Pair<Vec3, Vec3>>,
  val boundingBoxMm: Vec3,
  val volumeCm3: Float,
)

/**
 * Generates watertight 3D triangle meshes from the parametric feature tree
 * (including fillets/blends, chamfers, axial bores, and shells) and exports STL files.
 */
object CadMeshEngine {

  fun buildMesh(features: List<CadFeature>): CadMesh {
    val base = features.firstOrNull {
      it is CadFeature.BlockFeature || it is CadFeature.CylinderFeature || it is CadFeature.HexPrismFeature
    } ?: CadFeature.BlockFeature()

    val blends = features.filterIsInstance<CadFeature.EdgeBlendFeature>()
    val chamfers = features.filterIsInstance<CadFeature.ChamferFeature>()
    val hole = features.filterIsInstance<CadFeature.HoleFeature>().lastOrNull()
    val shell = features.filterIsInstance<CadFeature.ShellFeature>().lastOrNull()

    val topBlend = blends.lastOrNull { it.targetEdges == EdgeTarget.TOP || it.targetEdges == EdgeTarget.ALL }
    val bottomBlend = blends.lastOrNull { it.targetEdges == EdgeTarget.BOTTOM || it.targetEdges == EdgeTarget.ALL }
    val vertBlend = blends.lastOrNull { it.targetEdges == EdgeTarget.VERTICAL || it.targetEdges == EdgeTarget.ALL }

    val topChamfer = chamfers.lastOrNull { it.targetEdges == EdgeTarget.TOP || it.targetEdges == EdgeTarget.ALL }
    val bottomChamfer = chamfers.lastOrNull { it.targetEdges == EdgeTarget.BOTTOM || it.targetEdges == EdgeTarget.ALL }
    val vertChamfer = chamfers.lastOrNull { it.targetEdges == EdgeTarget.VERTICAL || it.targetEdges == EdgeTarget.ALL }

    val (w, d, h) = CadIntentParser.getBaseBoundingBox(features)
    val cornerRadius = vertBlend?.radiusMm ?: vertChamfer?.distanceMm ?: 0f
    val cornerIsFillet = vertBlend != null

    // Generate 2D perimeter profile in XY plane (CCW order)
    val baseRing: List<Pair<Float, Float>> =
      when (base) {
        is CadFeature.BlockFeature ->
          buildRoundedRectProfile(
            width = base.widthMm,
            depth = base.depthMm,
            cornerRadius = cornerRadius.coerceAtMost(min(base.widthMm, base.depthMm) * 0.42f),
            smoothCorner = cornerIsFillet,
          )
        is CadFeature.CylinderFeature ->
          buildCircleProfile(
            radius = base.diameterMm / 2f,
            segments = base.segments.coerceIn(24, 64),
          )
        is CadFeature.HexPrismFeature ->
          buildHexProfile(
            acrossFlats = base.acrossFlatsMm,
            cornerRadius = cornerRadius.coerceAtMost(base.acrossFlatsMm * 0.2f),
          )
        else -> buildRoundedRectProfile(60f, 40f, 0f, false)
      }

    // Build vertical Z profile layers (from z = -h/2 to z = +h/2)
    // Supports bottom fillet/chamfer and top fillet/chamfer
    data class ZLayer(val z: Float, val insetMm: Float, val group: FaceGroup)

    val layers = mutableListOf<ZLayer>()
    val halfH = h / 2f
    val maxInset = min(w, d) * 0.42f

    // Bottom transition
    when {
      bottomBlend != null -> {
        val r = bottomBlend.radiusMm.coerceAtMost(min(halfH * 0.85f, maxInset))
        val steps = 8
        for (i in 0..steps) {
          val theta = (PI / 2.0) * (1.0 - i.toDouble() / steps)
          val z = -halfH + r - (r * sin(theta)).toFloat()
          val inset = r - (r * cos(theta)).toFloat()
          layers.add(ZLayer(z, inset, if (i < steps) FaceGroup.FILLET_BLEND else FaceGroup.BASE_BODY))
        }
      }
      bottomChamfer != null -> {
        val c = bottomChamfer.distanceMm.coerceAtMost(min(halfH * 0.85f, maxInset))
        layers.add(ZLayer(-halfH, c, FaceGroup.CHAMFER_BEVEL))
        layers.add(ZLayer(-halfH + c, 0f, FaceGroup.BASE_BODY))
      }
      else -> {
        layers.add(ZLayer(-halfH, 0f, FaceGroup.BASE_BODY))
      }
    }

    // Top transition
    when {
      topBlend != null -> {
        val r = topBlend.radiusMm.coerceAtMost(min(halfH * 0.85f, maxInset))
        val steps = 8
        for (i in 0..steps) {
          val theta = (PI / 2.0) * (i.toDouble() / steps)
          val z = halfH - r + (r * sin(theta)).toFloat()
          val inset = r - (r * cos(theta)).toFloat()
          layers.add(ZLayer(z, inset, if (i == 0) FaceGroup.BASE_BODY else FaceGroup.FILLET_BLEND))
        }
      }
      topChamfer != null -> {
        val c = topChamfer.distanceMm.coerceAtMost(min(halfH * 0.85f, maxInset))
        layers.add(ZLayer(halfH - c, 0f, FaceGroup.BASE_BODY))
        layers.add(ZLayer(halfH, c, FaceGroup.CHAMFER_BEVEL))
      }
      else -> {
        layers.add(ZLayer(halfH, 0f, FaceGroup.BASE_BODY))
      }
    }

    val triangles = mutableListOf<Triangle3D>()
    val featureEdges = mutableListOf<Pair<Vec3, Vec3>>()

    fun scaleRingWithInset(ring: List<Pair<Float, Float>>, inset: Float, z: Float): List<Vec3> {
      val sx = max(0.1f, (w - 2f * inset) / w)
      val sy = max(0.1f, (d - 2f * inset) / d)
      return ring.map { (x, y) -> Vec3(x * sx, y * sy, z) }
    }

    val layerRings = layers.map { scaleRingWithInset(baseRing, it.insetMm, it.z) }

    // Connect outer wall strips between consecutive Z layers
    for (li in 0 until layerRings.size - 1) {
      val lowerRing = layerRings[li]
      val upperRing = layerRings[li + 1]
      val group =
        when {
          layers[li].group == FaceGroup.FILLET_BLEND || layers[li + 1].group == FaceGroup.FILLET_BLEND ->
            FaceGroup.FILLET_BLEND
          layers[li].group == FaceGroup.CHAMFER_BEVEL || layers[li + 1].group == FaceGroup.CHAMFER_BEVEL ->
            FaceGroup.CHAMFER_BEVEL
          else -> FaceGroup.BASE_BODY
        }

      val n = lowerRing.size
      for (i in 0 until n) {
        val next = (i + 1) % n
        val p00 = lowerRing[i]
        val p10 = lowerRing[next]
        val p11 = upperRing[next]
        val p01 = upperRing[i]
        addQuad(triangles, p00, p10, p11, p01, group)
      }
    }

    // Add feature highlight edges at top, bottom, and transition boundaries
    layerRings.forEachIndexed { idx, ring ->
      if (idx == 0 || idx == layerRings.lastIndex || layers[idx].insetMm == 0f) {
        for (i in ring.indices) {
          featureEdges.add(Pair(ring[i], ring[(i + 1) % ring.size]))
        }
      }
    }

    // Vertical corner feature lines on the main body layer
    val mainLowerIdx = layers.indexOfFirst { it.insetMm == 0f }.coerceAtLeast(0)
    val mainUpperIdx = layers.indexOfLast { it.insetMm == 0f }.coerceAtLeast(0)
    if (mainLowerIdx != mainUpperIdx) {
      val rLow = layerRings[mainLowerIdx]
      val rHigh = layerRings[mainUpperIdx]
      val stride = max(1, rLow.size / 8)
      for (i in rLow.indices step stride) {
        featureEdges.add(Pair(rLow[i], rHigh[i]))
      }
    }

    // Determine inner cavity or hole
    val topOuterRing = layerRings.last()
    val bottomOuterRing = layerRings.first()

    if (hole != null) {
      val holeRadius = (hole.diameterMm / 2f).coerceAtMost(min(w, d) * 0.44f)
      val holeRing2D = buildAlignedInnerCircle(topOuterRing.size, holeRadius, topOuterRing)
      val topInnerRing = holeRing2D.map { (x, y) -> Vec3(x, y, halfH) }
      val holeBottomZ = if (hole.throughAll) -halfH else max(-halfH + 2f, halfH - hole.depthMm)
      val bottomInnerRing = holeRing2D.map { (x, y) -> Vec3(x, y, holeBottomZ) }

      // Top annular cap (outer CCW -> inner)
      val n = topOuterRing.size
      for (i in 0 until n) {
        val next = (i + 1) % n
        addQuad(
          triangles,
          topInnerRing[i],
          topOuterRing[i],
          topOuterRing[next],
          topInnerRing[next],
          FaceGroup.BASE_BODY,
        )
        // Inner cylindrical bore wall (facing inward)
        addQuad(
          triangles,
          topInnerRing[next],
          bottomInnerRing[next],
          bottomInnerRing[i],
          topInnerRing[i],
          FaceGroup.HOLE_BORE,
        )
        featureEdges.add(Pair(topInnerRing[i], topInnerRing[next]))
      }

      if (hole.throughAll) {
        // Bottom annular cap
        for (i in 0 until n) {
          val next = (i + 1) % n
          addQuad(
            triangles,
            bottomOuterRing[i],
            bottomInnerRing[i],
            bottomInnerRing[next],
            bottomOuterRing[next],
            FaceGroup.BASE_BODY,
          )
          featureEdges.add(Pair(bottomInnerRing[i], bottomInnerRing[next]))
        }
      } else {
        // Blind hole floor + solid bottom cap
        val holeCenter = Vec3(0f, 0f, holeBottomZ)
        for (i in 0 until n) {
          val next = (i + 1) % n
          addTriangle(triangles, holeCenter, bottomInnerRing[i], bottomInnerRing[next], FaceGroup.HOLE_BORE)
          featureEdges.add(Pair(bottomInnerRing[i], bottomInnerRing[next]))
        }
        val bottomCenter = Vec3(0f, 0f, -halfH)
        for (i in 0 until n) {
          val next = (i + 1) % n
          addTriangle(triangles, bottomCenter, bottomOuterRing[next], bottomOuterRing[i], FaceGroup.BASE_BODY)
        }
      }
    } else if (shell != null) {
      val wall = shell.wallThicknessMm.coerceIn(0.8f, min(w, d) * 0.35f)
      val innerFloorZ = (-halfH + wall).coerceAtMost(halfH - 1f)
      val topInnerRing = scaleRingWithInset(baseRing, wall, halfH)
      val floorInnerRing = scaleRingWithInset(baseRing, wall, innerFloorZ)

      val n = topOuterRing.size
      for (i in 0 until n) {
        val next = (i + 1) % n
        // Top rim
        addQuad(
          triangles,
          topInnerRing[i],
          topOuterRing[i],
          topOuterRing[next],
          topInnerRing[next],
          FaceGroup.BASE_BODY,
        )
        // Inner cavity wall
        addQuad(
          triangles,
          topInnerRing[next],
          floorInnerRing[next],
          floorInnerRing[i],
          topInnerRing[i],
          FaceGroup.SHELL_CAVITY,
        )
        featureEdges.add(Pair(topInnerRing[i], topInnerRing[next]))
      }
      // Inner cavity floor
      val floorCenter = Vec3(0f, 0f, innerFloorZ)
      for (i in 0 until n) {
        val next = (i + 1) % n
        addTriangle(triangles, floorCenter, floorInnerRing[i], floorInnerRing[next], FaceGroup.SHELL_CAVITY)
      }
      // Solid outer bottom cap
      val bottomCenter = Vec3(0f, 0f, -halfH)
      for (i in 0 until n) {
        val next = (i + 1) % n
        addTriangle(triangles, bottomCenter, bottomOuterRing[next], bottomOuterRing[i], FaceGroup.BASE_BODY)
      }
    } else {
      // Solid top and bottom caps
      val topCenter = Vec3(0f, 0f, halfH)
      val bottomCenter = Vec3(0f, 0f, -halfH)
      val n = topOuterRing.size
      for (i in 0 until n) {
        val next = (i + 1) % n
        addTriangle(triangles, topCenter, topOuterRing[i], topOuterRing[next], FaceGroup.BASE_BODY)
        addTriangle(triangles, bottomCenter, bottomOuterRing[next], bottomOuterRing[i], FaceGroup.BASE_BODY)
      }
    }

    // Approximate volume in cm^3 using signed tetrahedron volume formula
    var signedVolMm3 = 0.0
    for (tri in triangles) {
      val v0 = tri.v0
      val v1 = tri.v1
      val v2 = tri.v2
      signedVolMm3 +=
        (v0.x * (v1.y * v2.z - v2.y * v1.z) -
          v1.x * (v0.y * v2.z - v2.y * v0.z) +
          v2.x * (v0.y * v1.z - v1.y * v0.z)) / 6.0
    }
    val volumeCm3 = (kotlin.math.abs(signedVolMm3) / 1000.0).toFloat()

    return CadMesh(
      triangles = triangles,
      featureEdges = featureEdges,
      boundingBoxMm = Vec3(w, d, h),
      volumeCm3 = volumeCm3,
    )
  }

  private fun addTriangle(
    out: MutableList<Triangle3D>,
    v0: Vec3,
    v1: Vec3,
    v2: Vec3,
    group: FaceGroup,
  ) {
    val n = (v1 - v0).cross(v2 - v0).normalized()
    out.add(Triangle3D(v0, v1, v2, n, group))
  }

  private fun addQuad(
    out: MutableList<Triangle3D>,
    v0: Vec3,
    v1: Vec3,
    v2: Vec3,
    v3: Vec3,
    group: FaceGroup,
  ) {
    addTriangle(out, v0, v1, v2, group)
    addTriangle(out, v0, v2, v3, group)
  }

  private fun buildRoundedRectProfile(
    width: Float,
    depth: Float,
    cornerRadius: Float,
    smoothCorner: Boolean,
  ): List<Pair<Float, Float>> {
    val hw = width / 2f
    val hd = depth / 2f
    if (cornerRadius <= 0.2f) {
      // Subdivide edges so hole/cap triangulation is well-distributed
      val pts = mutableListOf<Pair<Float, Float>>()
      val corners = listOf(
        Pair(hw, hd),
        Pair(-hw, hd),
        Pair(-hw, -hd),
        Pair(hw, -hd),
      )
      val stepsPerEdge = 6
      for (c in corners.indices) {
        val start = corners[c]
        val end = corners[(c + 1) % corners.size]
        for (s in 0 until stepsPerEdge) {
          val t = s.toFloat() / stepsPerEdge
          pts.add(
            Pair(
              start.first + (end.first - start.first) * t,
              start.second + (end.second - start.second) * t,
            )
          )
        }
      }
      return pts
    }

    val r = cornerRadius.coerceAtMost(min(hw, hd) - 0.5f)
    val arcSteps = if (smoothCorner) 8 else 1
    val centers = listOf(
      Triple(hw - r, hd - r, 0.0),
      Triple(-hw + r, hd - r, PI / 2.0),
      Triple(-hw + r, -hd + r, PI),
      Triple(hw - r, -hd + r, 3.0 * PI / 2.0),
    )
    val pts = mutableListOf<Pair<Float, Float>>()
    for ((cx, cy, startAngle) in centers) {
      for (i in 0..arcSteps) {
        val a = startAngle + (PI / 2.0) * (i.toDouble() / arcSteps)
        pts.add(Pair(cx + (r * cos(a)).toFloat(), cy + (r * sin(a)).toFloat()))
      }
    }
    return pts
  }

  private fun buildCircleProfile(radius: Float, segments: Int): List<Pair<Float, Float>> {
    return (0 until segments).map { i ->
      val a = 2.0 * PI * i / segments
      Pair((radius * cos(a)).toFloat(), (radius * sin(a)).toFloat())
    }
  }

  private fun buildHexProfile(acrossFlats: Float, cornerRadius: Float): List<Pair<Float, Float>> {
    val circumRadius = (acrossFlats / sqrt(3f))
    val pts = mutableListOf<Pair<Float, Float>>()
    val corners = (0 until 6).map { i ->
      val a = PI / 6.0 + i * (PI / 3.0)
      Pair((circumRadius * cos(a)).toFloat(), (circumRadius * sin(a)).toFloat())
    }
    val stepsPerSide = 4
    for (c in corners.indices) {
      val s0 = corners[c]
      val s1 = corners[(c + 1) % 6]
      for (k in 0 until stepsPerSide) {
        val t = k.toFloat() / stepsPerSide
        val x = s0.first + (s1.first - s0.first) * t
        val y = s0.second + (s1.second - s0.second) * t
        val shrink = if ((k == 0) && cornerRadius > 0.2f) 0.92f else 1.0f
        pts.add(Pair(x * shrink, y * shrink))
      }
    }
    return pts
  }

  private fun buildAlignedInnerCircle(
    count: Int,
    radius: Float,
    outerRing: List<Vec3>,
  ): List<Pair<Float, Float>> {
    return outerRing.map { v ->
      val angle = atan2(v.y.toDouble(), v.x.toDouble())
      Pair((radius * cos(angle)).toFloat(), (radius * sin(angle)).toFloat())
    }
  }

  /**
   * Exports the generated CAD mesh to standard ASCII STL format.
   */
  fun exportAsciiStl(mesh: CadMesh, solidName: String = "VoiceToCAD_Part"): String {
    val sb = StringBuilder()
    sb.appendLine("solid $solidName")
    for (t in mesh.triangles) {
      val n = t.normal
      sb.appendLine("  facet normal ${n.x} ${n.y} ${n.z}")
      sb.appendLine("    outer loop")
      sb.appendLine("      vertex ${t.v0.x} ${t.v0.y} ${t.v0.z}")
      sb.appendLine("      vertex ${t.v1.x} ${t.v1.y} ${t.v1.z}")
      sb.appendLine("      vertex ${t.v2.x} ${t.v2.y} ${t.v2.z}")
      sb.appendLine("    endloop")
      sb.appendLine("  endfacet")
    }
    sb.appendLine("endsolid $solidName")
    return sb.toString()
  }

  /**
   * Exports the generated CAD mesh to compact Binary STL format for OctoPrint / 3D slicers.
   */
  fun exportBinaryStl(mesh: CadMesh): ByteArray {
    val triCount = mesh.triangles.size
    val buffer = ByteBuffer.allocate(84 + triCount * 50).order(ByteOrder.LITTLE_ENDIAN)
    val header = "VoiceToCAD Android Binary STL Export".toByteArray(Charsets.US_ASCII)
    val headerBytes = ByteArray(80)
    System.arraycopy(header, 0, headerBytes, 0, min(header.size, 80))
    buffer.put(headerBytes)
    buffer.putInt(triCount)

    for (t in mesh.triangles) {
      buffer.putFloat(t.normal.x)
      buffer.putFloat(t.normal.y)
      buffer.putFloat(t.normal.z)

      buffer.putFloat(t.v0.x)
      buffer.putFloat(t.v0.y)
      buffer.putFloat(t.v0.z)

      buffer.putFloat(t.v1.x)
      buffer.putFloat(t.v1.y)
      buffer.putFloat(t.v1.z)

      buffer.putFloat(t.v2.x)
      buffer.putFloat(t.v2.y)
      buffer.putFloat(t.v2.z)

      buffer.putShort(0)
    }
    return buffer.array()
  }
}
