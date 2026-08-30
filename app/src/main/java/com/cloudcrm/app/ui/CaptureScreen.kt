package com.cloudcrm.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.cloudcrm.app.CloudCrmApplication
import com.cloudcrm.app.R
import com.cloudcrm.app.util.VoiceRecorder
import com.cloudcrm.app.viewmodel.CloudCrmViewModel
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch

enum class CaptureInputMode {
    VOICE,
    TEXT
}

private const val PLACEHOLDER_NOTE = "Met Dave (Maya's dad) at soccer last Friday. Elena mentioned elevator repair is tomorrow."

private val SAMPLE_PROMPTS = listOf(
    "Met Dave (Maya's dad) at soccer last Friday. Elena mentioned elevator repair is Friday.",
    "Coffee with Sarah Jenkins from Acme Corp yesterday. She wants a product demo next Tuesday on Zoom.",
    "Dr. Robert Miller recommended checking blood pressure twice daily. Follow up in 3 months.",
    "Marcus (building supervisor) confirmed roof inspection is Monday 10am. Needs gate code."
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    viewModel: CloudCrmViewModel,
    onNavigateToDiff: () -> Unit,
    onOpenApiKeyDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val captureState by viewModel.captureState.collectAsState()
    val currentApiKey = remember { CloudCrmApplication.getApiKey(context) }
    val voiceRecorder = remember { VoiceRecorder(context) }
    val recorderState by voiceRecorder.recorderState.collectAsState()

    var inputMode by remember { mutableStateOf(CaptureInputMode.VOICE) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            voiceRecorder.startRecording(coroutineScope)
        } else {
            Toast.makeText(context, "Microphone permission is required for voice capture", Toast.LENGTH_SHORT).show()
        }
    }

    val auth = remember { try { FirebaseAuth.getInstance() } catch (e: Exception) { null } }
    var currentUser by remember { mutableStateOf(auth?.currentUser) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .imePadding()
    ) {
        // API Key Header Banner if missing
        if (currentApiKey.isBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = "API Key",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Demo Mode active. Tap to enter Gemini API Key.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onOpenApiKeyDialog) {
                        Text("Setup", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Google Sign-In Header Banner
        if (currentUser == null) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudUpload,
                        contentDescription = "Sign In",
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Sign in to enable Cloud Sync.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        val credentialManager = CredentialManager.create(context)
                        coroutineScope.launch {
                            try {
                                val clientId = context.getString(R.string.default_web_client_id)
                                val googleIdOption = GetGoogleIdOption.Builder()
                                    .setFilterByAuthorizedAccounts(false)
                                    .setServerClientId(clientId)
                                    .setAutoSelectEnabled(true)
                                    .build()

                                val request = GetCredentialRequest.Builder()
                                    .addCredentialOption(googleIdOption)
                                    .build()

                                val result = credentialManager.getCredential(
                                    request = request,
                                    context = context
                                )

                                val credential = result.credential
                                if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                                    val firebaseCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
                                    auth?.signInWithCredential(firebaseCredential)?.addOnCompleteListener { task ->
                                        if (task.isSuccessful) {
                                            currentUser = auth.currentUser
                                            Toast.makeText(context, "Signed in as ${currentUser?.displayName ?: "User"}!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Sign in failed: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Sign in error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Text("Sign In", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Header Title
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Quick Capture",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = if (inputMode == CaptureInputMode.VOICE) "Speak naturally • Gemini extracts CRM records" else "Type or paste conversational meeting notes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Mode Switcher Toggle: [ 🎙️ Voice Note (Default) ] | [ ✍️ Text Input ]
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier.padding(4.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                // Voice Tab (Default)
                Surface(
                    color = if (inputMode == CaptureInputMode.VOICE) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { inputMode = CaptureInputMode.VOICE }
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = if (inputMode == CaptureInputMode.VOICE) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Voice Note",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (inputMode == CaptureInputMode.VOICE) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Text Tab
                Surface(
                    color = if (inputMode == CaptureInputMode.TEXT) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { inputMode = CaptureInputMode.TEXT }
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.EditNote,
                            contentDescription = null,
                            tint = if (inputMode == CaptureInputMode.TEXT) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Text Input",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (inputMode == CaptureInputMode.TEXT) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Center Content Area (Fills entire remaining viewport!)
        if (inputMode == CaptureInputMode.VOICE) {
            // ================================================================
            // VOICE MODE: Big Circle in Center of Capture Area
            // ================================================================
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (!recorderState.isRecording) {
                        // Outer subtle glow ring
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(160.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                        ) {
                            // Big Center Circular Mic Button (124dp)
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(124.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .clickable {
                                        val hasPermission = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (hasPermission) {
                                            voiceRecorder.startRecording(coroutineScope)
                                        } else {
                                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Tap to Record Voice Note",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(60.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "Tap to Record Voice Note",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Speak naturally about your interactions.\nGemini AI transcribes and extracts entities automatically.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    } else {
                        // Active Recording State with Pulsing Effect
                        val infiniteTransition = rememberInfiniteTransition(label = "recordingPulse")
                        val pulseScale by infiniteTransition.animateFloat(
                            initialValue = 1f,
                            targetValue = 1.15f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(800, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "scale"
                        )

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(140.dp)
                                .scale(pulseScale)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .clickable {
                                    val audioBytes = voiceRecorder.stopRecording()
                                    if (audioBytes != null && audioBytes.isNotEmpty()) {
                                        viewModel.startStreamingAudioExtraction(audioBytes)
                                        onNavigateToDiff()
                                    }
                                }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(110.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.error),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = "Stop & Process",
                                    tint = MaterialTheme.colorScheme.onError,
                                    modifier = Modifier.size(54.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                        val mins = recorderState.durationSeconds / 60
                        val secs = recorderState.durationSeconds % 60
                        Text(
                            text = "🔴 Recording %02d:%02d".format(mins, secs),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tap the center button or 'Extract & Sync' when done speaking",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(24.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            OutlinedButton(
                                onClick = { voiceRecorder.cancelRecording() },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Cancel")
                            }
                            Button(
                                onClick = {
                                    val audioBytes = voiceRecorder.stopRecording()
                                    if (audioBytes != null && audioBytes.isNotEmpty()) {
                                        viewModel.startStreamingAudioExtraction(audioBytes)
                                        onNavigateToDiff()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Icon(imageVector = Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Extract & Sync", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Sample Voice Prompt Chips at Bottom
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Example Voice Scenarios:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SAMPLE_PROMPTS.forEachIndexed { idx, sample ->
                    SuggestionChip(
                        onClick = {
                            viewModel.onSelectSamplePrompt(sample)
                            inputMode = CaptureInputMode.TEXT
                        },
                        label = {
                            Text(
                                text = when (idx) {
                                0 -> "⚽ \"Met Dave last Friday...\""
                                1 -> "💼 \"Demo with Sarah yesterday...\""
                                2 -> "🩺 \"Dr. Miller checkup...\""
                                else -> "🏢 \"Roof inspection Monday...\""
                                },
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        border = null
                    )
                }
            }

        } else {
            // ================================================================
            // TEXT MODE: Full-Screen Note Canvas & Extract Button
            // ================================================================
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp)
                ) {
                    OutlinedTextField(
                        value = captureState.noteInput,
                        onValueChange = { viewModel.onNoteInputChanged(it) },
                        placeholder = {
                            Text(
                                text = PLACEHOLDER_NOTE,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    // Bottom Meta Row inside note canvas
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (captureState.noteInput.isNotEmpty()) {
                            TextButton(
                                onClick = { viewModel.clearCaptureInput() }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Clear", style = MaterialTheme.typography.labelSmall)
                            }
                        } else {
                            Spacer(modifier = Modifier.width(1.dp))
                        }

                        Text(
                            text = "${captureState.noteInput.length} chars",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Sample Prompt Suggestions
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Sample Notes:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SAMPLE_PROMPTS.forEachIndexed { idx, sample ->
                    SuggestionChip(
                        onClick = { viewModel.onSelectSamplePrompt(sample) },
                        label = {
                            Text(
                                text = when (idx) {
                                    0 -> "⚽ Soccer & Elevator"
                                    1 -> "💼 Demo with Sarah"
                                    2 -> "🩺 Dr. Miller Follow-up"
                                    else -> "🏢 Roof Inspection"
                                },
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        border = null
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val noteToExtract = captureState.noteInput.ifBlank { PLACEHOLDER_NOTE }
            val canExtract = !captureState.isExtracting

            // Primary Extract & Process Button
            Button(
                onClick = {
                    viewModel.startStreamingExtraction(noteToExtract)
                    onNavigateToDiff()
                },
                enabled = canExtract,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp)
            ) {
                if (captureState.isExtracting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.5.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Extracting...",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Psychology,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Extract & Process",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}
