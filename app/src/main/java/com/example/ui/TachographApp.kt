package com.example.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.bluetooth.BluetoothPermissionManager
import com.example.data.bluetooth.ConnectionState
import com.example.ui.components.BluetoothScannerDialog
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class TachoNavTab(val title: String) {
    DASHBOARD("Панель"),
    TIMERS("Таймеры ЕС"),
    CARDS("Карты ЕС"),
    LOG("Журнал"),
    DEVICES("Тахограф ЕС")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TachographApp(
    viewModel: TachographViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var showBluetoothDialog by remember { mutableStateOf(false) }

    val connectionState by viewModel.connectionState.collectAsState()
    val connectedDeviceName by viewModel.connectedDeviceName.collectAsState()
    val lastErrorMessage by viewModel.lastErrorMessage.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val compliance by viewModel.compliance.collectAsState()
    val currentActivity by viewModel.currentActivity.collectAsState()
    val driverCard1 by viewModel.driverCard1.collectAsState()
    val driverCard2 by viewModel.driverCard2.collectAsState()
    val deviceInfo by viewModel.deviceInfo.collectAsState()
    val events by viewModel.events.collectAsState()
    val timelineSegments by viewModel.timelineSegments.collectAsState()
    val savedSessions by viewModel.savedSessions.collectAsState()
    val isDownloadingDdd by viewModel.isDownloadingDdd.collectAsState()
    val dddProgress by viewModel.dddDownloadProgress.collectAsState()
    val exportedReportText by viewModel.exportedReportText.collectAsState()

    // Real Stream Telemetry States
    val isRealDataActive by viewModel.isRealDataActive.collectAsState()
    val streamBytesReceived by viewModel.streamBytesReceived.collectAsState()
    val streamPacketsReceived by viewModel.streamPacketsReceived.collectAsState()
    val lastRawPacket by viewModel.lastRawPacket.collectAsState()
    val activeProtocolName by viewModel.activeProtocolName.collectAsState()
    val selectedProtocol by viewModel.selectedProtocol.collectAsState()
    val rawTerminalLogs by viewModel.rawTerminalLogs.collectAsState()

    // Listen for toast/snackbar messages from the engine
    LaunchedEffect(Unit) {
        viewModel.toastMessages.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    var hasBtPermission by remember { mutableStateOf(BluetoothPermissionManager.arePermissionsGranted(context)) }
    var isBtEnabled by remember { mutableStateOf(BluetoothPermissionManager.isBluetoothEnabled(context)) }

    // Launcher for Bluetooth Permissions
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        hasBtPermission = allGranted
        if (allGranted) {
            if (BluetoothPermissionManager.isBluetoothEnabled(context)) {
                viewModel.startDiscovery()
            } else {
                Toast.makeText(context, "Разрешения получены. Включите Bluetooth.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Требуется разрешение Bluetooth для поиска тахографов", Toast.LENGTH_SHORT).show()
        }
    }

    // Launcher to prompt system to enable Bluetooth
    val enableBtLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        isBtEnabled = BluetoothPermissionManager.isBluetoothEnabled(context)
        if (isBtEnabled && hasBtPermission) {
            viewModel.startDiscovery()
        }
    }

    fun requestBtPermissions() {
        val required = BluetoothPermissionManager.getRequiredPermissions()
        permissionLauncher.launch(required.toTypedArray())
    }

    fun requestEnableBt() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        try {
            enableBtLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Включите Bluetooth в настройках устройства", Toast.LENGTH_SHORT).show()
        }
    }

    if (showBluetoothDialog) {
        BluetoothScannerDialog(
            connectionState = connectionState,
            discoveredDevices = discoveredDevices,
            hasBluetoothPermission = hasBtPermission,
            isBluetoothEnabled = isBtEnabled,
            onRequestPermission = { requestBtPermissions() },
            onRequestEnableBluetooth = { requestEnableBt() },
            onStartScan = {
                if (!hasBtPermission) {
                    requestBtPermissions()
                } else if (!BluetoothPermissionManager.isBluetoothEnabled(context)) {
                    requestEnableBt()
                } else {
                    viewModel.startDiscovery()
                }
            },
            onCancelScan = { viewModel.cancelDiscovery() },
            onPairDevice = { device -> viewModel.pairDevice(device) },
            onConnectDevice = { device, transport -> viewModel.connectToDevice(device, transport) },
            onEnableDemoMode = { viewModel.enableDemoMode() },
            onDismiss = { showBluetoothDialog = false }
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
                            Icon(
                                imageVector = Icons.Default.LocalShipping,
                                contentDescription = null,
                                tint = Color(0xFF0F172A),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "ТАХОГРАФ ПРО",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = if (deviceInfo.regNumber.isNotBlank()) deviceInfo.regNumber else "Тахограф ЕС",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    // Bluetooth Status Pill Button
                    Row(
                        modifier = Modifier
                            .testTag("topbar_bluetooth_status_pill")
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF1E293B))
                            .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp))
                            .clickable { showBluetoothDialog = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isRealDataActive -> ColorDriving
                                        connectionState == ConnectionState.CONNECTED -> TachoAmber
                                        connectionState == ConnectionState.DEMO_MODE -> TachoCyan
                                        connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.SCANNING -> Color(0xFFF59E0B)
                                        else -> TachoRed
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when {
                                isRealDataActive -> "ОНЛАЙН ЕС"
                                connectionState == ConnectionState.CONNECTED -> "ПОДКЛЮЧЕНО"
                                connectionState == ConnectionState.DEMO_MODE -> "ДЕМО"
                                connectionState == ConnectionState.CONNECTING -> "СВЯЗЬ..."
                                connectionState == ConnectionState.SCANNING -> "ПОИСК"
                                else -> "ОТКЛ."
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                isRealDataActive -> ColorDriving
                                connectionState == ConnectionState.CONNECTED -> TachoAmber
                                connectionState == ConnectionState.DEMO_MODE -> TachoCyan
                                else -> Color(0xFFCBD5E1)
                            }
                        )
                    }

                    IconButton(
                        onClick = { showBluetoothDialog = true },
                        modifier = Modifier.testTag("topbar_bluetooth_icon_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bluetooth,
                            contentDescription = "Bluetooth настройки",
                            tint = TachoCyan
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .testTag("bottom_navigation_bar"),
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

                tabs.forEachIndexed { index, (tab, filledIcon, outlinedIcon) ->
                    val isSelected = selectedTabIndex == index
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { selectedTabIndex = index },
                        icon = {
                            Icon(
                                imageVector = if (isSelected) filledIcon else outlinedIcon,
                                contentDescription = tab.title
                            )
                        },
                        label = {
                            Text(
                                text = tab.title,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
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
            targetState = selectedTabIndex,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "ScreenTransition",
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) { tabIndex ->
            when (tabIndex) {
                0 -> DashboardScreen(
                    telemetry = telemetry,
                    compliance = compliance,
                    currentActivity = currentActivity,
                    driverCard1 = driverCard1,
                    connectionState = connectionState,
                    isRealDataActive = isRealDataActive,
                    activeProtocolName = activeProtocolName,
                    streamPacketsReceived = streamPacketsReceived,
                    streamBytesReceived = streamBytesReceived,
                    lastRawPacket = lastRawPacket,
                    onSelectActivity = { viewModel.setActivity(it) },
                    onPollTachograph = { viewModel.sendPollQuery() },
                    onOpenBluetoothDialog = { showBluetoothDialog = true }
                )
                1 -> TimersScreen(
                    compliance = compliance,
                    onSaveCurrentShift = {
                        viewModel.saveCurrentShift()
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Смена успешно сохранена в архив!")
                        }
                    }
                )
                2 -> DriverCardsScreen(
                    driverCard1 = driverCard1,
                    driverCard2 = driverCard2,
                    onToggleCard = { slot -> viewModel.toggleCard(slot) }
                )
                3 -> HistoryLogScreen(
                    timelineSegments = timelineSegments,
                    events = events,
                    savedSessions = savedSessions,
                    isDownloadingDdd = isDownloadingDdd,
                    dddProgress = dddProgress,
                    exportedReportText = exportedReportText,
                    onStartDddDownload = { viewModel.startDddDownload() },
                    onDismissReport = { viewModel.dismissReport() },
                    onDeleteSession = { id -> viewModel.deleteSession(id) }
                )
                4 -> DevicesScreen(
                    connectionState = connectionState,
                    connectedDeviceName = connectedDeviceName,
                    deviceInfo = deviceInfo,
                    telemetry = telemetry,
                    isRealDataActive = isRealDataActive,
                    activeProtocolName = activeProtocolName,
                    selectedProtocol = selectedProtocol,
                    streamBytesReceived = streamBytesReceived,
                    streamPacketsReceived = streamPacketsReceived,
                    lastRawPacket = lastRawPacket,
                    lastErrorMessage = lastErrorMessage,
                    rawTerminalLogs = rawTerminalLogs,
                    hasBluetoothPermission = hasBtPermission,
                    isBluetoothEnabled = isBtEnabled,
                    onRequestPermission = { requestBtPermissions() },
                    onRequestEnableBluetooth = { requestEnableBt() },
                    onSelectProtocol = { viewModel.setProtocol(it) },
                    onPollTachograph = { viewModel.sendPollQuery() },
                    onSendCustomCommand = { viewModel.sendCustomCommand(it) },
                    onClearTerminalLogs = { viewModel.clearTerminalLogs() },
                    onOpenBluetoothDialog = { showBluetoothDialog = true },
                    onDisconnect = { viewModel.disconnect() },
                    onEnableDemoMode = { viewModel.enableDemoMode() }
                )
            }
        }
    }
}
