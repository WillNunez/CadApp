package com.cadapp.voicetocad.ui.main

import com.cadapp.voicetocad.domain.CadDimensionRegistry
import com.cadapp.voicetocad.domain.CadFeature
import com.cadapp.voicetocad.domain.CadIntentParser
import com.cadapp.voicetocad.domain.CadMeshEngine
import com.cadapp.voicetocad.domain.DimensionField
import com.cadapp.voicetocad.domain.EdgeTarget
import com.cadapp.voicetocad.domain.NxOpenScriptGenerator
import com.cadapp.voicetocad.domain.OpenCascadeStepKernel
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Test

class MainScreenViewModelTest {

  @Test
  fun parseVoiceCommand_appliesTopEdgeBlendFillet() {
    val base = listOf(CadFeature.BlockFeature(widthMm = 60f, depthMm = 40f, heightMm = 30f))
    val parsed =
      CadIntentParser.parseVoiceCommand(
        "Apply a 5mm fillet to the top edge of the current block",
        base,
      )
    assertEquals(2, parsed.updatedFeatures.size)
    val blend = parsed.updatedFeatures.last() as CadFeature.EdgeBlendFeature
    assertEquals(5f, blend.radiusMm)
    assertEquals(EdgeTarget.TOP, blend.targetEdges)
    assertTrue(parsed.warnings.isEmpty())
  }

  @Test
  fun parseVoiceCommand_enforcesMechanicalGuardrailOnExcessiveFillet() {
    val base = listOf(CadFeature.BlockFeature(widthMm = 60f, depthMm = 40f, heightMm = 30f))
    val parsed =
      CadIntentParser.parseVoiceCommand(
        "Apply a 25mm fillet to the top edge",
        base,
      )
    val blend = parsed.updatedFeatures.last() as CadFeature.EdgeBlendFeature
    // 42% of 30mm shortest dimension = 12.6mm max blend radius
    assertEquals(12.6f, blend.radiusMm)
    assertEquals(1, parsed.warnings.size)
  }

  @Test
  fun buildMeshAndExportStl_producesValidGeometryAndNxScript() {
    val features = listOf(
      CadFeature.BlockFeature(widthMm = 60f, depthMm = 40f, heightMm = 30f),
      CadFeature.EdgeBlendFeature(radiusMm = 5f, targetEdges = EdgeTarget.TOP),
      CadFeature.HoleFeature(diameterMm = 12f, throughAll = true),
    )
    val mesh = CadMeshEngine.buildMesh(features)
    assertTrue(mesh.triangles.isNotEmpty())
    assertTrue(mesh.volumeCm3 > 10f)

    val asciiStl = CadMeshEngine.exportAsciiStl(mesh)
    assertTrue(asciiStl.startsWith("solid VoiceToCAD_Part"))
    assertTrue(asciiStl.contains("facet normal"))

    val binaryStl = CadMeshEngine.exportBinaryStl(mesh)
    assertEquals(84 + mesh.triangles.size * 50, binaryStl.size)

    val nxScript = NxOpenScriptGenerator.generateScript(features, "Apply 5mm fillet")
    assertTrue(nxScript.contains("import NXOpen"))
    assertTrue(nxScript.contains("CreateEdgeBlendBuilder"))
    assertTrue(nxScript.contains("CreateHolePackageBuilder"))

    val occtScript = OpenCascadeStepKernel.generateOcctKernelScript(features, "Apply 5mm fillet")
    assertTrue(occtScript.contains("BRepPrimAPI_MakeBox"))
    assertTrue(occtScript.contains("BRepFilletAPI_MakeFillet"))
    assertTrue(occtScript.contains("import cadquery as cq"))

    val stepFile = OpenCascadeStepKernel.exportIso10303Step(features, mesh)
    assertTrue(stepFile.startsWith("ISO-10303-21;"))
    assertTrue(stepFile.contains("MANIFOLD_SOLID_BREP"))
    assertTrue(stepFile.contains("CLOSED_SHELL"))
    assertTrue(stepFile.trim().endsWith("END-ISO-10303-21;"))
  }

  @Test
  fun extractAndEditNamedDimensions_updatesSolidCorrectly() {
    val block = CadFeature.BlockFeature(widthMm = 60f, depthMm = 40f, heightMm = 30f)
    val fillet = CadFeature.EdgeBlendFeature(radiusMm = 5f, targetEdges = EdgeTarget.TOP)
    val hole = CadFeature.HoleFeature(diameterMm = 12f, throughAll = true)
    val features = listOf(block, fillet, hole)

    val dims = CadDimensionRegistry.extractDimensions(features)
    // 3 block dims + 1 fillet dim + 2 hole dims = 6 named parameters
    assertEquals(6, dims.size)
    assertTrue(dims.any { it.symbolName == "block_width_x" && it.displayName == "Base Block Width (X)" })
    assertTrue(dims.any { it.symbolName == "top_fillet_radius" && it.displayName == "Top Edges Fillet Radius (R)" })
    assertTrue(dims.any { it.symbolName == "hole_bore_diameter" && it.displayName == "Center Bore Diameter (Ø)" })

    // Edit block_width_x from 60mm to 85mm
    val updated = CadDimensionRegistry.updateDimension(features, block.id, DimensionField.BLOCK_WIDTH, 85f)
    val updatedBlock = updated.first() as CadFeature.BlockFeature
    assertEquals(85f, updatedBlock.widthMm)
  }
}
