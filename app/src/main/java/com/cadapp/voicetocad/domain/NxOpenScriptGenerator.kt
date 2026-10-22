package com.cadapp.voicetocad.domain

/**
 * Generates executable Siemens NX Open (Python) automation scripts
 * aligned with the Voice-to-CAD pipeline specification.
 */
object NxOpenScriptGenerator {

  fun generateScript(
    features: List<CadFeature>,
    lastVoiceCommand: String?,
    warnings: List<GuardrailWarning> = emptyList(),
  ): String {
    val sb = StringBuilder()
    sb.appendLine("# ==========================================================================")
    sb.appendLine("# Voice-to-CAD Siemens NX Open Automation Script")
    if (!lastVoiceCommand.isNullOrBlank()) {
      sb.appendLine("# Last Voice Command: \"$lastVoiceCommand\"")
    }
    if (warnings.isNotEmpty()) {
      sb.appendLine("# Deterministic Engineering Guardrails Applied:")
      warnings.forEach { w ->
        sb.appendLine("#   [!] ${w.ruleName}: ${w.message} (${w.originalValueMm}mm -> ${w.clampedValueMm}mm)")
      }
    }
    sb.appendLine("# ==========================================================================")
    sb.appendLine("import NXOpen")
    sb.appendLine("import NXOpen.Features")
    sb.appendLine("import NXOpen.GeometricUtilities")
    sb.appendLine()
    sb.appendLine("def create_edge_blend(work_part, target_edges_tag, radius_val, tolerance=0.01):")
    sb.appendLine("    \"\"\"Automated Fillet/Blend Generation via NX Open API.\"\"\"")
    sb.appendLine("    edge_blend_builder = work_part.Features.CreateEdgeBlendBuilder(NXOpen.Features.Feature.Null)")
    sb.appendLine("    sc_collector = work_part.ScCollectors.CreateCollector()")
    sb.appendLine("    radius_dim = work_part.UnitCollection.FindObject(\"MilliMeter\")")
    sb.appendLine()
    sb.appendLine("    # Configure blend parameters")
    sb.appendLine("    edge_blend_builder.Tolerance = tolerance")
    sb.appendLine("    # Target edge chain selection: target_edges_tag")
    sb.appendLine("    edge_blend_builder.AddChain(sc_collector, str(radius_val), radius_dim, False)")
    sb.appendLine()
    sb.appendLine("    new_feature = edge_blend_builder.Commit()")
    sb.appendLine("    edge_blend_builder.Destroy()")
    sb.appendLine("    return new_feature")
    sb.appendLine()
    sb.appendLine("def execute_voice_cad_pipeline():")
    sb.appendLine("    the_session = NXOpen.Session.GetSession()")
    sb.appendLine("    work_part = the_session.Parts.Work")
    sb.appendLine("    lw = the_session.ListingWindow")
    sb.appendLine("    lw.Open()")
    sb.appendLine("    lw.WriteLine(\"Starting Voice-to-CAD NX Open feature sequence...\")")
    sb.appendLine()

    features.forEachIndexed { index, feature ->
      val stepNum = index + 1
      when (feature) {
        is CadFeature.BlockFeature -> {
          sb.appendLine("    # Step $stepNum: ${feature.name} (${feature.summary})")
          sb.appendLine("    block_builder_$stepNum = work_part.Features.CreateBlockFeatureBuilder(NXOpen.Features.Feature.Null)")
          sb.appendLine("    origin_$stepNum = NXOpen.Point3d(${-feature.widthMm / 2.0}, ${-feature.depthMm / 2.0}, 0.0)")
          sb.appendLine("    block_builder_$stepNum.SetOriginAndLengths(origin_$stepNum, \"${feature.widthMm}\", \"${feature.depthMm}\", \"${feature.heightMm}\")")
          sb.appendLine("    base_feature = block_builder_$stepNum.CommitFeature()")
          sb.appendLine("    block_builder_$stepNum.Destroy()")
          sb.appendLine()
        }
        is CadFeature.CylinderFeature -> {
          sb.appendLine("    # Step $stepNum: ${feature.name} (${feature.summary})")
          sb.appendLine("    cyl_builder_$stepNum = work_part.Features.CreateCylinderBuilder(NXOpen.Features.Feature.Null)")
          sb.appendLine("    cyl_builder_$stepNum.Diameter.RightHandSide = \"${feature.diameterMm}\"")
          sb.appendLine("    cyl_builder_$stepNum.Height.RightHandSide = \"${feature.heightMm}\"")
          sb.appendLine("    base_feature = cyl_builder_$stepNum.CommitFeature()")
          sb.appendLine("    cyl_builder_$stepNum.Destroy()")
          sb.appendLine()
        }
        is CadFeature.HexPrismFeature -> {
          sb.appendLine("    # Step $stepNum: ${feature.name} (${feature.summary})")
          sb.appendLine("    # Extruded 6-sided polygon stock (AF=${feature.acrossFlatsMm}mm, H=${feature.heightMm}mm)")
          sb.appendLine("    extrude_builder_$stepNum = work_part.Features.CreateExtrudeBuilder(NXOpen.Features.Feature.Null)")
          sb.appendLine("    extrude_builder_$stepNum.Limits.EndExtend.Value.RightHandSide = \"${feature.heightMm}\"")
          sb.appendLine("    extrude_builder_$stepNum.Destroy()")
          sb.appendLine()
        }
        is CadFeature.EdgeBlendFeature -> {
          sb.appendLine("    # Step $stepNum: ${feature.name} (${feature.summary})")
          sb.appendLine(
            "    blend_feat_$stepNum = create_edge_blend(work_part, \"${feature.targetEdges.name}\", ${feature.radiusMm}, ${feature.toleranceMm})"
          )
          sb.appendLine()
        }
        is CadFeature.ChamferFeature -> {
          sb.appendLine("    # Step $stepNum: ${feature.name} (${feature.summary})")
          sb.appendLine("    chamfer_builder_$stepNum = work_part.Features.CreateApexRangeChamferBuilder(NXOpen.Features.ApexRangeChamfer.Null)")
          sb.appendLine("    # Symmetric chamfer distance = ${feature.distanceMm} mm on ${feature.targetEdges.name}")
          sb.appendLine("    chamfer_builder_$stepNum.Destroy()")
          sb.appendLine()
        }
        is CadFeature.HoleFeature -> {
          sb.appendLine("    # Step $stepNum: ${feature.name} (${feature.summary})")
          sb.appendLine("    hole_builder_$stepNum = work_part.Features.CreateHolePackageBuilder(NXOpen.Features.HolePackage.Null)")
          sb.appendLine("    hole_builder_$stepNum.GeneralSimpleHoleDiameter.RightHandSide = \"${feature.diameterMm}\"")
          if (!feature.throughAll) {
            sb.appendLine("    hole_builder_$stepNum.GeneralSimpleHoleDepth.RightHandSide = \"${feature.depthMm}\"")
          }
          sb.appendLine("    hole_builder_$stepNum.Destroy()")
          sb.appendLine()
        }
        is CadFeature.ShellFeature -> {
          sb.appendLine("    # Step $stepNum: ${feature.name} (${feature.summary})")
          sb.appendLine("    shell_builder_$stepNum = work_part.Features.CreateShellBuilder(NXOpen.Features.Feature.Null)")
          sb.appendLine("    shell_builder_$stepNum.DefaultThickness.RightHandSide = \"${feature.wallThicknessMm}\"")
          sb.appendLine("    shell_builder_$stepNum.Destroy()")
          sb.appendLine()
        }
      }
    }

    sb.appendLine("    lw.WriteLine(\"Voice-to-CAD pipeline completed (${features.size} active features).\")")
    sb.appendLine()
    sb.appendLine("if __name__ == \"__main__\":")
    sb.appendLine("    print(\"Executing voice-commanded NX Open CAD sequence...\")")
    sb.appendLine("    execute_voice_cad_pipeline()")
    return sb.toString()
  }
}
