package com.cadapp.voicetocad.ui.main

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.cadapp.voicetocad.data.DefaultDataRepository
import com.cadapp.voicetocad.domain.AiMode
import com.cadapp.voicetocad.domain.CadFeature
import com.cadapp.voicetocad.domain.CadMeshEngine
import com.cadapp.voicetocad.ui.components.CadViewport3D
import com.cadapp.voicetocad.ui.components.VoiceRecognizerHelper
import com.cadapp.voicetocad.theme.VoiceToCADTheme

private val presetVoiceCommands = listOf(
  "Apply a 5mm fillet to the top edge of the current block",
  "Drill a 14mm hole through the center",
  "Apply 4mm chamfer to bottom edges",
  "Fillet vertical edges 6mm",
  "Create cylinder 50mm diameter 45mm tall",
  "Create hex bolt stock 42mm by 28mm",
  "Shell part with 2.5mm wall",
  "Create 80x50x35mm block",
  "Apply 25mm fillet to top edge", // Triggers guardrail demo!
)

@Composable
fun MainScreen(
  @Suppress("UNUSED_PARAMETER") onItemClick: (NavKey) -> Unit = {},
  modifier: Modifier = Modifier,
  viewModel: MainScreenViewModel = viewModel { MainScreenViewModel(DefaultDataRepository()) },
) {
  val cadState by viewModel.cadState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  var isVoskInstalled by remember { mutableStateOf(false) }

  val voiceHelper =
    remember(context) {
      VoiceRecognizerHelper(
        context = context,
        scope = coroutineScope,
        onListeningStateChanged = { viewModel.setListeningState(it) },
        onPartialResult = { viewModel.setPartialTranscript(it) },
        onFinalResult = { transcript -> viewModel.executeVoiceCommand(transcript) },
        onErrorMessage = { msg -> viewModel.setStatusMessage(msg) },
      ).also {
        isVoskInstalled = it.isVoskModelInstalled()
      }
    }

  DisposableEffect(voiceHelper) {
    onDispose { voiceHelper.stopAllImmediate() }
  }

  val micPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) {
        voiceHelper.startListening(cadState.settings)
      } else {
        viewModel.setStatusMessage("Microphone permission denied — use text input or quick voice chips")
      }
    }

  VoiceToCadWorkspace(
    state = cadState,
    isVoskModelInstalled = isVoskInstalled,
    onDownloadVoskModel = {
      voiceHelper.downloadVoskModel(
        onProgress = { msg -> viewModel.setStatusMessage(msg) },
        onComplete = { ok -> isVoskInstalled = ok },
      )
    },
    onMicToggle = {
      if (cadState.isListening) {
        voiceHelper.stopListening(cadState.settings)
      } else {
        val hasPerm =
          ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (hasPerm) {
          voiceHelper.startListening(cadState.settings)
        } else {
          micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
      }
    },
    onPromptChange = viewModel::setPromptInput,
    onSubmitCommand = { cmd -> viewModel.executeVoiceCommand(cmd) },
    onDimensionSearchChange = viewModel::setDimensionSearchQuery,
    onUpdateDimension = { featId, field, name, newVal ->
      viewModel.updateDimensionValue(featId, field, name, newVal)
    },
    onRenderModeChange = viewModel::setRenderMode,
    onTabSelect = viewModel::setActiveTab,
    onRemoveFeature = viewModel::removeFeature,
    onResetWorkspace = viewModel::resetWorkspace,
    onCopyText = { label, text ->
      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
      viewModel.setStatusMessage("Copied $label to clipboard")
    },
    onShareScript = {
      viewModel.shareTextArtifact(
        context = context,
        title = "voice_to_cad_occt.py",
        content = cadState.nxOpenScript,
      )
    },
    onExportStep = {
      val (stepFile, stepContent) = viewModel.exportStepFileToCache(context)
      viewModel.shareTextArtifact(
        context = context,
        title = stepFile.name,
        content = stepContent,
      )
    },
    onExportStl = {
      val stlFile = viewModel.exportStlFileToCache(context)
      val asciiStl = CadMeshEngine.exportAsciiStl(cadState.mesh)
      viewModel.shareTextArtifact(
        context = context,
        title = stlFile.name,
        content = asciiStl,
      )
    },
    onUploadOctoPrint = viewModel::uploadToOctoPrint,
    onUploadSftp = viewModel::uploadStlViaSftp,
    onTestSsh = viewModel::testOctoPiSsh,
    onRefreshPrinter = viewModel::refreshPrinterStatus,
    onPrinterAction = viewModel::triggerPrinterAction,
    onRunSshCommand = viewModel::runCustomSshCommand,
    onUpdateSettings = viewModel::updateSettings,
    modifier = modifier,
  )
}

