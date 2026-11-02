package com.itsmart.datecs.client.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Ecranele aplicatiei. Cele cu [tab] = true sunt in bara de jos; celelalte se deschid din ele (cu sageata inapoi)
 * si tin selectat tab-ul [parent].
 */
enum class Screen(val label: String, val icon: ImageVector, val tab: Boolean, val parent: Screen? = null) {
    Home("Acasa", Icons.Filled.Home, true),
    Devices("Aparate", Icons.Filled.PointOfSale, true),
    Files("Fisiere", Icons.Filled.Description, true),
    Settings("Setari", Icons.Filled.Settings, true),
    Export("Export fisiere XML", Icons.Filled.Description, false, Devices),
    Maintenance("Mentenanta AMEF", Icons.Filled.Settings, false, Devices),
    Messages("Mesaje", Icons.Filled.Home, false, Home);

    val tabScreen: Screen get() = parent ?: this
}
