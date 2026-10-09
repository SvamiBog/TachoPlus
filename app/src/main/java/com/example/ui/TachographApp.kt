package com.example.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.AvTimer
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.AvTimer
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collectLatest
import com.example.data.bluetooth.BluetoothPermissionManager
import com.example.data.link.LinkState
import com.example.ui.components.BluetoothScannerDialog
import com.example.ui.components.PinDialog
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.DevicesScreen
import com.example.ui.screens.DriverCardsScreen
import com.example.ui.screens.HistoryLogScreen
import com.example.ui.screens.TimersScreen
import com.example.ui.theme.ColorDriving
import com.example.ui.theme.TachoAmber
import com.example.ui.theme.TachoCyan
import com.example.ui.theme.TachoRed
import com.example.ui.viewmodel.TachographViewModel

enum class TachoNavTab(val title: String) {
    DASHBOARD("Панель"),
    TIMERS("Таймеры"),
    CARDS("Карты"),
    LOG("Журнал"),
    DEVICES("Адаптер")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TachographApp(viewModel: TachographViewModel = viewModel()) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showScanner by rememberSaveable { mutableStateOf(false) }

    val link by viewModel.link.collectAsStateWithLifecycle()
    val vehicle by viewModel.vehicle.collectAsStateWithLifecycle()
    val compliance by viewModel.compliance.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val bluetoothEnabled by viewModel.bluetoothEnabled.collectAsStateWithLifecycle()
    val terminal by viewModel.terminal.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val sessions by viewModel.savedSessions.collectAsStateWithLifecycle()
    val timeline by viewModel.todayTimeline.collectAsStateWithLifecycle()
    val report by viewModel.report.collectAsStateWithLifecycle()

    var hasBtPermission by remember { mutableStateOf(BluetoothPermissionManager.arePermissionsGranted(context)) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasBtPermission = BluetoothPermissionManager.arePermissionsGranted(context)
        viewModel.refreshBluetoothState()
    }

    LaunchedEffect(Unit) {
        // Only the latest message matters; older ones must not pile up behind it.
        viewModel.messages.collectLatest { snackbarHostState.showSnackbar(it) }
    }