@Composable
fun VoiceToCadWorkspace(
  state: CadWorkspaceUiState,
  isVoskModelInstalled: Boolean,
  onDownloadVoskModel: () -> Unit,
  onMicToggle: () -> Unit,
  onPromptChange: (String) -> Unit,
  onSubmitCommand: (String) -> Unit,
  onDimensionSearchChange: (String) -> Unit,
  onUpdateDimension: (String, com.cadapp.voicetocad.domain.DimensionField, String, Float) -> Unit,
  onRenderModeChange: (com.cadapp.voicetocad.domain.RenderMode) -> Unit,
  onTabSelect: (WorkspaceTab) -> Unit,
  onRemoveFeature: (String) -> Unit,
  onResetWorkspace: () -> Unit,
  onCopyText: (String, String) -> Unit,
  onShareScript: () -> Unit,
  onExportStep: () -> Unit,
  onExportStl: () -> Unit,
  onUploadOctoPrint: () -> Unit,
  onUploadSftp: () -> Unit,
  onTestSsh: () -> Unit,
  onRefreshPrinter: () -> Unit,
  onPrinterAction: (com.cadapp.voicetocad.data.PrinterControlAction) -> Unit,
  onRunSshCommand: (String) -> Unit,
  onUpdateSettings: (com.cadapp.voicetocad.domain.AppSettings) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier =
      modifier
        .fillMaxSize()
        .background(Color(0xFF070C12))
        .statusBarsPadding()
        .navigationBarsPadding()
        .imePadding()
        .padding(horizontal = 12.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // 1. Header Bar
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
        Text(
          text = "VOICE-TO-CAD STUDIO",
          color = Color(0xFFE3F2FD),
          fontSize = 15.sp,
          fontWeight = FontWeight.ExtraBold,
          letterSpacing = 0.8.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = "${state.settings.aiMode.label} • OpenCASCADE STEP & STL",
          color = Color(0xFF4FC3F7),
          fontSize = 11.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Text(
        text = "Reset Block",
        color = Color(0xFFFF8A80),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier =
          Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1E293B))
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
            .clickable { onResetWorkspace() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
      )
    }

    // 2. Interactive 3D CAD Viewport
    CadViewport3D(
      mesh = state.mesh,
      renderMode = state.renderMode,
      onRenderModeChange = onRenderModeChange,
      modifier = Modifier.fillMaxWidth().height(205.dp),
    )

    // 3. Voice Command Input Bar & Quick Presets
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(14.dp))
          .background(Color(0xFF101926))
          .border(1.dp, Color(0xFF1E324A), RoundedCornerShape(14.dp))
          .padding(10.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        // Mic Button
        Box(
          contentAlignment = Alignment.Center,
          modifier =
            Modifier
              .size(44.dp)
              .clip(CircleShape)
              .background(if (state.isListening) Color(0xFFFF1744) else Color(0xFF0288D1))
              .clickable { onMicToggle() },
        ) {
          Text(
            text = if (state.isListening) "STOP" else "MIC",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
          )
        }

        // Command Input Box
        Box(
          modifier =
            Modifier
              .weight(1f)
              .clip(RoundedCornerShape(10.dp))
              .background(Color(0xFF0A1018))
              .border(
                1.dp,
                if (state.isListening) Color(0xFFFF5252) else Color(0xFF263B54),
                RoundedCornerShape(10.dp),
              )
              .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
          if (state.promptInput.isEmpty()) {
            Text(
              text =
                if (state.isListening) "Listening... speak a CAD command"
                else "Say e.g. \"Apply 5mm fillet to top edge\"...",
              color = Color(0xFF607D8B),
              fontSize = 13.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          BasicTextField(
            value = state.promptInput,
            onValueChange = onPromptChange,
            textStyle = TextStyle(color = Color(0xFFE1F5FE), fontSize = 13.sp),
            cursorBrush = SolidColor(Color(0xFF29B6F6)),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
        }

        // Execute Command Button
        Box(
          contentAlignment = Alignment.Center,
          modifier =
            Modifier
              .clip(RoundedCornerShape(10.dp))
              .background(Color(0xFF00ACC1))
              .clickable {
                if (state.promptInput.isNotBlank()) {
                  onSubmitCommand(state.promptInput)
                }
              }
              .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
          Text(
            text = "Run",
            color = Color(0xFF041016),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
          )
        }
      }

      // Quick Voice Command Chips
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        presetVoiceCommands.forEach { preset ->
          Text(
            text = preset,
            color = Color(0xFFB3E5FC),
            fontSize = 11.sp,
            modifier =
              Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF17263B))
                .border(1.dp, Color(0xFF253D5B), RoundedCornerShape(8.dp))
                .clickable { onSubmitCommand(preset) }
                .padding(horizontal = 10.dp, vertical = 5.dp),
          )
        }
      }

      // Status / Guardrail Warning Banner
      Text(
        text = state.statusMessage,
        color = if (state.warnings.isNotEmpty()) Color(0xFFFFD54F) else Color(0xFF81D4FA),
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }

    // 4. Workbench Navigation Tabs
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      WorkspaceTab.entries.forEach { tab ->
        val selected = state.activeTab == tab
        Box(
          contentAlignment = Alignment.Center,
          modifier =
            Modifier
              .weight(1f)
              .clip(RoundedCornerShape(10.dp))
              .background(if (selected) Color(0xFF0288D1) else Color(0xFF111B27))
              .border(
                1.dp,
                if (selected) Color(0xFF4FC3F7) else Color(0xFF1F2F42),
                RoundedCornerShape(10.dp),
              )
              .clickable { onTabSelect(tab) }
              .padding(horizontal = 3.dp, vertical = 8.dp),
        ) {
          Text(
            text = tab.title,
            color = if (selected) Color.White else Color(0xFF90A4AE),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }

    // 5. Tab Content Area
    Box(
      modifier =
        Modifier
          .weight(1f)
          .fillMaxWidth()
          .clip(RoundedCornerShape(14.dp))
          .background(Color(0xFF0D1520))
          .border(1.dp, Color(0xFF1C2C40), RoundedCornerShape(14.dp))
          .padding(12.dp)
    ) {
      when (state.activeTab) {
        WorkspaceTab.DIMENSIONS ->
          DimensionsTableTab(
            state = state,
            onSearchQueryChange = onDimensionSearchChange,
            onUpdateDimension = onUpdateDimension,
          )
        WorkspaceTab.VOICE_AND_TREE ->
          FeatureTreeAndHistoryTab(
            state = state,
            onRemoveFeature = onRemoveFeature,
          )
        WorkspaceTab.OCTOPI_CONTROL ->
          StlAndOctoPrintTab(
            state = state,
            onExportStep = onExportStep,
            onExportStl = onExportStl,
            onUploadOctoPrint = onUploadOctoPrint,
            onUploadSftp = onUploadSftp,
            onTestSsh = onTestSsh,
            onRefreshPrinter = onRefreshPrinter,
            onPrinterAction = onPrinterAction,
            onRunSshCommand = onRunSshCommand,
            onUpdateSettings = onUpdateSettings,
          )
        WorkspaceTab.OCCT_KERNEL ->
          OcctKernelTab(
            script = state.nxOpenScript,
            onCopy = { onCopyText("OpenCASCADE / CadQuery Script", state.nxOpenScript) },
            onShare = onShareScript,
          )
        WorkspaceTab.SETTINGS ->
          AiAndLanSettingsTab(
            settings = state.settings,
            isVoskModelInstalled = isVoskModelInstalled,
            onDownloadVoskModel = onDownloadVoskModel,
            onTestSsh = onTestSsh,
            onUpdateSettings = onUpdateSettings,
          )
      }
    }
  }
}

