package com.cloudcrm.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector? = null,
    val unselectedIcon: ImageVector? = null
) {
    data object Capture : Screen(
        route = "capture",
        title = "Quick Capture",
        selectedIcon = Icons.Filled.AddComment,
        unselectedIcon = Icons.Outlined.AddComment
    )

    data object StreamingDiff : Screen(
        route = "streaming_diff",
        title = "Review Extraction"
    )

    data object SemanticTimeline : Screen(
        route = "timeline",
        title = "Timeline Feed",
        selectedIcon = Icons.Filled.DynamicFeed,
        unselectedIcon = Icons.Outlined.DynamicFeed
    )
}

val BottomNavScreens = listOf(
    Screen.Capture,
    Screen.SemanticTimeline
)
