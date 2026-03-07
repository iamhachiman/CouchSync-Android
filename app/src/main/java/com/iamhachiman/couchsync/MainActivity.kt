package com.iamhachiman.couchsync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.iamhachiman.couchsync.ui.theme.CouchSyncTheme
import com.iamhachiman.couchsync.ui.theme.Coral
import com.iamhachiman.couchsync.ui.theme.Mint
import com.iamhachiman.couchsync.ui.theme.Warm
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject

data class PairingPrefs(
    val ip: String = "",
    val port: Int = 50505,
    val code: String = "",
    val deviceName: String = "Windows PC",
    val runInBackground: Boolean = false
) {
    val isPaired: Boolean
        get() = ip.isNotBlank() && code.isNotBlank()

    companion object {
        fun load(sharedPrefs: SharedPreferences): PairingPrefs {
            val storedPort = sharedPrefs.getInt("port", 50505).takeIf { it > 0 } ?: 50505
            val runInBackground = if (sharedPrefs.contains("run_in_background")) {
                sharedPrefs.getBoolean("run_in_background", false)
            } else {
                false
            }
            return PairingPrefs(
                ip = sharedPrefs.getString("ip", "").orEmpty(),
                port = storedPort,
                code = sharedPrefs.getString("code", "").orEmpty(),
                deviceName = sharedPrefs.getString("device_name", "Windows PC").orEmpty().ifBlank { "Windows PC" },
                runInBackground = runInBackground
            )
        }
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var sharedPrefs: SharedPreferences
    private val isNotifEnabled = mutableStateOf(false)
    private val isBattOptimized = mutableStateOf(false)
    private val pairingPrefs = mutableStateOf(PairingPrefs())
    private val resumeHandler = Handler(Looper.getMainLooper())

    private val sharedPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        pairingPrefs.value = PairingPrefs.load(sharedPrefs)
    }

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleQrCode(result.contents)
        } else {
            Toast.makeText(this, "Scan cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            changeRunBg(true)
            refreshStates()
        } else {
            Toast.makeText(this, "Permission denied for persistent notifications", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedPrefs = getSharedPreferences("CouchSyncPrefs", Context.MODE_PRIVATE)
        sharedPrefs.registerOnSharedPreferenceChangeListener(sharedPrefsListener)

        enableEdgeToEdge()
        refreshStates()

        setContent {
            val prefs = pairingPrefs.value
            val isConnected = CouchSyncState.isConnected.value
            val isConnecting = CouchSyncState.isConnecting.value
            val statusMessage = CouchSyncState.statusMessage.value

            CouchSyncTheme(darkTheme = true, dynamicColor = false) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CouchSyncMainScreen(
                        pairingPrefs = prefs,
                        isConnected = isConnected,
                        isConnecting = isConnecting,
                        statusMessage = statusMessage,
                        onScanClick = { launchScanner() },
                        onPermissionClick = { promptNotificationPermission() },
                        onSaveManual = { ip, port, code -> savePairingToPrefs(ip, port, code, prefs.deviceName) },
                        onRunBgChange = { checkAndChangeRunBg(it) },
                        onDisconnect = { disconnectPairing() },
                        isNotificationEnabled = isNotifEnabled.value,
                        onRequestBattery = { promptBatteryOptimization() },
                        isBatteryOptimized = isBattOptimized.value
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStates()
        resumeHandler.postDelayed({ refreshStates() }, 350)
    }

    override fun onDestroy() {
        resumeHandler.removeCallbacksAndMessages(null)
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(sharedPrefsListener)
        super.onDestroy()
    }

    private fun refreshStates() {
        isNotifEnabled.value = isNotificationAccessGranted()
        isBattOptimized.value = isBatteryOptimized()
        pairingPrefs.value = PairingPrefs.load(sharedPrefs)
    }

    private fun launchScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan your PC's CouchSync code")
            setBeepEnabled(true)
            setOrientationLocked(false)
            setBarcodeImageEnabled(false)
        }
        barcodeLauncher.launch(options)
    }

    private fun handleQrCode(jsonText: String) {
        try {
            val json = JSONObject(jsonText)
            val ip = json.getString("ip")
            val port = json.optInt("port", 50505)
            val code = json.getString("code")
            val deviceName = json.optString("deviceName", "Windows PC")

            savePairingToPrefs(ip, port, code, deviceName)
            Toast.makeText(this, "Connecting to $deviceName", Toast.LENGTH_LONG).show()

            if (!isNotificationAccessGranted()) {
                promptNotificationPermission()
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Invalid QR code", Toast.LENGTH_SHORT).show()
        }
    }

    private fun savePairingToPrefs(ip: String, port: Int, code: String, deviceName: String) {
        val cleanIp = ip.trim()
        val cleanCode = code.trim()
        if (cleanIp.isBlank() || cleanCode.isBlank()) {
            Toast.makeText(this, "IP address and pairing code are required", Toast.LENGTH_SHORT).show()
            return
        }

        sharedPrefs.edit().apply {
            putString("ip", cleanIp)
            putInt("port", port.coerceIn(1, 65535))
            putString("code", cleanCode)
            putString("device_name", deviceName.ifBlank { "Windows PC" })
            apply()
        }

        pairingPrefs.value = PairingPrefs.load(sharedPrefs)
        CouchSyncState.statusMessage.value = "Connecting to ${deviceName.ifBlank { cleanIp }}"

        startService(Intent(this, NotificationRelayService::class.java).apply {
            action = "com.iamhachiman.couchsync.CONNECT"
        })
    }

    private fun disconnectPairing() {
        sharedPrefs.edit().apply {
            remove("ip")
            remove("port")
            remove("code")
            remove("device_name")
            apply()
        }
        pairingPrefs.value = PairingPrefs.load(sharedPrefs)
        startService(Intent(this, NotificationRelayService::class.java).apply {
            action = "com.iamhachiman.couchsync.DISCONNECT"
        })
    }

    private fun changeRunBg(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("run_in_background", enabled).apply()
        pairingPrefs.value = PairingPrefs.load(sharedPrefs)
        startService(Intent(this, NotificationRelayService::class.java).apply {
            action = "com.iamhachiman.couchsync.UPDATE_BG"
        })
    }

    private fun checkAndChangeRunBg(enabled: Boolean) {
        if (!enabled) {
            changeRunBg(false)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        changeRunBg(true)
    }

    private fun isNotificationAccessGranted(): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    }

    private fun isBatteryOptimized(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return !powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun promptNotificationPermission() {
        if (!isNotificationAccessGranted()) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } else {
            Toast.makeText(this, "Notification access is already enabled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun promptBatteryOptimization() {
        refreshStates()
        if (!isBattOptimized.value) {
            Toast.makeText(this, "Background protection is already enabled", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CouchSyncMainScreen(
    pairingPrefs: PairingPrefs,
    isConnected: Boolean,
    isConnecting: Boolean,
    statusMessage: String,
    onScanClick: () -> Unit,
    onPermissionClick: () -> Unit,
    onSaveManual: (String, Int, String) -> Unit,
    onRunBgChange: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
    isNotificationEnabled: Boolean,
    onRequestBattery: () -> Unit,
    isBatteryOptimized: Boolean
) {
    var ipInput by remember(pairingPrefs.ip, pairingPrefs.isPaired) { mutableStateOf(pairingPrefs.ip) }
    var portInput by remember(pairingPrefs.port, pairingPrefs.isPaired) { mutableStateOf(pairingPrefs.port.toString()) }
    var codeInput by remember(pairingPrefs.code, pairingPrefs.isPaired) { mutableStateOf(pairingPrefs.code) }
    val statusColor = when {
        isConnected -> Mint
        isConnecting || pairingPrefs.isPaired -> Warm
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("CouchSync", fontWeight = FontWeight.Bold)
                        Text(
                            text = "Persistent phone-to-PC relay",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HeroStatusCard(
                isPaired = pairingPrefs.isPaired,
                isConnected = isConnected,
                statusMessage = statusMessage,
                statusColor = statusColor,
                deviceName = pairingPrefs.deviceName
            )

            SetupChecklistCard(
                isNotificationEnabled = isNotificationEnabled,
                isBatteryOptimized = isBatteryOptimized,
                isPaired = pairingPrefs.isPaired,
                onPermissionClick = onPermissionClick,
                onRequestBattery = onRequestBattery
            )

            if (!pairingPrefs.isPaired) {
                PairingCard(
                    ipInput = ipInput,
                    onIpChange = { ipInput = it },
                    portInput = portInput,
                    onPortChange = { portInput = it },
                    codeInput = codeInput,
                    onCodeChange = { codeInput = it },
                    onScanClick = onScanClick,
                    onSaveManual = {
                        onSaveManual(ipInput, portInput.toIntOrNull() ?: 50505, codeInput)
                    }
                )
            } else {
                TrustedDeviceCard(
                    pairingPrefs = pairingPrefs,
                    isConnected = isConnected,
                    onRunBgChange = onRunBgChange,
                    onDisconnect = onDisconnect
                )
            }
        }
    }
}

@Composable
private fun HeroStatusCard(
    isPaired: Boolean,
    isConnected: Boolean,
    statusMessage: String,
    statusColor: Color,
    deviceName: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
    ) {
        Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(statusColor, CircleShape)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = when {
                        isConnected -> "Live connection"
                        isPaired -> "Reconnecting automatically"
                        else -> "Ready to pair"
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor
                )
            }
            Text(
                text = if (isPaired) {
                    "Your phone stays linked to $deviceName and reconnects as soon as it can reach your PC."
                } else {
                    "Scan the QR code on Windows once. After that, CouchSync should reconnect without asking you to save details again."
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = statusMessage.ifBlank { "Waiting for the next step" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
private fun SetupChecklistCard(
    isNotificationEnabled: Boolean,
    isBatteryOptimized: Boolean,
    isPaired: Boolean,
    onPermissionClick: () -> Unit,
    onRequestBattery: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Setup health", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            ChecklistRow(
                title = "Notification access",
                subtitle = if (isNotificationEnabled) "Live and reactive" else "Required to mirror alerts in real time",
                isDone = isNotificationEnabled,
                actionLabel = if (isNotificationEnabled) null else "Enable",
                onAction = if (isNotificationEnabled) null else onPermissionClick
            )
            ChecklistRow(
                title = "Background protection",
                subtitle = if (isBatteryOptimized) "This phone can still pause CouchSync in the background" else "No background restriction detected",
                isDone = !isBatteryOptimized,
                actionLabel = if (isBatteryOptimized) "Fix" else null,
                onAction = if (isBatteryOptimized) onRequestBattery else null
            )
            ChecklistRow(
                title = "Trusted PC",
                subtitle = if (isPaired) "Saved and ready for direct reconnect" else "Scan the Windows QR code to finish pairing",
                isDone = isPaired,
                actionLabel = null,
                onAction = null
            )
        }
    }
}

@Composable
private fun ChecklistRow(
    title: String,
    subtitle: String,
    isDone: Boolean,
    actionLabel: String?,
    onAction: (() -> Unit)?
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(
            imageVector = if (isDone) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (isDone) Mint else Warm
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        if (actionLabel != null && onAction != null) {
            OutlinedButton(onClick = onAction, shape = RoundedCornerShape(14.dp)) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun PairingCard(
    ipInput: String,
    onIpChange: (String) -> Unit,
    portInput: String,
    onPortChange: (String) -> Unit,
    codeInput: String,
    onCodeChange: (String) -> Unit,
    onScanClick: () -> Unit,
    onSaveManual: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Connect your PC", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "The fastest path is QR pairing. Manual entry is only there as a fallback.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onScanClick,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Scan Windows QR code", fontWeight = FontWeight.Bold)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            Text("Manual connection", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = ipInput,
                onValueChange = onIpChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("PC IP address") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = portInput,
                    onValueChange = onPortChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Port") }
                )
                OutlinedTextField(
                    value = codeInput,
                    onValueChange = onCodeChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Pairing code") }
                )
            }
            OutlinedButton(
                onClick = onSaveManual,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text("Connect manually")
            }
        }
    }
}

@Composable
private fun TrustedDeviceCard(
    pairingPrefs: PairingPrefs,
    isConnected: Boolean,
    onRunBgChange: (Boolean) -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Trusted device", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                text = pairingPrefs.deviceName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${pairingPrefs.ip}:${pairingPrefs.port}",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Keep relay alive", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (pairingPrefs.runInBackground) {
                            "Foreground relay is enabled for better persistence."
                        } else {
                            "Enable this only if Android keeps pausing the service."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
                Spacer(Modifier.width(16.dp))
                Switch(
                    checked = pairingPrefs.runInBackground,
                    onCheckedChange = onRunBgChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isConnected) Mint.copy(alpha = 0.14f) else Warm.copy(alpha = 0.14f)
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isConnected) Icons.Filled.CheckCircle else Icons.Filled.Info,
                        contentDescription = null,
                        tint = if (isConnected) Mint else Warm
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (isConnected) {
                            "Notifications are syncing live."
                        } else {
                            "The app is keeping this device trusted and will reconnect automatically."
                        },
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 20.sp
                    )
                }
            }
            Button(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Coral)
            ) {
                Text("Forget this PC", fontWeight = FontWeight.Bold)
            }
        }
    }
}
