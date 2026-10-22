package com.cadapp.voicetocad.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cadapp.voicetocad.domain.CadMesh
import com.cadapp.voicetocad.domain.FaceGroup
import com.cadapp.voicetocad.domain.RenderMode
import com.cadapp.voicetocad.domain.Vec3
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun CadViewport3D(
  mesh: CadMesh,
  renderMode: RenderMode,
  onRenderModeChange: (RenderMode) -> Unit,
  modifier: Modifier = Modifier,
) {
  var yawDeg by remember { mutableFloatStateOf(-38f) }
  var pitchDeg by remember { mutableFloatStateOf(26f) }
  var zoom by remember { mutableFloatStateOf(1.0f) }

  val bx = mesh.boundingBoxMm.x
  val by = mesh.boundingBoxMm.y
  val bz = mesh.boundingBoxMm.z
  val maxDim = max(bx, max(by, bz)).coerceAtLeast(20f)
  val diagDim = sqrt(bx * bx + by * by + bz * bz).coerceAtLeast(35f)

  Column(
    modifier =
      modifier
        .clip(RoundedCornerShape(16.dp))
        .background(
          Brush.verticalGradient(
            colors = listOf(Color(0xFF0B121C), Color(0xFF131F30), Color(0xFF0D1622))
          )
        )
        .border(1.dp, Color(0xFF24364D), RoundedCornerShape(16.dp))
  ) {
    // Non-overlapping Top Telemetry & View Mode Header Bar
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .background(Color(0xCC091019))
          .padding(horizontal = 10.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column {
        Text(
          text = "OCCT B-REP • ${bx.toInt()}×${by.toInt()}×${bz.toInt()} mm",
          color = Color(0xFF4FC3F7),
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          fontFamily = FontFamily.Monospace,
        )
        Text(
          text = "Vol: ${"%.1f".format(mesh.volumeCm3)} cm³ • ${mesh.triangles.size} tris",
          color = Color(0xFF90A4AE),
          fontSize = 10.sp,
          fontFamily = FontFamily.Monospace,
        )
      }

      Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        RenderMode.entries.forEach { mode ->
          val selected = mode == renderMode
          val shortLabel =
            when (mode) {
              RenderMode.SHADED_EDGES -> "Shaded"
              RenderMode.WIREFRAME -> "Wire"
              RenderMode.XRAY -> "X-Ray"
            }
          Text(
            text = shortLabel,
            color = if (selected) Color(0xFF081018) else Color(0xFFB0BEC5),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier =
              Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (selected) Color(0xFF29B6F6) else Color(0xFF162436))
                .clickable { onRenderModeChange(mode) }
                .padding(horizontal = 7.dp, vertical = 4.dp),
          )
        }
        Text(
          text = "ISO",
          color = Color(0xFF80DEEA),
          fontSize = 10.sp,
          fontWeight = FontWeight.Bold,
          modifier =
            Modifier
              .clip(RoundedCornerShape(6.dp))
              .background(Color(0xFF162436))
              .border(1.dp, Color(0xFF29B6F6), RoundedCornerShape(6.dp))
              .clickable {
                yawDeg = -38f
                pitchDeg = 26f
                zoom = 1.0f
              }
              .padding(horizontal = 7.dp, vertical = 4.dp),
        )
      }
    }

    // 3D Interactive Canvas Area (clipped cleanly so geometry never bleeds outside)
    Box(
      modifier =
        Modifier
          .weight(1f)
          .fillMaxWidth()
          .clipToBounds()
          .pointerInput(Unit) {
            detectTransformGestures { _, pan, gestureZoom, _ ->
              if (kotlin.math.abs(gestureZoom - 1f) > 0.01f) {
                zoom = (zoom * gestureZoom).coerceIn(0.45f, 3.0f)
              } else {
                yawDeg = (yawDeg + pan.x * 0.45f) % 360f
                pitchDeg = (pitchDeg + pan.y * 0.35f).coerceIn(-82f, 82f)
              }
            }
          }
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height * 0.52f
        // Fit the full 3D bounding diagonal inside the canvas height/width with margin
        val baseScale = (minOf(size.width, size.height) * 0.62f / diagDim) * zoom

        val yawRad = Math.toRadians(yawDeg.toDouble()).toFloat()
        val pitchRad = Math.toRadians(pitchDeg.toDouble()).toFloat()
        val cosYaw = cos(yawRad)
        val sinYaw = sin(yawRad)
        val cosPitch = cos(pitchRad)
        val sinPitch = sin(pitchRad)

        fun toCameraSpace(p: Vec3): Vec3 {
          val rx = p.x * cosYaw - p.y * sinYaw
          val ry = p.x * sinYaw + p.y * cosYaw
          val rz = p.z

          val camX = rx
          val camY = ry * sinPitch - rz * cosPitch
          val camZ = ry * cosPitch + rz * sinPitch
          return Vec3(camX, camY, camZ)
        }

        fun project(p: Vec3): Offset {
          val cam = toCameraSpace(p)
          val perspective = 1f + (cam.z / (diagDim * 4.5f)) * 0.22f
          return Offset(
            x = cx + cam.x * baseScale * perspective,
            y = cy + cam.y * baseScale * perspective,
          )
        }

        // 1. Reference XY Datum Grid at Z = -bz / 2
        val floorZ = -bz / 2f
        val gridHalf = maxDim * 0.70f
        val step = (maxDim / 4f).coerceAtLeast(10f)
        var g = -gridHalf
        while (g <= gridHalf + 0.1f) {
          val p1 = project(Vec3(g, -gridHalf, floorZ))
          val p2 = project(Vec3(g, gridHalf, floorZ))
          val p3 = project(Vec3(-gridHalf, g, floorZ))
          val p4 = project(Vec3(gridHalf, g, floorZ))
          val isZero = kotlin.math.abs(g) < 0.5f
          val gridColor = if (isZero) Color(0xFF2D4A6A) else Color(0xFF1A2B40)
          drawLine(gridColor, p1, p2, strokeWidth = if (isZero) 2f else 1f)
          drawLine(gridColor, p3, p4, strokeWidth = if (isZero) 2f else 1f)
          g += step
        }

        // 2. Soft Projected Studio Drop-Shadow on Floor Plane (for Shaded mode)
        if (renderMode == RenderMode.SHADED_EDGES) {
          val shadowScaleX = bx * 0.56f
          val shadowScaleY = by * 0.56f
          val shadowCorners =
            listOf(
              project(Vec3(-shadowScaleX, -shadowScaleY, floorZ - 0.5f)),
              project(Vec3(shadowScaleX, -shadowScaleY, floorZ - 0.5f)),
              project(Vec3(shadowScaleX, shadowScaleY, floorZ - 0.5f)),
              project(Vec3(-shadowScaleX, shadowScaleY, floorZ - 0.5f)),
            )
          val shadowPath =
            Path().apply {
              moveTo(shadowCorners[0].x, shadowCorners[0].y)
              lineTo(shadowCorners[1].x, shadowCorners[1].y)
              lineTo(shadowCorners[2].x, shadowCorners[2].y)
              lineTo(shadowCorners[3].x, shadowCorners[3].y)
              close()
            }
          drawPath(shadowPath, color = Color(0x5503070C))
        }

        // 3. Depth-sort and draw 3D mesh triangles with 3-Point Studio Blinn-Phong Shading
        // Key light from upper-left-front, fill light from right, rim/top sky bounce
        val keyLight = Vec3(-0.48f, -0.58f, 0.66f).normalized()
        val fillLight = Vec3(0.72f, 0.22f, 0.66f).normalized()
        val viewDir = Vec3(0f, 0f, 1f)
        val halfVec = (keyLight + viewDir).normalized()

        if (renderMode != RenderMode.WIREFRAME) {
          val sortedTris =
            mesh.triangles
              .map { tri ->
                val camCentroid = toCameraSpace(tri.centroid)
                val camV0 = toCameraSpace(tri.v0)
                val camV1 = toCameraSpace(tri.v1)
                val camV2 = toCameraSpace(tri.v2)
                // Compute true camera-space geometric normal and orient outward
                var camN = (camV1 - camV0).cross(camV2 - camV0).normalized()
                val rawCamN = toCameraSpace(tri.normal).normalized()
                if (camN.x * rawCamN.x + camN.y * rawCamN.y + camN.z * rawCamN.z < 0f) {
                  camN = camN * -1f
                }
                Triple(tri, camCentroid.z, camN)
              }
              // Back-to-front painter's algorithm; in Shaded mode, cull hidden back-faces of outer body
              .filter { (tri, _, camN) ->
                if (renderMode == RenderMode.XRAY) true
                else if (tri.group == FaceGroup.HOLE_BORE || tri.group == FaceGroup.SHELL_CAVITY) true
                else camN.z >= -0.18f
              }
              .sortedBy { it.second }

          val path = Path()
          for ((tri, _, camNormal) in sortedTris) {
            val s0 = project(tri.v0)
            val s1 = project(tri.v1)
            val s2 = project(tri.v2)

            // Oriented normal toward viewer for interior cavities/bores
            val n = if (camNormal.z < 0f) camNormal * -1f else camNormal

            // Signed 3-point studio lighting (gives distinct lightness to Top, Front-Left, and Front-Right faces)
            val keyDiffuse = max(0f, n.x * keyLight.x + n.y * keyLight.y + n.z * keyLight.z)
            val fillDiffuse = max(0f, n.x * fillLight.x + n.y * fillLight.y + n.z * fillLight.z)
            val topSkyBounce = (tri.normal.z * 0.5f + 0.5f).coerceIn(0f, 1f)

            // Blinn-Phong specular highlight for metallic CAD sheen on fillets & cylinders
            val nDotH = max(0f, n.x * halfVec.x + n.y * halfVec.y + n.z * halfVec.z)
            val specPower = if (tri.group == FaceGroup.FILLET_BLEND) 18f else 28f
            val specStrength = if (tri.group == FaceGroup.FILLET_BLEND) 0.42f else 0.24f
            val specular = nDotH.pow(specPower) * specStrength

            val cavityDim =
              if (tri.group == FaceGroup.HOLE_BORE || tri.group == FaceGroup.SHELL_CAVITY) 0.72f else 1.0f

            val shade =
              ((0.26f + 0.52f * keyDiffuse + 0.18f * fillDiffuse + 0.14f * topSkyBounce) * cavityDim)
                .coerceIn(0.18f, 1.05f)

            val baseRgb =
              when (tri.group) {
                FaceGroup.BASE_BODY -> Triple(92f, 152f, 214f)       // Machined anodized cobalt steel
                FaceGroup.FILLET_BLEND -> Triple(24f, 212f, 242f)    // Polished cyan fillet blend
                FaceGroup.CHAMFER_BEVEL -> Triple(255f, 188f, 44f)   // Gold/amber chamfer bevel
                FaceGroup.HOLE_BORE -> Triple(54f, 96f, 138f)        // Shadowed interior bore wall
                FaceGroup.SHELL_CAVITY -> Triple(68f, 128f, 168f)    // Machined pocket wall
              }

            val r = ((baseRgb.first * shade) / 255f + specular).coerceIn(0f, 1f)
            val g = ((baseRgb.second * shade) / 255f + specular).coerceIn(0f, 1f)
            val b = ((baseRgb.third * shade) / 255f + specular * 1.1f).coerceIn(0f, 1f)
            val fillAlpha = if (renderMode == RenderMode.XRAY) 0.42f else 1.0f

            val faceColor = Color(red = r, green = g, blue = b, alpha = fillAlpha)

            path.reset()
            path.moveTo(s0.x, s0.y)
            path.lineTo(s1.x, s1.y)
            path.lineTo(s2.x, s2.y)
            path.close()

            drawPath(path, color = faceColor)
            if (renderMode == RenderMode.SHADED_EDGES) {
              // Fuse coplanar adjacent triangles seamlessly so no dark background cracks appear
              drawPath(
                path,
                color = faceColor,
                style = Stroke(width = 1.35f, cap = StrokeCap.Round, join = StrokeJoin.Round),
              )
            } else if (renderMode == RenderMode.XRAY) {
              drawPath(path, color = Color(0x444FC3F7), style = Stroke(width = 0.8f))
            }
          }
        } else {
          // Pure Wireframe Mode
          val path = Path()
          for (tri in mesh.triangles) {
            val s0 = project(tri.v0)
            val s1 = project(tri.v1)
            val s2 = project(tri.v2)
            path.reset()
            path.moveTo(s0.x, s0.y)
            path.lineTo(s1.x, s1.y)
            path.lineTo(s2.x, s2.y)
            path.close()
            val wireColor =
              when (tri.group) {
                FaceGroup.FILLET_BLEND -> Color(0xFF00E5FF)
                FaceGroup.CHAMFER_BEVEL -> Color(0xFFFFCA28)
                FaceGroup.HOLE_BORE -> Color(0xFF80DEEA)
                else -> Color(0xFF64B5F6)
              }
            drawPath(path, color = wireColor, style = Stroke(width = 1.1f))
          }
          for ((a, b) in mesh.featureEdges) {
            drawLine(
              color = Color(0xFFE1F5FE),
              start = project(a),
              end = project(b),
              strokeWidth = 1.6f,
            )
          }
        }

        // 4. Draw 3D Orientation Triad (X/Y/Z Gizmo) in bottom-left with safe margin
        val triadOrigin = Offset(34.dp.toPx(), size.height - 28.dp.toPx())
        val axisLen = 20.dp.toPx()
        val xDir = toCameraSpace(Vec3(1f, 0f, 0f))
        val yDir = toCameraSpace(Vec3(0f, 1f, 0f))
        val zDir = toCameraSpace(Vec3(0f, 0f, 1f))
        drawLine(
          Color(0xFFFF5252),
          triadOrigin,
          Offset(triadOrigin.x + xDir.x * axisLen, triadOrigin.y + xDir.y * axisLen),
          strokeWidth = 3.5f,
        )
        drawLine(
          Color(0xFF69F0AE),
          triadOrigin,
          Offset(triadOrigin.x + yDir.x * axisLen, triadOrigin.y + yDir.y * axisLen),
          strokeWidth = 3.5f,
        )
        drawLine(
          Color(0xFF40C4FF),
          triadOrigin,
          Offset(triadOrigin.x + zDir.x * axisLen, triadOrigin.y + zDir.y * axisLen),
          strokeWidth = 3.5f,
        )
      }

      // Bottom-right Legend
      Text(
        text = "1-finger orbit • 2-finger pinch zoom",
        color = Color(0xFF78909C),
        fontSize = 10.sp,
        modifier = Modifier.align(Alignment.BottomEnd).padding(horizontal = 10.dp, vertical = 6.dp),
      )
    }
  }
}