@Composable
private fun DimensionsTableTab(
  state: CadWorkspaceUiState,
  onSearchQueryChange: (String) -> Unit,
  onUpdateDimension: (String, com.cadapp.voicetocad.domain.DimensionField, String, Float) -> Unit,
) {
  val query = state.dimensionSearchQuery.trim().lowercase()
  val filteredDims =
    remember(state.dimensions, query) {
      if (query.isEmpty()) {
        state.dimensions
      } else {
        state.dimensions.filter { param ->
          param.displayName.lowercase().contains(query) ||
            param.symbolName.lowercase().contains(query) ||
            param.featureName.lowercase().contains(query)
        }
      }
    }

  Column(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // 1. Search & Filter Bar
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(10.dp))
          .background(Color(0xFF090F17))
          .border(1.dp, Color(0xFF233852), RoundedCornerShape(10.dp))
          .padding(horizontal = 12.dp, vertical = 9.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Box(modifier = Modifier.weight(1f)) {
        if (state.dimensionSearchQuery.isEmpty()) {
          Text(
            text = "Search dimensions (e.g. width, fillet, radius, hole, z)...",
            color = Color(0xFF607D8B),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        BasicTextField(
          value = state.dimensionSearchQuery,
          onValueChange = onSearchQueryChange,
          textStyle = TextStyle(color = Color(0xFFE1F5FE), fontSize = 12.sp),
          cursorBrush = SolidColor(Color(0xFF29B6F6)),
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
      if (state.dimensionSearchQuery.isNotEmpty()) {
        Text(
          text = "Clear",
          color = Color(0xFF4FC3F7),
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          modifier =
            Modifier
              .padding(start = 8.dp)
              .clickable { onSearchQueryChange("") },
        )
      } else {
        Text(
          text = "${filteredDims.size} params",
          color = Color(0xFF78909C),
          fontSize = 10.sp,
          fontFamily = FontFamily.Monospace,
        )
      }
    }

    // 2. Table Header Row
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(6.dp))
          .background(Color(0xFF132030))
          .padding(horizontal = 10.dp, vertical = 5.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "DIMENSION NAME / SYMBOL",
        color = Color(0xFF4FC3F7),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
      )
      Text(
        text = "VALUE (MM) & QUICK EDIT",
        color = Color(0xFF4FC3F7),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
      )
    }

    // 3. Scrollable Editable Dimension Rows
    Column(
      modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (filteredDims.isEmpty()) {
        Text(
          text = "No dimensions match \"${state.dimensionSearchQuery}\".",
          color = Color(0xFF90A4AE),
          fontSize = 12.sp,
          modifier = Modifier.padding(12.dp),
        )
      }

      filteredDims.forEach { param ->
        var textVal by remember(param.id, param.valueMm) {
          mutableStateOf(
            if (param.valueMm == param.valueMm.toLong().toFloat()) {
              param.valueMm.toLong().toString()
            } else {
              "%.1f".format(java.util.Locale.US, param.valueMm)
            }
          )
        }

        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(10.dp))
              .background(Color(0xFF121E2E))
              .border(1.dp, Color(0xFF21354E), RoundedCornerShape(10.dp))
              .padding(horizontal = 10.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            // Left: Parameter Name + Variable Symbol + Feature Owner
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = param.displayName,
                color = Color(0xFFE3F2FD),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
              )
              Text(
                text = "${param.symbolName} • ${param.featureName} (${param.minMm.toInt()}–${param.maxMm.toInt()} mm)",
                color = Color(0xFF81D4FA),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
              )
            }

            // Right: Stepper (-), Editable Text Box (mm), Set Button, Stepper (+)
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              // Decrement button
              Box(
                contentAlignment = Alignment.Center,
                modifier =
                  Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF1E324A))
                    .clickable {
                      val next = (param.valueMm - param.stepMm).coerceIn(param.minMm, param.maxMm)
                      onUpdateDimension(param.featureId, param.field, param.displayName, next)
                    },
              ) {
                Text("-", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
              }

              // Direct text input field
              Box(
                contentAlignment = Alignment.Center,
                modifier =
                  Modifier
                    .width(56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF091019))
                    .border(1.dp, Color(0xFF29B6F6), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 5.dp),
              ) {
                BasicTextField(
                  value = textVal,
                  onValueChange = { input ->
                    textVal = input
                    val parsed = input.toFloatOrNull()
                    if (parsed != null && parsed >= param.minMm && parsed <= param.maxMm) {
                      onUpdateDimension(param.featureId, param.field, param.displayName, parsed)
                    }
                  },
                  textStyle =
                    TextStyle(
                      color = Color(0xFF00E5FF),
                      fontSize = 12.sp,
                      fontFamily = FontFamily.Monospace,
                      fontWeight = FontWeight.Bold,
                    ),
                  cursorBrush = SolidColor(Color(0xFF00E5FF)),
                  singleLine = true,
                )
              }

              Text(
                text = "mm",
                color = Color(0xFF90A4AE),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
              )

              // Increment button
              Box(
                contentAlignment = Alignment.Center,
                modifier =
                  Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF1E324A))
                    .clickable {
                      val next = (param.valueMm + param.stepMm).coerceIn(param.minMm, param.maxMm)
                      onUpdateDimension(param.featureId, param.field, param.displayName, next)
                    },
              ) {
                Text("+", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
              }
            }
          }

          // Interactive Thumb Slider for fast 120Hz scrubbing on Pixel 9 Pro
          Slider(
            value = param.valueMm.coerceIn(param.minMm, param.maxMm),
            onValueChange = { raw ->
              val rounded = (kotlin.math.round(raw * 2f) / 2f).coerceIn(param.minMm, param.maxMm)
              onUpdateDimension(param.featureId, param.field, param.displayName, rounded)
            },
            valueRange = param.minMm..param.maxMm.coerceAtLeast(param.minMm + 1f),
            colors =
              SliderDefaults.colors(
                thumbColor = Color(0xFF00E5FF),
                activeTrackColor = Color(0xFF0288D1),
                inactiveTrackColor = Color(0xFF1B2B40),
              ),
            modifier = Modifier.fillMaxWidth().height(36.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun FeatureTreeAndHistoryTab(
  state: CadWorkspaceUiState,
  onRemoveFeature: (String) -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    // Active Guardrail Alerts
    if (state.warnings.isNotEmpty()) {
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF2B2111))
            .border(1.dp, Color(0xFFFFB300), RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
          text = "DETERMINISTIC ENGINEERING GUARDRAILS TRIGGERED",
          color = Color(0xFFFFCA28),
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
        )
        state.warnings.forEach { w ->
          Text(
            text = "• ${w.ruleName}: ${w.message} (${w.originalValueMm}mm → ${w.clampedValueMm}mm)",
            color = Color(0xFFFFE082),
            fontSize = 11.sp,
          )
        }
      }
    }

    Text(
      text = "PARAMETRIC FEATURE TREE (${state.features.size})",
      color = Color(0xFF4FC3F7),
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold,
      fontFamily = FontFamily.Monospace,
    )

    state.features.forEachIndexed { index, feat ->
      val isBase =
        feat is CadFeature.BlockFeature ||
          feat is CadFeature.CylinderFeature ||
          feat is CadFeature.HexPrismFeature
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF142132))
            .border(1.dp, Color(0xFF223650), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "${index + 1}. ${feat.name}",
            color = Color(0xFFE3F2FD),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            text = feat.summary,
            color = Color(0xFF81D4FA),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
          )
        }
        if (!isBase) {
          Text(
            text = "Delete",
            color = Color(0xFFFF8A80),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier =
              Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF2C1B21))
                .clickable { onRemoveFeature(feat.id) }
                .padding(horizontal = 8.dp, vertical = 4.dp),
          )
        } else {
          Text(
            text = "BASE SOLID",
            color = Color(0xFF64B5F6),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text = "VOICE INTENT LOG (${state.commandHistory.size})",
      color = Color(0xFF4FC3F7),
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold,
      fontFamily = FontFamily.Monospace,
    )

    state.commandHistory.forEach { log ->
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF101A28))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = "\"${log.transcript}\"",
            color = Color(0xFFB3E5FC),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
          )
          Text(
            text = log.timestampLabel,
            color = Color(0xFF607D8B),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
          )
        }
        Text(
          text = "→ ${log.intentSummary} • ${log.source.label}",
          color = Color(0xFF80CBC4),
          fontSize = 11.sp,
          fontFamily = FontFamily.Monospace,
        )
      }
    }
  }
}