    link.pinRequestFailures?.let { failures ->
        PinDialog(
            deviceName = link.deviceName,
            failedAttempts = failures,
            onSubmit = { viewModel.submitPin(it) },
            onCancel = viewModel::disconnect
        )
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(context, "Без уведомлений предупреждения о лимитах будут видны только в приложении", Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && BluetoothPermissionManager.needsNotificationPermission(context)) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasBtPermission = BluetoothPermissionManager.arePermissionsGranted(context)
        viewModel.refreshBluetoothState()
        if (hasBtPermission && BluetoothPermissionManager.isBluetoothEnabled(context)) viewModel.startScan()
        if (!hasBtPermission) Toast.makeText(context, "Без разрешения Bluetooth адаптер не найти", Toast.LENGTH_SHORT).show()
    }
    val enableBtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshBluetoothState()
        if (hasBtPermission && BluetoothPermissionManager.isBluetoothEnabled(context)) viewModel.startScan()
    }

    fun requestBtPermissions() = permissionLauncher.launch(BluetoothPermissionManager.getRequiredPermissions().toTypedArray())

    fun requestEnableBt() {
        try {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (e: Exception) {
            Toast.makeText(context, "Включите Bluetooth в настройках", Toast.LENGTH_SHORT).show()
        }
    }

    fun openScanner() {
        showScanner = true
        if (hasBtPermission && bluetoothEnabled && !isScanning) viewModel.startScan()
    }

    if (showScanner) {
        BluetoothScannerDialog(
            isScanning = isScanning,
            devices = devices,
            hasBluetoothPermission = hasBtPermission,
            isBluetoothEnabled = bluetoothEnabled,
            onRequestPermission = { requestBtPermissions() },
            onRequestEnableBluetooth = { requestEnableBt() },
            onStartScan = { viewModel.startScan() },
            onStopScan = { viewModel.stopScan() },
            onPairDevice = { viewModel.pair(it) },
            onConnectDevice = { device, transport ->
                viewModel.connect(device, transport)
                showScanner = false
            },
            onStartDemo = {
                viewModel.startDemo()
                showScanner = false
            },
            onDismiss = {
                viewModel.stopScan()
                showScanner = false
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(TachoCyan),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.LocalShipping, contentDescription = null, tint = Color(0xFF0F172A), modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("ТАХОГРАФ ПРО", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                            Text(
                                text = vehicle.registration ?: vehicle.vin ?: "Режим труда и отдыха",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    val (label, color) = when {
                        link.state == LinkState.DEMO -> "ДЕМО" to TachoCyan
                        link.state == LinkState.CONNECTED && link.messagesDecoded > 0 -> "ОНЛАЙН" to ColorDriving
                        link.state == LinkState.CONNECTED -> "ПОДКЛЮЧЕНО" to TachoAmber
                        link.state == LinkState.CONNECTING || link.state == LinkState.RECONNECTING -> "СВЯЗЬ…" to TachoAmber
                        link.state == LinkState.ERROR -> "ОШИБКА" to TachoRed
                        else -> "ОТКЛ." to Color(0xFF94A3B8)
                    }
                    Row(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .testTag("topbar_bluetooth_status_pill")
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF1E293B))
                            .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp))
                            .clickable { selectedTab = TachoNavTab.DEVICES.ordinal }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier.testTag("bottom_navigation_bar"),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                val tabs = listOf(
                    Triple(TachoNavTab.DASHBOARD, Icons.Default.Speed, Icons.Outlined.Speed),
                    Triple(TachoNavTab.TIMERS, Icons.Default.AvTimer, Icons.Outlined.AvTimer),
                    Triple(TachoNavTab.CARDS, Icons.Default.CreditCard, Icons.Outlined.CreditCard),
                    Triple(TachoNavTab.LOG, Icons.Default.Assessment, Icons.Outlined.Assessment),
                    Triple(TachoNavTab.DEVICES, Icons.Default.Bluetooth, Icons.Outlined.Bluetooth)
                )
                tabs.forEachIndexed { index, (tab, filled, outlined) ->
                    val selected = selectedTab == index
                    NavigationBarItem(
                        selected = selected,
                        onClick = { selectedTab = index },
                        icon = { Icon(if (selected) filled else outlined, contentDescription = tab.title) },
                        label = { Text(tab.title, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF0F172A),
                            indicatorColor = TachoCyan,
                            selectedTextColor = TachoCyan,
                            unselectedIconColor = Color(0xFF94A3B8),
                            unselectedTextColor = Color(0xFF94A3B8)
                        ),
                        modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}")
                    )
                }
            }
        }
    ) { innerPadding ->
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "ScreenTransition",
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) { tab ->
            when (TachoNavTab.entries[tab]) {
                TachoNavTab.DASHBOARD -> DashboardScreen(
                    link = link,
                    vehicle = vehicle,
                    compliance = compliance,
                    onSelectActivity = viewModel::setActivity,
                    onOpenBluetoothDialog = ::openScanner,
                    onDisconnect = viewModel::disconnect,
                    onStopDemo = viewModel::stopDemo
                )
                TachoNavTab.TIMERS -> TimersScreen(status = compliance, onSaveCurrentShift = viewModel::saveCurrentShift)
                TachoNavTab.CARDS -> DriverCardsScreen(vehicle = vehicle, link = link)
                TachoNavTab.LOG -> HistoryLogScreen(
                    timeline = timeline,
                    events = events,
                    savedSessions = sessions,
                    report = report,
                    onBuildReport = viewModel::buildReport,
                    onDismissReport = viewModel::dismissReport,
                    onDeleteSession = viewModel::deleteSession
                )
                TachoNavTab.DEVICES -> DevicesScreen(
                    link = link,
                    vehicle = vehicle,
                    terminal = terminal,
                    hasBluetoothPermission = hasBtPermission,
                    onRequestPermission = { requestBtPermissions() },
                    onSelectProtocol = viewModel::setProtocol,
                    onSendCommand = viewModel::sendCommand,
                    onClearTerminal = viewModel::clearTerminal,
                    onOpenBluetoothDialog = ::openScanner,
                    onDisconnect = viewModel::disconnect,
                    onStartDemo = viewModel::startDemo,
                    onStopDemo = viewModel::stopDemo
                )
            }
        }
    }
}
