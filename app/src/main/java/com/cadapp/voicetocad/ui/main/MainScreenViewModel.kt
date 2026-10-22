package com.cadapp.voicetocad.ui.main

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cadapp.voicetocad.data.CadNetworkService
import com.cadapp.voicetocad.data.DataRepository
import com.cadapp.voicetocad.data.DefaultDataRepository
import com.cadapp.voicetocad.domain.AppSettings
import com.cadapp.voicetocad.domain.BackendSource
import com.cadapp.voicetocad.domain.CadDimensionParameter
import com.cadapp.voicetocad.domain.CadDimensionRegistry
import com.cadapp.voicetocad.domain.CadFeature
import com.cadapp.voicetocad.domain.CadIntentParser
import com.cadapp.voicetocad.domain.CadMesh
import com.cadapp.voicetocad.domain.CadMeshEngine
import com.cadapp.voicetocad.domain.DimensionField
import com.cadapp.voicetocad.domain.EdgeTarget
import com.cadapp.voicetocad.domain.GuardrailWarning
import com.cadapp.voicetocad.domain.OpenCascadeStepKernel
import com.cadapp.voicetocad.domain.RenderMode
import com.cadapp.voicetocad.domain.VoiceCommandLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class WorkspaceTab(val title: String) {
  DIMENSIONS("Dimensions"),
  VOICE_AND_TREE("Tree & Log"),
  OCCT_KERNEL("OCCT Code"),
  FABRICATE_STL("STEP & STL"),
  SETTINGS("AI & STT"),
}

data class CadWorkspaceUiState(
  val features: List<CadFeature>,
  val dimensions: List<CadDimensionParameter>,
  val dimensionSearchQuery: String = "",
  val mesh: CadMesh,
  val nxOpenScript: String,
  val renderMode: RenderMode = RenderMode.SHADED_EDGES,
  val activeTab: WorkspaceTab = WorkspaceTab.DIMENSIONS,
  val isListening: Boolean = false,
  val isProcessing: Boolean = false,
  val partialTranscript: String = "",
  val promptInput: String = "",
  val statusMessage: String = "Ready — tap Mic, use a voice preset, or edit any dimension below",
  val warnings: List<GuardrailWarning> = emptyList(),
  val commandHistory: List<VoiceCommandLog> = emptyList(),
  val settings: AppSettings = AppSettings(),
)

sealed interface MainScreenUiState {
  object Loading : MainScreenUiState
  data class Error(val throwable: Throwable) : MainScreenUiState
  data class Success(val data: List<String>) : MainScreenUiState
}

