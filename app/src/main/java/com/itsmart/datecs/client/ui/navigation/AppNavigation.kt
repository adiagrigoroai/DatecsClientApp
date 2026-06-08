package com.itsmart.datecs.client.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Message
import androidx.compose.ui.graphics.vector.ImageVector

enum class Screen(val route: String, val label: String, val icon: ImageVector) {
    Connect("connect", "Conectare", Icons.Filled.Link),
    Status("status", "Status", Icons.Filled.MonitorHeart),
    Export("export", "Export", Icons.Filled.FileDownload),
    Messages("messages", "Mesaje", Icons.Filled.Message),
}
