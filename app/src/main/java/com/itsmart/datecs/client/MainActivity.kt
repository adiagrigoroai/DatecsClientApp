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
import com.itsmart.datecs.client.ui.components.AppHeader
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

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleOpenIntent(intent)
    }

    /** Notificarea de comanda CRM (revizie) deschide ecranul Mesaje. */
    private fun handleOpenIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(com.itsmart.datecs.client.service.NotificationHelper.EXTRA_OPEN_MESSAGES, false) == true) {
            viewModelInstance.openMessagesRequest.value = true
            intent.removeExtra(com.itsmart.datecs.client.service.NotificationHelper.EXTRA_OPEN_MESSAGES)
        }
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

        handleOpenIntent(intent)
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

    val account by viewModel.account.collectAsStateWithLifecycle()
    val enrollLoading by viewModel.enrollLoading.collectAsStateWithLifecycle()
    val enrollError by viewModel.enrollError.collectAsStateWithLifecycle()
    var showEnroll by remember { mutableStateOf(false) }

    if (!isLoggedIn) {
        if (showEnroll) EnrollScreen(enrollLoading, enrollError, onEnroll = { c, e, p -> viewModel.enroll(c, e, p) }, onBack = { showEnroll = false })
        else LoginScreen(
            isLoading = loginLoading,
            errorMessage = loginError,
            onLogin = { user, pass -> viewModel.login(user, pass) },
            onEnroll = { showEnroll = true },
            onPairCode = { viewModel.loginWithPairCode(it) }
        )
        return
    }

    // aparat inrolat din aplicatie: conectarea la casa si exportul doar cu contul activ (email confirmat + plata)
    val accountNotice by viewModel.accountNotice.collectAsStateWithLifecycle()
    val pending = account
    if (pending != null && !pending.active) {
        AccountStatusScreen(pending, accountNotice, onRefresh = { viewModel.refreshAccount() }, onResend = { viewModel.resendConfirmation() },
            onLeave = { showEnroll = false; viewModel.leaveEnrollment() })
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

    var currentScreen by remember { mutableStateOf(Screen.Home) }
    // tableta / POS all-in-one (10", 12", 15" wide): meniu lateral si continut pe doua coloane
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    // ecranele care cer aparat conectat revin la Aparate daca legatura se pierde
    LaunchedEffect(connected) {
        if (!connected && currentScreen in setOf(Screen.Export, Screen.Maintenance)) currentScreen = Screen.Devices
    }
    val openMessages by viewModel.openMessagesRequest.collectAsStateWithLifecycle()
    LaunchedEffect(openMessages) {
        if (openMessages) { currentScreen = Screen.Messages; viewModel.openMessagesRequest.value = false }
    }
    androidx.activity.compose.BackHandler(enabled = currentScreen != Screen.Home) {
        currentScreen = if (currentScreen.tab) Screen.Home else currentScreen.tabScreen
    }

    // ── Update check pe GitHub Releases (o singura data per pornire app) ──
    var updateInfo by remember { mutableStateOf<com.itsmart.datecs.client.service.AppUpdateService.ReleaseInfo?>(null) }
    var updateDownloading by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf("") }
    val updateScope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) {
        try {
            val res = com.itsmart.datecs.client.service.AppUpdateService.checkForUpdate()
            val info = res.latest
            if (res.hasUpdate && info != null) {
                updateInfo = info
                if (!com.itsmart.datecs.client.service.AppUpdateService.needsInstallPermission(context)) {
                    updateDownloading = true
                    updateProgress = "Se descarca automat..."
                    val apk = com.itsmart.datecs.client.service.AppUpdateService.downloadApk(context, info) { dl, total ->
                        updateProgress = "Descarcat ${if (total > 0) dl * 100 / total else 0}%"
                    }
                    updateDownloading = false
                    if (apk != null) { com.itsmart.datecs.client.service.AppUpdateService.installApk(context, apk); updateInfo = null }
                    else updateProgress = "Descarcarea automata a esuat. Apasati Actualizeaza."
                }
            }
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

    val open: (Screen) -> Unit = { currentScreen = it }
    val header: @Composable () -> Unit = {
        if (currentScreen == Screen.Home) {
            AppHeader("IT Smart Client", "Conectare · Export XML · Mentenanta AMEF",
                leading = { Icon(Icons.Filled.CloudUpload, null, tint = Color.White, modifier = Modifier.size(38.dp)) }) {
                if (pendingRevizie != null) {
                    Text("REVIZIE", fontSize = 10.sp, fontWeight = FontWeight.W800, color = Color.White,
                        modifier = Modifier.background(AppColors.Warning, RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
                }
                IconButton(onClick = { currentScreen = Screen.Messages }) {
                    BadgedBox(badge = { if (unreadCount > 0) Badge { Text("$unreadCount") } }) {
                        Icon(Icons.Filled.Notifications, "Mesaje", tint = Color.White)
                    }
                }
            }
        } else {
            val title = when (currentScreen) {
                Screen.Devices -> if (connected) "Aparat conectat" else "Conecteaza aparatul"
                Screen.Files -> "Fisiere XML"
                else -> currentScreen.label
            }
            val sub = if (connected) "${deviceState.devName} · ${deviceState.serialNumber}" else null
            AppHeader(title, sub, onBack = if (wide && currentScreen.tab) null else ({ currentScreen = if (currentScreen.tab) Screen.Home else currentScreen.tabScreen }))
        }
    }
    val tabs = Screen.entries.filter { it.tab }
    val navColors = NavigationBarItemDefaults.colors(selectedIconColor = Color.White, selectedTextColor = AppColors.Primary,
        unselectedIconColor = AppColors.TextSecondary, unselectedTextColor = AppColors.TextSecondary, indicatorColor = AppColors.Primary)

    Scaffold(
        topBar = header,
        bottomBar = {
            if (!wide) NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                tabs.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, null, modifier = Modifier.size(24.dp)) },
                        label = { Text(screen.label, fontSize = 12.sp, fontWeight = FontWeight.W700) },
                        selected = currentScreen.tabScreen == screen, onClick = { currentScreen = screen }, colors = navColors)
                }
            }
        }
    ) { paddingValues ->
        Row(Modifier.fillMaxSize().padding(paddingValues).background(AppColors.Background)) {
            if (wide) NavigationRail(containerColor = Color.White, modifier = Modifier.fillMaxHeight().padding(top = 8.dp)) {
                tabs.forEach { screen ->
                    NavigationRailItem(
                        icon = { Icon(screen.icon, null, modifier = Modifier.size(26.dp)) },
                        label = { Text(screen.label, fontSize = 12.sp, fontWeight = FontWeight.W700) },
                        selected = currentScreen.tabScreen == screen, onClick = { currentScreen = screen },
                        colors = NavigationRailItemDefaults.colors(selectedIconColor = Color.White, selectedTextColor = AppColors.Primary,
                            unselectedIconColor = AppColors.TextSecondary, unselectedTextColor = AppColors.TextSecondary,
                            indicatorColor = AppColors.Primary),
                        modifier = Modifier.padding(vertical = 6.dp))
                }
            }
        Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            // pe ecranele foarte late (15") continutul nu se intinde peste ~1100 dp
            Box(Modifier.fillMaxSize().widthIn(max = 1100.dp)) {
                when (currentScreen) {
                    Screen.Home -> HomeScreen(viewModel, connected, wide, open)
                    Screen.Devices -> DevicesScreen(viewModel, connected, wide, open)
                    Screen.Files -> FilesScreen(viewModel, wide, open)
                    Screen.Settings -> SettingsScreen(viewModel, wide, open)
                    Screen.Export -> ExportScreen(viewModel, deviceState)
                    Screen.Maintenance -> StatusScreen(viewModel, deviceState, isLoading)
                    Screen.Messages -> MessagesScreen(viewModel, deviceState)
                }
            }

            // Toast
            androidx.compose.animation.AnimatedVisibility(
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
}
