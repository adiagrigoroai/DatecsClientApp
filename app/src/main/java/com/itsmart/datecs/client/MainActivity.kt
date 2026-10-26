package com.itsmart.datecs.client

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.itsmart.datecs.client.ui.navigation.Screen
import com.itsmart.datecs.client.ui.screens.*
import com.itsmart.datecs.client.ui.theme.AppColors
import com.itsmart.datecs.client.ui.theme.DatecsServiceTheme
import com.itsmart.datecs.client.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    private val btPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {}

    /** ViewModel singleton — folosit de broadcast receiver pentru auto-execute. */
    private val viewModelInstance: MainViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(this)[MainViewModel::class.java]
    }

    /** Receiver pentru comenzi noi din CRM (broadcast emis de AgentPollingService). */
    private val newCommandReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action != com.itsmart.datecs.client.service.AgentPollingService.ACTION_NEW_COMMAND) return
            val id = intent.getStringExtra(com.itsmart.datecs.client.service.AgentPollingService.EXTRA_CMD_ID) ?: return
            val type = intent.getStringExtra(com.itsmart.datecs.client.service.AgentPollingService.EXTRA_CMD_TYPE) ?: ""
            val serial = intent.getStringExtra(com.itsmart.datecs.client.service.AgentPollingService.EXTRA_SERIAL) ?: ""
            val payload = intent.getStringExtra(com.itsmart.datecs.client.service.AgentPollingService.EXTRA_PAYLOAD_JSON) ?: "{}"
            android.util.Log.d("MainActivity", "BR primit: id=$id type=$type serial=$serial")
            viewModelInstance.onIncomingCrmCommand(id, type, serial, payload)
        }
    }

    /** True pe device-uri cu ecran landscape (BC500). False pe BC50 (portrait). */
    private fun isLandscapeFiscalPos(): Boolean {
        val m = (Build.MODEL ?: "") + "|" + (Build.DEVICE ?: "") + "|" + (Build.PRODUCT ?: "")
        // BC500 / BC-500 / BlueCash_500 / BlueCash-500
        return Regex("(?i)(BC[-_]?500|BlueCash[-_]?500)").containsMatchIn(m)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Orientare auto: BC500 are ecran landscape (7"), BC50 are portret (5").
        // Detectam dupa Build.MODEL/DEVICE (BlueCash_500 / BlueCash-500 vs BlueCash_50).
        requestedOrientation = if (isLandscapeFiscalPos()) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) needed.add(android.Manifest.permission.BLUETOOTH_CONNECT)
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) needed.add(android.Manifest.permission.BLUETOOTH_SCAN)
            // Permisiune notificari pe Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            if (needed.isNotEmpty()) btPermissionLauncher.launch(needed.toTypedArray())
        }

        // Initializez canalele de notificare (sunet + heads-up).
        com.itsmart.datecs.client.service.NotificationHelper.ensureChannels(this)

        // BlueCash: monitorizarea starii casei in fundal porneste odata cu aplicatia (si dupa boot)
        if (com.itsmart.datecs.client.device.InternalFiscalBridge.isAvailable()) {
            try {
                startForegroundService(android.content.Intent(this, com.itsmart.datecs.client.service.AgentPollingService::class.java)
                    .setAction(com.itsmart.datecs.client.service.AgentPollingService.ACTION_START))
            } catch (_: Throwable) {}
        }

        // Receiver-ul de broadcast e inregistrat la onCreate (NU onStart) ca sa
        // primeasca comenzi si cand Activity-ul e in background — service-ul foreground
        // tine procesul viu, deci instanta MainActivity persista pana la onDestroy.
        val filter = android.content.IntentFilter(
            com.itsmart.datecs.client.service.AgentPollingService.ACTION_NEW_COMMAND
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(newCommandReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(newCommandReceiver, filter)
        }

        setContent { DatecsServiceTheme { MainContent() } }
    }

    override fun onDestroy() {
        try { unregisterReceiver(newCommandReceiver) } catch (_: Throwable) {}
        super.onDestroy()
    }
}

