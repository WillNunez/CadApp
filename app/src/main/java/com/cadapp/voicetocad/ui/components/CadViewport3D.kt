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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import kotlin.math.sin

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

  val maxDim = max(mesh.boundingBoxMm.x, max(mesh.boundingBoxMm.y, mesh.boundingBoxMm.z)).coerceAtLeast(20f)

  Box(
    modifier =
      modifier
        .clip(RoundedCornerShape(16.dp))
        .background(
          Brush.verticalGradient(
            colors = listOf(Color(0xFF0B121C), Color(0xFF131F30), Color(0xFF0D1622))
          )
        )
        .border(1.dp, Color(0xFF24364D), RoundedCornerShape(16.dp))
        .pointerInput(Unit) {
          detectTransformGestures { _, pan, gestureZoom, _ ->
            if ( kotlin.math.abs(gestureZoom - 1f) > 0.01f ) {
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
      val cy = size.height * 0.54f
      val baseScale = (minOf(size.width, size.height) * 0.56f / maxDim) * zoom

      val yawRad = Math.toRadians(yawDeg.toDouble()).toFloat()
      val pitchRad = Math.toRadians(pitchDeg.toDouble()).toFloat()
      val cosYaw = cos(yawRad)
      val sinYaw = sin(yawRad)
      val cosPitch = cos(pitchRad)
      val sinPitch = sin(pitchRad)

      // Transform 3D CAD coordinates (X right, Y depth, Z up) into camera space
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
        val perspective = 1f + (cam.z / (maxDim * 4.5f)) * 0.25f
        return Offset(
          x = cx + cam.x * baseScale * perspective,
          y = cy + cam.y * baseScale * perspective,
        )
      }

      // 1. Draw reference XY Datum Grid at Z = -boundingBox.z / 2
      val floorZ = -mesh.boundingBoxMm.z / 2f
      val gridHalf = (maxDim * 0.85f)
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

      // 2. Depth-sort and draw 3D mesh triangles
      val lightDir = Vec3(0.45f, -0.55f, 0.70f).normalized()
      val fillAlpha =
        when (renderMode) {
          RenderMode.SHADED_EDGES -> 1.0f
          RenderMode.XRAY -> 0.42f
          RenderMode.WIREFRAME -> 0.0f
        }

      if (renderMode != RenderMode.WIREFRAME) {
        val sortedTris =
          mesh.triangles
            .map { tri ->
              val camCentroid = toCameraSpace(tri.centroid)
              Pair(tri, camCentroid.z)
            }
            .sortedBy { it.second }

        val path = Path()
        for ((tri, _) in sortedTris) {
          val s0 = project(tri.v0)
          val s1 = project(tri.v1)
          val s2 = project(tri.v2)

          val camNormal = toCameraSpace(tri.normal).normalized()
          val diffuse = kotlin.math.abs(
            camNormal.x * lightDir.x + camNormal.y * lightDir.y + camNormal.z * lightDir.z
          )
          val shade = (0.38f + 0.62f * diffuse).coerceIn(0.25f, 1.0f)

          val baseRgb =
            when (tri.group) {
              FaceGroup.BASE_BODY -> Triple(108f, 148f, 188f)
              FaceGroup.FILLET_BLEND -> Triple(38f, 206f, 228f)
              FaceGroup.CHAMFER_BEVEL -> Triple(255f, 184f, 48f)
              FaceGroup.HOLE_BORE -> Triple(74f, 118f, 152f)
              FaceGroup.SHELL_CAVITY -> Triple(88f, 168f, 198f)
            }

          val faceColor =
            Color(
              red = ((baseRgb.first * shade) / 255f).coerceIn(0f, 1f),
              green = ((baseRgb.second * shade) / 255f).coerceIn(0f, 1f),
              blue = ((baseRgb.third * shade) / 255f).coerceIn(0f, 1f),
              alpha = fillAlpha,
            )

          path.reset()
          path.moveTo(s0.x, s0.y)
          path.lineTo(s1.x, s1.y)
          path.lineTo(s2.x, s2.y)
          path.close()

          drawPath(path, color = faceColor)
          if (renderMode == RenderMode.XRAY) {
            drawPath(path, color = Color(0x444FC3F7), style = Stroke(width = 0.8f))
          }
        }
      } else {
        // Full wireframe mode
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
          drawPath(path, color = wireColor, style = Stroke(width = 1.2f))
        }
      }

      // 3. Draw sharp CAD topology feature edges
      val edgeColor =
        if (renderMode == RenderMode.WIREFRAME) Color(0xFFE1F5FE) else Color(0xCCBCE4FA)
      for ((a, b) in mesh.featureEdges) {
        drawLine(
          color = edgeColor,
          start = project(a),
          end = project(b),
          strokeWidth = 1.6f,
        )
      }

      // 4. Draw 3D Orientation Triad (X/Y/Z Gizmo) in bottom-left
      val triadOrigin = Offset(48.dp.toPx(), size.height - 44.dp.toPx())
      val axisLen = 26.dp.toPx()
      val xDir = toCameraSpace(Vec3(1f, 0f, 0f))
      val yDir = toCameraSpace(Vec3(0f, 1f, 0f))
      val zDir = toCameraSpace(Vec3(0f, 0f, 1f))
      drawLine(
        Color(0xFFFF5252),
        triadOrigin,
        Offset(triadOrigin.x + xDir.x * axisLen, triadOrigin.y + xDir.y * axisLen),
        strokeWidth = 4f,
      )
      drawLine(
        Color(0xFF69F0AE),
        triadOrigin,
        Offset(triadOrigin.x + yDir.x * axisLen, triadOrigin.y + yDir.y * axisLen),
        strokeWidth = 4f,
      )
      drawLine(
        Color(0xFF40C4FF),
        triadOrigin,
        Offset(triadOrigin.x + zDir.x * axisLen, triadOrigin.y + zDir.y * axisLen),
        strokeWidth = 4f,
      )
    }

    // Top-left CAD Telemetry HUD
    Column(
      modifier =
        Modifier
          .align(Alignment.TopStart)
          .padding(10.dp)
          .background(Color(0xAA0A111A), RoundedCornerShape(8.dp))
          .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
      Text(
        text = "OPENCASCADE (OCCT) B-REP • MM",
        color = Color(0xFF4FC3F7),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
      )
      Text(
        text =
          "BBox: ${mesh.boundingBoxMm.x.toInt()} × ${mesh.boundingBoxMm.y.toInt()} × ${mesh.boundingBoxMm.z.toInt()} mm",
        color = Color(0xFFD6E4F0),
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
      )
      Text(
        text = "Vol: ${"%.1f".format(mesh.volumeCm3)} cm³ • ${mesh.triangles.size} tris",
        color = Color(0xFF90A4AE),
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
      )
    }

    // Top-right Render Mode & Reset Controls
    Row(
      modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      RenderMode.entries.forEach { mode ->
        val selected = mode == renderMode
        Text(
          text = mode.label,
          color = if (selected) Color(0xFF081018) else Color(0xFFB0BEC5),
          fontSize = 11.sp,
          fontWeight = FontWeight.SemiBold,
          modifier =
            Modifier
              .clip(RoundedCornerShape(6.dp))
              .background(if (selected) Color(0xFF29B6F6) else Color(0xAA162436))
              .clickable { onRenderModeChange(mode) }
              .padding(horizontal = 8.dp, vertical = 4.dp),
        )
      }
      Text(
        text = "ISO",
        color = Color(0xFF80DEEA),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier =
          Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xAA162436))
            .border(1.dp, Color(0xFF29B6F6), RoundedCornerShape(6.dp))
            .clickable {
              yawDeg = -38f
              pitchDeg = 26f
              zoom = 1.0f
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
      )
    }

    // Bottom-right Legend
    Row(
      modifier =
        Modifier
          .align(Alignment.BottomEnd)
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.End,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "Drag to orbit • Pinch to zoom",
        color = Color(0xFF78909C),
        fontSize = 10.sp,
      )
    }
  }
}
