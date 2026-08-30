package com.cloudcrm.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cloudcrm.app.ui.CaptureScreen
import com.cloudcrm.app.ui.SemanticTimelineScreen
import com.cloudcrm.app.ui.StreamingDiffScreen
import com.cloudcrm.app.ui.navigation.BottomNavScreens
import com.cloudcrm.app.ui.navigation.Screen
import com.cloudcrm.app.ui.theme.CloudCrmTheme
import com.cloudcrm.app.viewmodel.CloudCrmViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: CloudCrmViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CloudCrmTheme {
                MainAppContainer(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun MainAppContainer(viewModel: CloudCrmViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val context = LocalContext.current

    var showApiKeyDialog by remember { mutableStateOf(false) }
    var apiKeyDraft by remember { mutableStateOf(CloudCrmApplication.getApiKey(context)) }

    val shouldShowBottomBar = currentRoute in listOf(Screen.Capture.route, Screen.SemanticTimeline.route)

    Scaffold(
        bottomBar = {
            if (shouldShowBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp
                ) {
                    BottomNavScreens.forEach { screen ->
                        val isSelected = currentRoute == screen.route
                        val icon = if (isSelected) screen.selectedIcon else screen.unselectedIcon

                        NavigationBarItem(
                            selected = isSelected,
                            onClick = {
                                if (currentRoute != screen.route) {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                if (icon != null) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = screen.title
                                    )
                                }
                            },
                            label = { Text(screen.title) }
                        )
                    }
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Capture.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Capture.route) {
                CaptureScreen(
                    viewModel = viewModel,
                    onNavigateToDiff = {
                        navController.navigate(Screen.StreamingDiff.route)
                    },
                    onOpenApiKeyDialog = {
                        apiKeyDraft = CloudCrmApplication.getApiKey(context)
                        showApiKeyDialog = true
                    }
                )
            }

            composable(Screen.StreamingDiff.route) {
                StreamingDiffScreen(
                    viewModel = viewModel,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onNavigateToTimeline = {
                        navController.navigate(Screen.SemanticTimeline.route) {
                            popUpTo(Screen.Capture.route) { inclusive = false }
                        }
                    }
                )
            }

            composable(Screen.SemanticTimeline.route) {
                SemanticTimelineScreen(
                    viewModel = viewModel,
                    onNavigateToCapture = {
                        navController.navigate(Screen.Capture.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                        }
                    }
                )
            }
        }
    }

    // Gemini API Key Configuration Dialog
    if (showApiKeyDialog) {
        AlertDialog(
            onDismissRequest = { showApiKeyDialog = false },
            title = {
                Text(
                    text = "Gemini API Configuration",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(
                        text = "Enter your Google Gemini API key to enable live paid-tier streaming extraction (gemini-3.7-flash) and text-embedding-004 vectors.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))
                    OutlinedTextField(
                        value = apiKeyDraft,
                        onValueChange = { apiKeyDraft = it },
                        label = { Text("API Key") },
                        singleLine = true,
                        placeholder = { Text("AIzaSy...") }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        CloudCrmApplication.setApiKey(context, apiKeyDraft.trim())
                        showApiKeyDialog = false
                    }
                ) {
                    Text("Save Key")
                }
            },
            dismissButton = {
                TextButton(onClick = { showApiKeyDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