@Composable
private fun OcctKernelTab(
  script: String,
  onCopy: () -> Unit,
  onShare: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Button(
        onClick = onCopy,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0288D1), contentColor = Color.White),
        modifier = Modifier.weight(1f),
      ) {
        Text("Copy OCCT / CadQuery", fontSize = 12.sp)
      }
      Button(
        onClick = onShare,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B), contentColor = Color.White),
        modifier = Modifier.weight(1f),
      ) {
        Text("Share Script", fontSize = 12.sp)
      }
    }

    Box(
      modifier =
        Modifier
          .weight(1f)
          .fillMaxWidth()
          .clip(RoundedCornerShape(10.dp))
          .background(Color(0xFF070B10))
          .border(1.dp, Color(0xFF1E2D3D), RoundedCornerShape(10.dp))
          .padding(10.dp)
          .verticalScroll(rememberScrollState())
          .horizontalScroll(rememberScrollState())
    ) {
      Text(
        text = script,
        color = Color(0xFFA5D6FF),
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        lineHeight = 16.sp,
      )
    }
  }
}

@Composable
private fun StlAndOctoPrintTab(
  state: CadWorkspaceUiState,
  onExportStep: () -> Unit,
  onExportStl: () -> Unit,
  onUploadOctoPrint: () -> Unit,
  onUploadSftp: () -> Unit,
  onTestSsh: () -> Unit,
  onRefreshPrinter: () -> Unit,
  onPrinterAction: (com.cadapp.voicetocad.data.PrinterControlAction) -> Unit,
  onRunSshCommand: (String) -> Unit,
  onUpdateSettings: (com.cadapp.voicetocad.domain.AppSettings) -> Unit,
) {
  val mesh = state.mesh
  val telemetry = state.printerTelemetry
  val settings = state.settings
  val estFilamentGrams = mesh.volumeCm3 * 1.24f * 0.35f
  var showSshConfigMenu by remember { mutableStateOf(false) }
  var customSshCmd by remember { mutableStateOf("vcgencmd measure_temp && uptime") }

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    // 1. CAD Export & Direct OctoPi Upload Row
    Text(
      text = "1. EXPORT & SEND TO OCTOPI (.STEP / .STL)",
      color = Color(0xFF4FC3F7),
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold,
      fontFamily = FontFamily.Monospace,
    )

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Button(
        onClick = onExportStep,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B), contentColor = Color.White),
        modifier = Modifier.weight(1f),
      ) {
        Text(".STEP", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
      }
      Button(
        onClick = onExportStl,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0288D1), contentColor = Color.White),
        modifier = Modifier.weight(1f),
      ) {
        Text(".STL", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
      }
      Button(
        onClick = onUploadSftp,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E35B1), contentColor = Color.White),
        modifier = Modifier.weight(1.2f),
      ) {
        Text("SFTP Send", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
      }
      Button(
        onClick = onUploadOctoPrint,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32), contentColor = Color.White),
        modifier = Modifier.weight(1.1f),
      ) {
        Text("API Send", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
      }
    }

    // 2. Live 3D Printer Telemetry & Status Header
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "2. LIVE 3D PRINTER CONTROL",
        color = Color(0xFF4FC3F7),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).padding(end = 6.dp),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
          text = if (showSshConfigMenu) "Hide SSH Menu" else "OctoPi SSH Setup",
          color = Color(0xFFFFD54F),
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          modifier =
            Modifier
              .clip(RoundedCornerShape(6.dp))
              .background(Color(0xFF263238))
              .border(1.dp, Color(0xFFFFB300), RoundedCornerShape(6.dp))
              .clickable { showSshConfigMenu = !showSshConfigMenu }
              .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Text(
          text = "Poll Temps",
          color = Color(0xFF80DEEA),
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          modifier =
            Modifier
              .clip(RoundedCornerShape(6.dp))
              .background(Color(0xFF162436))
              .border(1.dp, Color(0xFF29B6F6), RoundedCornerShape(6.dp))
              .clickable { onRefreshPrinter() }
              .padding(horizontal = 8.dp, vertical = 4.dp),
        )
      }
    }

    // Collapsible OctoPi SSH Credentials Menu right inside the Printer tab!
    if (showSshConfigMenu) {
      OctoPiSshMenuCard(
        settings = settings,
        telemetry = telemetry,
        onTestSsh = onTestSsh,
        onUpdateSettings = onUpdateSettings,
      )
    }

    // Telemetry Cards Row
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      StatCard(
        label = "PRINTER STATE",
        value = "${telemetry.printerStateText} (${"%.0f".format(telemetry.jobProgressPercent)}%)",
        modifier = Modifier.weight(1f),
      )
      StatCard(
        label = "NOZZLE / BED TEMP",
        value =
          "N: ${telemetry.nozzleActualC.toInt()}/${telemetry.nozzleTargetC.toInt()}°C • B: ${telemetry.bedActualC.toInt()}/${telemetry.bedTargetC.toInt()}°C",
        modifier = Modifier.weight(1f),
      )
    }

    // 3. Start / Pause / Stop Print Job Controls
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Button(
        onClick = { onPrinterAction(com.cadapp.voicetocad.data.PrinterControlAction.START_PRINT) },
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32), contentColor = Color.White),
        modifier = Modifier.weight(1f),
      ) {
        Text("▶ Start Print", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
      }
      Button(
        onClick = { onPrinterAction(com.cadapp.voicetocad.data.PrinterControlAction.PAUSE_RESUME) },
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF57F17), contentColor = Color.White),
        modifier = Modifier.weight(1f),
      ) {
        Text("⏸ Pause", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
      }
      Button(
        onClick = { onPrinterAction(com.cadapp.voicetocad.data.PrinterControlAction.CANCEL_PRINT) },
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828), contentColor = Color.White),
        modifier = Modifier.weight(1f),
      ) {
        Text("⏹ Stop", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
      }
    }

    // 4. Hardware Motion & Thermal Preheats
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Button(
        onClick = { onPrinterAction(com.cadapp.voicetocad.data.PrinterControlAction.HOME_XYZ) },
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0), contentColor = Color.White),
        modifier = Modifier.weight(1f),
      ) {
        Text("Home XYZ", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
      }
      Button(
        onClick = { onPrinterAction(com.cadapp.voicetocad.data.PrinterControlAction.PREHEAT_PLA) },
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD84315), contentColor = Color.White),
        modifier = Modifier.weight(1f),
      ) {
        Text("PLA 205°", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
      }
      Button(
        onClick = { onPrinterAction(com.cadapp.voicetocad.data.PrinterControlAction.PREHEAT_PETG) },
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFAD1457), contentColor = Color.White),
        modifier = Modifier.weight(1f),
      ) {
        Text("PETG 235°", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
      }
      Button(
        onClick = { onPrinterAction(com.cadapp.voicetocad.data.PrinterControlAction.COOLDOWN) },
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F), contentColor = Color.White),
        modifier = Modifier.weight(1f),
      ) {
        Text("Cooldown", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
      }
    }

    // 5. OctoPi SSH Command Runner & Part Info
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      StatCard(
        label = "PART DIMENSIONS",
        value = "${mesh.boundingBoxMm.x.toInt()}×${mesh.boundingBoxMm.y.toInt()}×${mesh.boundingBoxMm.z.toInt()} mm",
        modifier = Modifier.weight(1f),
      )
      StatCard(
        label = "EST. FILAMENT",
        value = "${"%.1f".format(mesh.volumeCm3)} cm³ (~${"%.1f".format(estFilamentGrams)}g PLA)",
        modifier = Modifier.weight(1f),
      )
    }

    Text(
      text = "OCTOPI SSH TERMINAL (${settings.octoPiSshUser}@${settings.octoPiSshHost})",
      color = Color(0xFF90A4AE),
      fontSize = 10.sp,
      fontFamily = FontFamily.Monospace,
    )

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier =
          Modifier
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF080D14))
            .border(1.dp, Color(0xFF263B54), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
      ) {
        BasicTextField(
          value = customSshCmd,
          onValueChange = { customSshCmd = it },
          textStyle =
            TextStyle(
              color = Color(0xFF80CBC4),
              fontSize = 12.sp,
              fontFamily = FontFamily.Monospace,
            ),
          cursorBrush = SolidColor(Color(0xFF29B6F6)),
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
      Button(
        onClick = { onRunSshCommand(customSshCmd) },
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B), contentColor = Color.White),
      ) {
        Text("SSH Exec", fontSize = 11.sp, fontWeight = FontWeight.Bold)
      }
    }

    if (telemetry.lastSshOutput.isNotBlank()) {
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF070B10))
            .border(1.dp, Color(0xFF1E2D3D), RoundedCornerShape(8.dp))
            .padding(10.dp)
      ) {
        Text(
          text = telemetry.lastSshOutput,
          color = Color(0xFFA5D6FF),
          fontSize = 10.sp,
          fontFamily = FontFamily.Monospace,
        )
      }
    }
  }
}