@Composable
fun MainContent(viewModel: MainViewModel = viewModel()) {
    // Crash report dialog — BLOCKS the rest of the app until dismissed
    val context = LocalContext.current
    var crashReport by remember { mutableStateOf(AmefMonitorApp.getLastCrashReport(context)) }

    if (crashReport != null) {
        Box(
            modifier = Modifier.fillMaxSize().background(AppColors.Background),
            contentAlignment = Alignment.Center
        ) {
            Text("IT Smart Client", fontWeight = FontWeight.W800, fontSize = 16.sp,
                color = AppColors.TextTertiary, modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp))
        }
        AlertDialog(
            onDismissRequest = {
                AmefMonitorApp.clearCrashReport(context)
                crashReport = null
            },
            icon = { Icon(Icons.Filled.BugReport, null, tint = AppColors.Danger, modifier = Modifier.size(32.dp)) },
            title = { Text("Aplicatia a dat crash", fontWeight = FontWeight.W700) },
            text = {
                Column {
                    Text(
                        "La ultima utilizare aplicatia s-a inchis neasteptat. Doriti sa trimiteti raportul de eroare?",
                        fontSize = 13.sp, color = AppColors.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = Color(0xFFF5F5F5),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)
                    ) {
                        Text(
                            crashReport ?: "",
                            fontSize = 9.sp,
                            color = AppColors.TextSecondary,
                            modifier = Modifier.padding(8.dp).verticalScroll(rememberScrollState())
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val report = crashReport ?: return@Button
                        context.startActivity(AmefMonitorApp.createCrashEmailIntent(report))
                        AmefMonitorApp.clearCrashReport(context)
                        crashReport = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary)
                ) {
                    Icon(Icons.Filled.Email, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Trimite pe email")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    AmefMonitorApp.clearCrashReport(context)
                    crashReport = null
                }) {
                    Text("Inchide")
                }
            }
        )
        return  // DON'T render the rest of the app until crash report is dismissed
    }

    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val loginLoading by viewModel.loginLoading.collectAsStateWithLifecycle()
    val loginError by viewModel.loginError.collectAsStateWithLifecycle()

    if (!isLoggedIn) {
        LoginScreen(
            isLoading = loginLoading,
            errorMessage = loginError,
            onLogin = { user, pass -> viewModel.login(user, pass) }
        )
        return
    }

    // Logged in — main app
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val deviceState by viewModel.deviceState.collectAsStateWithLifecycle()
    val toastMessage by viewModel.toastMessage.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isConnecting by viewModel.isConnecting.collectAsStateWithLifecycle()
    val revizieRunning by viewModel.revizieRunning.collectAsStateWithLifecycle()
    val anafDownloading by viewModel.anafDownloading.collectAsStateWithLifecycle()
    val username by viewModel.username.collectAsStateWithLifecycle()
    val pendingRevizie by viewModel.pendingRevizie.collectAsStateWithLifecycle()
    val unreadCount by viewModel.unreadMessageCount.collectAsStateWithLifecycle()

    var currentScreen by remember { mutableStateOf(Screen.Connect) }

    // Auto-navigate to Status when connected
    LaunchedEffect(connected) {
        if (connected && currentScreen == Screen.Connect) currentScreen = Screen.Status
    }

    // ── Update check pe GitHub Releases (o singura data per pornire app) ──
    var updateInfo by remember { mutableStateOf<com.itsmart.datecs.client.service.AppUpdateService.ReleaseInfo?>(null) }
    var updateDownloading by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf("") }
    val updateScope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) {
        try {
            val res = com.itsmart.datecs.client.service.AppUpdateService.checkForUpdate()
            if (res.hasUpdate && res.latest != null) updateInfo = res.latest
        } catch (_: Throwable) {}
    }
    updateInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { if (!updateDownloading) updateInfo = null },
            icon = { Icon(Icons.Filled.SystemUpdate, null, tint = AppColors.Primary, modifier = Modifier.size(32.dp)) },
            title = { Text("Versiune noua disponibila", fontWeight = FontWeight.W700) },
            text = {
                Column {
                    Text("v${info.versionName}", fontSize = 14.sp, fontWeight = FontWeight.W700, color = AppColors.Primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (info.releaseNotes.isNotBlank()) {
                        Surface(color = Color(0xFFF5F5F5), shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                            Text(info.releaseNotes, fontSize = 11.sp, color = AppColors.TextSecondary,
                                modifier = Modifier.padding(8.dp).verticalScroll(rememberScrollState()))
                        }
                    }
                    if (updateDownloading) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(updateProgress, fontSize = 11.sp, color = AppColors.Warning, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !updateDownloading,
                    onClick = {
                        updateDownloading = true
                        updateProgress = "Verific permisiuni..."
                        if (com.itsmart.datecs.client.service.AppUpdateService.needsInstallPermission(context)) {
                            com.itsmart.datecs.client.service.AppUpdateService.openInstallPermissionSettings(context)
                            updateDownloading = false
                            updateProgress = ""
                            return@Button
                        }
                        updateScope.launch {
                            updateProgress = "Se descarca..."
                            val apk = com.itsmart.datecs.client.service.AppUpdateService.downloadApk(context, info) { dl, total ->
                                val pct = if (total > 0) (dl * 100 / total) else 0
                                updateProgress = "Descarcat $pct% (${dl / 1024 / 1024} / ${total / 1024 / 1024} MB)"
                            }
                            if (apk != null) {
                                updateProgress = "Lansez install..."
                                com.itsmart.datecs.client.service.AppUpdateService.installApk(context, apk)
                                updateInfo = null
                            } else {
                                updateProgress = "Eroare descarcare. Incearca manual."
                            }
                            updateDownloading = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary)
                ) {
                    Icon(Icons.Filled.Download, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (updateDownloading) "Se descarca..." else "Actualizeaza")
                }
            },
            dismissButton = {
                TextButton(enabled = !updateDownloading, onClick = { updateInfo = null }) { Text("Mai tarziu") }
            }
        )
    }

    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth().background(Color.White).statusBarsPadding()) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(painterResource(R.drawable.itsmart_mark), null, modifier = Modifier.size(34.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("IT Smart Client", color = AppColors.PrimaryDark, fontWeight = FontWeight.W800, fontSize = 17.sp)
                        if (connected) {
                            Text(
                                "${deviceState.devName} \u2022 ${deviceState.serialNumber}",
                                color = AppColors.TextSecondary, fontSize = 11.sp
                            )
                        } else {
                            Text("Niciun aparat conectat", color = AppColors.TextTertiary, fontSize = 11.sp)
                        }
                    }
                    // Pending revizie indicator
                    if (pendingRevizie != null) {
                        Box(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .background(AppColors.Warning, RoundedCornerShape(12.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("REVIZIE", fontSize = 9.sp, fontWeight = FontWeight.W800, color = Color.White)
                        }
                    }
                    // Connection dot
                    Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(if (connected) AppColors.Success else AppColors.Danger))
                    // Logout
                    IconButton(onClick = { viewModel.logout() }) {
                        Icon(Icons.Filled.Logout, "Logout", tint = AppColors.TextSecondary, modifier = Modifier.size(20.dp))
                    }
                }
                Box(Modifier.fillMaxWidth().height(3.dp).background(Brush.horizontalGradient(AppColors.BrandGradient)))
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                Screen.entries.forEach { screen ->
                    NavigationBarItem(
                        icon = {
                            if (screen == Screen.Messages && unreadCount > 0) {
                                BadgedBox(badge = {
                                    Badge { Text("$unreadCount") }
                                }) {
                                    Icon(screen.icon, null, modifier = Modifier.size(22.dp))
                                }
                            } else {
                                Icon(screen.icon, null, modifier = Modifier.size(22.dp))
                            }
                        },
                        label = { Text(screen.label, fontSize = 11.sp, fontWeight = FontWeight.W600) },
                        selected = currentScreen == screen,
                        onClick = { currentScreen = screen },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AppColors.Primary,
                            selectedTextColor = AppColors.Primary,
                            unselectedIconColor = AppColors.TextTertiary,
                            unselectedTextColor = AppColors.TextTertiary,
                            indicatorColor = AppColors.Primary.copy(alpha = 0.1f)
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize().padding(paddingValues).background(AppColors.Background)
        ) {
            // Not connected guard (except Connect screen)
            if (!connected && currentScreen != Screen.Connect) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Filled.LinkOff, null, modifier = Modifier.size(48.dp), tint = AppColors.TextTertiary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Conectati un aparat fiscal", fontWeight = FontWeight.W700, fontSize = 16.sp, color = AppColors.TextPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("USB, Bluetooth sau LAN", fontSize = 12.sp, color = AppColors.TextSecondary)
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = { currentScreen = Screen.Connect }, shape = RoundedCornerShape(10.dp)) {
                        Text("Conectare", fontWeight = FontWeight.W700)
                    }
                }
            } else {
                when (currentScreen) {
                    Screen.Connect -> ConnectScreen(viewModel, connected) { currentScreen = Screen.Status }
                    Screen.Status -> StatusScreen(viewModel, deviceState, isLoading)
                    Screen.Export -> ExportScreen(viewModel, deviceState)
                    Screen.Messages -> MessagesScreen(viewModel, deviceState)
                }
            }

            // Toast
            AnimatedVisibility(
                visible = toastMessage != null,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
            ) {
                toastMessage?.let { (msg, type) ->
                    Surface(
                        color = when (type) { "error" -> AppColors.Danger; "warning" -> AppColors.Warning; else -> AppColors.Success },
                        shape = RoundedCornerShape(10.dp), shadowElevation = 8.dp
                    ) {
                        Text(msg, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.W600,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                    }
                }
            }

            // Loading overlay
            val showOverlay = isConnecting || isLoading || revizieRunning || anafDownloading
            if (showOverlay) {
                val title = when {
                    isConnecting -> "Conectare..."
                    revizieRunning -> "Revizie in curs..."
                    anafDownloading -> "Download ANAF..."
                    else -> "Citire date..."
                }
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    Surface(shape = RoundedCornerShape(16.dp), color = Color.White, shadowElevation = 12.dp) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(40.dp, 32.dp)) {
                            com.itsmart.datecs.client.ui.components.SafeCircularProgress(modifier = Modifier.size(36.dp), color = AppColors.Primary, strokeWidth = 3.dp)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(title, fontWeight = FontWeight.W700, fontSize = 14.sp, color = AppColors.TextPrimary)
                            Text("Va rugam asteptati...", fontSize = 11.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}