class MainScreenViewModel(
  @Suppress("UNUSED_PARAMETER") dataRepository: DataRepository = DefaultDataRepository(),
) : ViewModel() {

  private val initialFeatures = listOf(
    CadFeature.BlockFeature(widthMm = 60f, depthMm = 40f, heightMm = 30f),
    CadFeature.EdgeBlendFeature(radiusMm = 5f, targetEdges = EdgeTarget.TOP),
  )

  private val _cadState = MutableStateFlow(
    buildWorkspaceState(
      features = initialFeatures,
      lastCommand = "Apply a 5mm fillet to the top edge of the current block",
      warnings = emptyList(),
      history = listOf(
        VoiceCommandLog(
          timestampLabel = currentTimeString(),
          transcript = "Apply a 5mm fillet to the top edge of the current block",
          intentSummary = "Apply R5.0mm Edge Blend (Top Edges)",
          source = BackendSource.LOCAL_DETERMINISTIC,
        )
      ),
    )
  )
  val cadState: StateFlow<CadWorkspaceUiState> = _cadState.asStateFlow()

  // Preserved for template unit test compatibility
  val uiState: StateFlow<MainScreenUiState> =
    MutableStateFlow(MainScreenUiState.Success(listOf("VoiceToCAD"))).asStateFlow()

  fun setPromptInput(text: String) {
    _cadState.update { it.copy(promptInput = text) }
  }

  fun setListeningState(listening: Boolean) {
    _cadState.update {
      it.copy(
        isListening = listening,
        statusMessage = if (listening) "Listening for CAD voice command..." else it.statusMessage,
      )
    }
  }

  fun setPartialTranscript(partial: String) {
    _cadState.update {
      it.copy(
        partialTranscript = partial,
        promptInput = partial,
      )
    }
  }

  fun setStatusMessage(msg: String) {
    _cadState.update { it.copy(statusMessage = msg) }
  }

  fun setRenderMode(mode: RenderMode) {
    _cadState.update { it.copy(renderMode = mode) }
  }

  fun setActiveTab(tab: WorkspaceTab) {
    _cadState.update { it.copy(activeTab = tab) }
  }

  fun setDimensionSearchQuery(query: String) {
    _cadState.update { it.copy(dimensionSearchQuery = query) }
  }

  fun updateDimensionValue(
    featureId: String,
    field: DimensionField,
    displayName: String,
    newValueMm: Float,
  ) {
    val current = _cadState.value
    val rawUpdated = CadDimensionRegistry.updateDimension(current.features, featureId, field, newValueMm)
    val (sanitized, warnings) = CadIntentParser.enforceAllGuardrails(rawUpdated)
    val dims = CadDimensionRegistry.extractDimensions(sanitized)
    val mesh = CadMeshEngine.buildMesh(sanitized)
    val label = "Edited $displayName = ${"%.1f".format(Locale.US, newValueMm)} mm"
    val script = OpenCascadeStepKernel.generateOcctKernelScript(sanitized, label, warnings)

    _cadState.update {
      it.copy(
        features = sanitized,
        dimensions = dims,
        mesh = mesh,
        nxOpenScript = script,
        warnings = warnings,
        statusMessage = label,
      )
    }
  }

  fun updateSettings(newSettings: AppSettings) {
    _cadState.update {
      it.copy(
        settings = newSettings,
        statusMessage = "Updated AI & Workstation configuration (${newSettings.aiMode.label})",
      )
    }
  }

  fun executeVoiceCommand(rawCommand: String) {
    val trimmed = rawCommand.trim()
    if (trimmed.isEmpty()) return

    val current = _cadState.value
    _cadState.update {
      it.copy(
        isProcessing = true,
        partialTranscript = "",
        promptInput = "",
        statusMessage = "Parsing CAD intent: \"$trimmed\"...",
      )
    }

    viewModelScope.launch {
      val parsed =
        CadNetworkService.processVoiceIntent(
          transcript = trimmed,
          currentFeatures = current.features,
          settings = current.settings,
        )

      val newLog =
        VoiceCommandLog(
          timestampLabel = currentTimeString(),
          transcript = trimmed,
          intentSummary = parsed.intentTitle,
          source = parsed.source,
          warnings = parsed.warnings,
        )

      val dims = CadDimensionRegistry.extractDimensions(parsed.updatedFeatures)
      val mesh = CadMeshEngine.buildMesh(parsed.updatedFeatures)
      val script =
        OpenCascadeStepKernel.generateOcctKernelScript(
          features = parsed.updatedFeatures,
          lastVoiceCommand = trimmed,
          warnings = parsed.warnings,
        )

      _cadState.update { state ->
        state.copy(
          features = parsed.updatedFeatures,
          dimensions = dims,
          mesh = mesh,
          nxOpenScript = script,
          isProcessing = false,
          warnings = parsed.warnings,
          commandHistory = listOf(newLog) + state.commandHistory.take(19),
          statusMessage = "${parsed.intentTitle} [${parsed.source.label}]",
        )
      }
    }
  }

  fun removeFeature(featureId: String) {
    val current = _cadState.value
    val remaining = current.features.filterNot { it.id == featureId }
    if (remaining.isEmpty()) {
      resetWorkspace()
      return
    }
    val (sanitized, warnings) = CadIntentParser.enforceAllGuardrails(remaining)
    val dims = CadDimensionRegistry.extractDimensions(sanitized)
    val mesh = CadMeshEngine.buildMesh(sanitized)
    val script = OpenCascadeStepKernel.generateOcctKernelScript(sanitized, "Removed feature", warnings)
    _cadState.update {
      it.copy(
        features = sanitized,
        dimensions = dims,
        mesh = mesh,
        nxOpenScript = script,
        warnings = warnings,
        statusMessage = "Removed feature (${sanitized.size} active features)",
      )
    }
  }

  fun resetWorkspace() {
    val defaultBlock = listOf(CadFeature.BlockFeature(widthMm = 60f, depthMm = 40f, heightMm = 30f))
    val dims = CadDimensionRegistry.extractDimensions(defaultBlock)
    val mesh = CadMeshEngine.buildMesh(defaultBlock)
    val script = OpenCascadeStepKernel.generateOcctKernelScript(defaultBlock, "Reset workspace")
    _cadState.update {
      it.copy(
        features = defaultBlock,
        dimensions = dims,
        mesh = mesh,
        nxOpenScript = script,
        warnings = emptyList(),
        statusMessage = "Workspace reset to 60 × 40 × 30 mm Base Block",
      )
    }
  }

  fun exportStlFileToCache(context: Context): File {
    val state = _cadState.value
    val asciiStl = CadMeshEngine.exportAsciiStl(state.mesh, "VoiceToCAD_Part")
    val file = File(context.cacheDir, "voice_to_cad_part.stl")
    file.writeText(asciiStl, Charsets.UTF_8)
    _cadState.update {
      it.copy(statusMessage = "Exported STL (${file.length() / 1024} KB) on device")
    }
    return file
  }

  fun exportStepFileToCache(context: Context): Pair<File, String> {
    val state = _cadState.value
    val stepContent = OpenCascadeStepKernel.exportIso10303Step(state.features, state.mesh)
    val file = File(context.cacheDir, "voice_to_cad_part.step")
    file.writeText(stepContent, Charsets.UTF_8)
    _cadState.update {
      it.copy(statusMessage = "Exported ISO-10303-21 .STEP (${file.length() / 1024} KB) on device")
    }
    return Pair(file, stepContent)
  }

  fun shareTextArtifact(context: Context, title: String, content: String) {
    val sendIntent =
      Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, content)
      }
    val chooser = Intent.createChooser(sendIntent, title).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
  }

  fun uploadToOctoPrint() {
    val state = _cadState.value
    _cadState.update {
      it.copy(isProcessing = true, statusMessage = "Uploading binary STL to OctoPrint (${state.settings.octoPrintUrl})...")
    }
    viewModelScope.launch {
      val binaryStl = CadMeshEngine.exportBinaryStl(state.mesh)
      val res =
        CadNetworkService.uploadStlToOctoPrint(
          stlBytes = binaryStl,
          fileName = "voice_to_cad_part.stl",
          settings = state.settings,
        )
      _cadState.update {
        it.copy(
          isProcessing = false,
          statusMessage =
            res.getOrElse { err ->
              "OctoPrint upload note: ${err.message} (check URL/API key in AI & LAN tab)"
            },
        )
      }
    }
  }

  fun dispatchToNxWorkstation() {
    val state = _cadState.value
    _cadState.update {
      it.copy(
        isProcessing = true,
        statusMessage = "Dispatching NX Open script to ${state.settings.nxWorkstationUrl}...",
      )
    }
    viewModelScope.launch {
      val res = CadNetworkService.dispatchNxScriptToWorkstation(state.nxOpenScript, state.settings)
      _cadState.update {
        it.copy(
          isProcessing = false,
          statusMessage =
            res.getOrElse { err ->
              "Workstation bridge note: ${err.message} (script ready for copy/export)"
            },
        )
      }
    }
  }

  private fun buildWorkspaceState(
    features: List<CadFeature>,
    lastCommand: String,
    warnings: List<GuardrailWarning>,
    history: List<VoiceCommandLog>,
  ): CadWorkspaceUiState {
    val dims = CadDimensionRegistry.extractDimensions(features)
    val mesh = CadMeshEngine.buildMesh(features)
    val script = OpenCascadeStepKernel.generateOcctKernelScript(features, lastCommand, warnings)
    return CadWorkspaceUiState(
      features = features,
      dimensions = dims,
      mesh = mesh,
      nxOpenScript = script,
      warnings = warnings,
      commandHistory = history,
    )
  }

  private fun currentTimeString(): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
}