@Composable
private fun OctoPiSshMenuCard(
  settings: com.cadapp.voicetocad.domain.AppSettings,
  telemetry: com.cadapp.voicetocad.domain.PrinterTelemetryState,
  onTestSsh: () -> Unit,
  onUpdateSettings: (com.cadapp.voicetocad.domain.AppSettings) -> Unit,
) {
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(Color(0xFF131E2E))
        .border(1.dp, Color(0xFF29B6F6), RoundedCornerShape(12.dp))
        .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "OCTOPI SSH & PRINTER CREDENTIALS",
          color = Color(0xFFFFD54F),
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          fontFamily = FontFamily.Monospace,
        )
        Text(
          text = telemetry.piSystemSummary,
          color = if (telemetry.sshConnected) Color(0xFF69F0AE) else Color(0xFFB0BEC5),
          fontSize = 10.sp,
        )
      }
      Button(
        onClick = onTestSsh,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0288D1), contentColor = Color.White),
      ) {
        Text("Test SSH & Auto-Key", fontSize = 11.sp, fontWeight = FontWeight.Bold)
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OutlinedTextField(
        value = settings.octoPiSshHost,
        onValueChange = {
          onUpdateSettings(
            settings.copy(
              octoPiSshHost = it,
              octoPrintUrl = if (it.startsWith("http")) it else "http://$it",
            )
          )
        },
        label = { Text("OctoPi Host / IP (e.g. octopi.local)") },
        singleLine = true,
        modifier = Modifier.weight(2f),
      )
      OutlinedTextField(
        value = settings.octoPiSshPort.toString(),
        onValueChange = { portStr ->
          val p = portStr.toIntOrNull() ?: 22
          onUpdateSettings(settings.copy(octoPiSshPort = p))
        },
        label = { Text("Port") },
        singleLine = true,
        modifier = Modifier.weight(1f),
      )
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OutlinedTextField(
        value = settings.octoPiSshUser,
        onValueChange = { onUpdateSettings(settings.copy(octoPiSshUser = it)) },
        label = { Text("SSH Username (e.g. pi)") },
        singleLine = true,
        modifier = Modifier.weight(1f),
      )
      OutlinedTextField(
        value = settings.octoPiSshPassword,
        onValueChange = { onUpdateSettings(settings.copy(octoPiSshPassword = it)) },
        label = { Text("SSH Password") },
        singleLine = true,
        modifier = Modifier.weight(1f),
      )
    }

    OutlinedTextField(
      value = settings.octoPiRemoteDir,
      onValueChange = { onUpdateSettings(settings.copy(octoPiRemoteDir = it)) },
      label = { Text("SFTP Upload Folder (/home/pi/.octoprint/watched)") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
      value = settings.octoPrintApiKey,
      onValueChange = { onUpdateSettings(settings.copy(octoPrintApiKey = it)) },
      label = { Text("OctoPrint API Key (auto-filled when you tap Test SSH)") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
private fun StatCard(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier =
      modifier
        .clip(RoundedCornerShape(10.dp))
        .background(Color(0xFF142132))
        .border(1.dp, Color(0xFF223650), RoundedCornerShape(10.dp))
        .padding(10.dp)
  ) {
    Text(
      text = label,
      color = Color(0xFF78909C),
      fontSize = 10.sp,
      fontFamily = FontFamily.Monospace,
    )
    Spacer(modifier = Modifier.height(2.dp))
    Text(
      text = value,
      color = Color(0xFFE3F2FD),
      fontSize = 12.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun AiAndLanSettingsTab(
  settings: com.cadapp.voicetocad.domain.AppSettings,
  isVoskModelInstalled: Boolean,
  onDownloadVoskModel: () -> Unit,
  onTestSsh: () -> Unit,
  onUpdateSettings: (com.cadapp.voicetocad.domain.AppSettings) -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(
      text = "OCTOPI SSH & 3D PRINTER SETUP",
      color = Color(0xFFFFD54F),
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold,
      fontFamily = FontFamily.Monospace,
    )

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OutlinedTextField(
        value = settings.octoPiSshHost,
        onValueChange = {
          onUpdateSettings(
            settings.copy(
              octoPiSshHost = it,
              octoPrintUrl = if (it.startsWith("http")) it else "http://$it",
            )
          )
        },
        label = { Text("OctoPi Host / IP (octopi.local)") },
        singleLine = true,
        modifier = Modifier.weight(2f),
      )
      OutlinedTextField(
        value = settings.octoPiSshPort.toString(),
        onValueChange = { portStr ->
          onUpdateSettings(settings.copy(octoPiSshPort = portStr.toIntOrNull() ?: 22))
        },
        label = { Text("SSH Port") },
        singleLine = true,
        modifier = Modifier.weight(1f),
      )
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OutlinedTextField(
        value = settings.octoPiSshUser,
        onValueChange = { onUpdateSettings(settings.copy(octoPiSshUser = it)) },
        label = { Text("SSH User (pi)") },
        singleLine = true,
        modifier = Modifier.weight(1f),
      )
      OutlinedTextField(
        value = settings.octoPiSshPassword,
        onValueChange = { onUpdateSettings(settings.copy(octoPiSshPassword = it)) },
        label = { Text("SSH Password") },
        singleLine = true,
        modifier = Modifier.weight(1f),
      )
    }

    Button(
      onClick = onTestSsh,
      colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0288D1), contentColor = Color.White),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text("Test OctoPi SSH Connection & Auto-Fetch OctoPrint API Key", fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }

    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text = "SPEECH-TO-TEXT (STT) ENGINE • PIXEL 9 PRO & OPEN-SOURCE",
      color = Color(0xFF4FC3F7),
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold,
      fontFamily = FontFamily.Monospace,
    )

    com.cadapp.voicetocad.domain.SttEngineMode.entries.forEach { stt ->
      val selected = settings.sttEngine == stt
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFF132A42) else Color(0xFF101924))
            .border(
              1.dp,
              if (selected) Color(0xFF00E5FF) else Color(0xFF1F2F42),
              RoundedCornerShape(10.dp),
            )
            .clickable { onUpdateSettings(settings.copy(sttEngine = stt)) }
            .padding(10.dp)
      ) {
        Text(
          text = stt.label,
          color = Color(0xFFE3F2FD),
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text = stt.description,
          color = Color(0xFF90A4AE),
          fontSize = 11.sp,
        )
      }
    }

    if (settings.sttEngine == com.cadapp.voicetocad.domain.SttEngineMode.WHISPER_OPEN_SOURCE) {
      OutlinedTextField(
        value = settings.whisperEndpointUrl,
        onValueChange = { onUpdateSettings(settings.copy(whisperEndpointUrl = it)) },
        label = { Text("Open-Source Whisper Server URL (faster-whisper / whisper.cpp)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
    }

    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text = "AI CODE GENERATION BACKEND",
      color = Color(0xFF4FC3F7),
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold,
      fontFamily = FontFamily.Monospace,
    )

    AiMode.entries.forEach { mode ->
      val selected = settings.aiMode == mode
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFF162C46) else Color(0xFF121C29))
            .border(
              1.dp,
              if (selected) Color(0xFF29B6F6) else Color(0xFF203044),
              RoundedCornerShape(10.dp),
            )
            .clickable { onUpdateSettings(settings.copy(aiMode = mode)) }
            .padding(10.dp)
      ) {
        Text(
          text = mode.label,
          color = Color(0xFFE3F2FD),
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text = mode.description,
          color = Color(0xFF90A4AE),
          fontSize = 11.sp,
        )
      }
    }

    OutlinedTextField(
      value = settings.geminiApiKey,
      onValueChange = { onUpdateSettings(settings.copy(geminiApiKey = it)) },
      label = { Text("Gemini API Key (optional in Hybrid mode)") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
      value = settings.lanEndpointUrl,
      onValueChange = { onUpdateSettings(settings.copy(lanEndpointUrl = it)) },
      label = { Text("Local Workstation LLM URL (RX 7800 XT / Ollama / vLLM)") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
      value = settings.nxWorkstationUrl,
      onValueChange = { onUpdateSettings(settings.copy(nxWorkstationUrl = it)) },
      label = { Text("Siemens NX Open Workstation Bridge URL") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
      value = settings.octoPrintUrl,
      onValueChange = { onUpdateSettings(settings.copy(octoPrintUrl = it)) },
      label = { Text("OctoPrint Host URL (e.g. http://octopi.local)") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
      value = settings.octoPrintApiKey,
      onValueChange = { onUpdateSettings(settings.copy(octoPrintApiKey = it)) },
      label = { Text("OctoPrint X-Api-Key") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )

    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Checkbox(
        checked = settings.autoStartPrintOnUpload,
        onCheckedChange = { onUpdateSettings(settings.copy(autoStartPrintOnUpload = it)) },
        colors = CheckboxDefaults.colors(checkedColor = Color(0xFF29B6F6)),
      )
      Spacer(modifier = Modifier.width(4.dp))
      Text(
        text = "Auto-select & start print job on OctoPrint upload",
        color = Color(0xFFCFD8DC),
        fontSize = 12.sp,
      )
    }
  }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun MainScreenPreview() {
  VoiceToCADTheme {
    Surface {
      MainScreen(onItemClick = {})
    }
  }
}
