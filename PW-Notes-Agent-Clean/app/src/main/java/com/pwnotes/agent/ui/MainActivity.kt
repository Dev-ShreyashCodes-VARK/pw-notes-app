package com.pwnotes.agent.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pwnotes.agent.ai.SubAgentCoordinator
import com.pwnotes.agent.data.AppWhitelistManager
import com.pwnotes.agent.export.A4VisualNotesGenerator
import com.pwnotes.agent.service.FloatingOverlayService
import com.pwnotes.agent.service.ScreenCaptureService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var whitelistManager: AppWhitelistManager

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "Screen Capture Agent Active", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Screen capture permission declined", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        whitelistManager = AppWhitelistManager(this)

        setContent {
            MaterialTheme {
                MainAppScreen(
                    whitelistManager = whitelistManager,
                    onRequestScreenCapture = { requestMediaProjection() },
                    onRequestOverlayPermission = { requestOverlayPermission() },
                    onStartFloatingOverlay = { startFloatingOverlay() }
                )
            }
        }
    }

    private fun requestMediaProjection() {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            Toast.makeText(this, "Overlay permission already granted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startFloatingOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please grant Overlay permission first", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, FloatingOverlayService::class.java)
        startService(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    whitelistManager: AppWhitelistManager,
    onRequestScreenCapture: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onStartFloatingOverlay: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    var apiKeyInput by remember { mutableStateOf("") }
    var showApiDialog by remember { mutableStateOf(false) }

    val workflowStage by SubAgentCoordinator.stage.collectAsState()
    val capturedCount by SubAgentCoordinator.capturedCount.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PW Study Notes Agent") },
                actions = {
                    IconButton(onClick = { showApiDialog = true }) {
                        Icon(Icons.Default.Lock, contentDescription = "API Key")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Permissions & Setup") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("1. Review Notes") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("2. A4 Layout") }
                )
            }

            when (selectedTab) {
                0 -> SetupTab(
                    whitelistManager = whitelistManager,
                    capturedCount = capturedCount,
                    onRequestScreenCapture = onRequestScreenCapture,
                    onRequestOverlayPermission = onRequestOverlayPermission,
                    onStartFloatingOverlay = onStartFloatingOverlay
                )
                1 -> ReviewNotesTab(
                    stage = workflowStage,
                    onConfirmNotes = { approvedNotes ->
                        scope.launch {
                            SubAgentCoordinator.confirmAndGenerateA4VisualNotes(approvedNotes)
                            selectedTab = 2
                        }
                    }
                )
                2 -> VisualA4Tab(
                    stage = workflowStage,
                    context = context
                )
            }
        }
    }

    if (showApiDialog) {
        AlertDialog(
            onDismissRequest = { showApiDialog = false },
            title = { Text("Enter Gemini API Key") },
            text = {
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text("Gemini API Key") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    SubAgentCoordinator.apiKey = apiKeyInput.trim()
                    showApiDialog = false
                    Toast.makeText(context, "API Key saved", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showApiDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun SetupTab(
    whitelistManager: AppWhitelistManager,
    capturedCount: Int,
    onRequestScreenCapture: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onStartFloatingOverlay: () -> Unit
) {
    var allowedApps by remember { mutableStateOf(whitelistManager.getAllowedPackages().toList()) }
    var newAppPkg by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("🔒 Screen Privacy Guard", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "The AI will ONLY be granted permission to see screens when you are inside your allowed apps. Screen capture is instantly paused if you leave these apps.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text("Allowed Apps:", style = MaterialTheme.typography.labelMedium)
                allowedApps.forEach { pkg ->
                    Text("• $pkg", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newAppPkg,
                        onValueChange = { newAppPkg = it },
                        label = { Text("Add App Package") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        if (newAppPkg.isNotBlank()) {
                            whitelistManager.addPackage(newAppPkg.trim())
                            allowedApps = whitelistManager.getAllowedPackages().toList()
                            newAppPkg = ""
                        }
                    }) {
                        Text("Add")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Agent Actions", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onRequestOverlayPermission,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("1. Grant Floating Bubble Overlay")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onRequestScreenCapture,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("2. Authorize Screen Capture Agent")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onStartFloatingOverlay,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("3. Launch Floating Assistant Pill")
        }

        Spacer(modifier = Modifier.height(16.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Captured Slides/Pages: ", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "$capturedCount",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun ReviewNotesTab(
    stage: SubAgentCoordinator.WorkflowStage,
    onConfirmNotes: (String) -> Unit
) {
    var editableNotes by remember { mutableStateOf("") }

    LaunchedEffect(stage) {
        if (stage is SubAgentCoordinator.WorkflowStage.WaitingForUserReview) {
            editableNotes = stage.rawNotes
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        when (stage) {
            is SubAgentCoordinator.WorkflowStage.GeneratingCompleteNotes -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Transcribing entire PDF/slides faithfully...")
                        Text("Preserving formulas, diagrams, and hierarchy.", fontSize = 12.sp, color = Color.Gray)
                    }
                }
            }
            is SubAgentCoordinator.WorkflowStage.WaitingForUserReview -> {
                Text("Review & Edit Notes (Stage 1)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Remove unwanted pages or edit content. Once satisfied, click confirm to generate the visual A4 pages.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = editableNotes,
                    onValueChange = { editableNotes = it },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    label = { Text("Complete Transcribed Notes") }
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { onConfirmNotes(editableNotes) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Confirm Notes & Generate A4 Layout")
                }
            }
            is SubAgentCoordinator.WorkflowStage.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Error: ${stage.message}", color = MaterialTheme.colorScheme.error)
                }
            }
            else -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Capture slides from your study app and click 'Generate Notes' to begin.")
                }
            }
        }
    }
}

@Composable
fun VisualA4Tab(
    stage: SubAgentCoordinator.WorkflowStage,
    context: Context
) {
    val generator = remember { A4VisualNotesGenerator(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        when (stage) {
            is SubAgentCoordinator.WorkflowStage.GeneratingA4VisualLayout -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Designing page-by-page vertical A4 layout...")
                        Text("Distributing content cleanly & embedding visuals", fontSize = 12.sp, color = Color.Gray)
                    }
                }
            }
            is SubAgentCoordinator.WorkflowStage.Completed -> {
                Text("Vertical A4 Visual Pages Ready", style = MaterialTheme.typography.titleMedium)
                Text("${stage.a4HtmlPages.size} A4 pages created.", fontSize = 12.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        val fullDocument = stage.a4HtmlPages.joinToString("\n<div style='page-break-after: always;'></div>\n")
                        generator.exportHtmlToPdf(
                            htmlContent = fullDocument,
                            onComplete = { file ->
                                Toast.makeText(context, "Saved to: ${file.absolutePath}", Toast.LENGTH_LONG).show()
                            },
                            onError = { err ->
                                Toast.makeText(context, "Export error: ${err.message}", Toast.LENGTH_SHORT).show()
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Download Complete Notes as A4 PDF")
                }

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    stage.a4HtmlPages.forEachIndexed { index, pageHtml ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("A4 Page ${index + 1}", style = MaterialTheme.typography.labelLarge)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    pageHtml.take(200) + "...",
                                    fontSize = 11.sp,
                                    color = Color.DarkGray
                                )
                            }
                        }
                    }
                }
            }
            else -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Complete and confirm notes in Step 1 to generate the visual A4 layout.")
                }
            }
        }
    }
}
