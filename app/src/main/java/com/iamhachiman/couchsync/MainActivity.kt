package com.iamhachiman.couchsync

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var sharedPrefs: SharedPreferences
    private var isNotifEnabled = mutableStateOf(false)
    private var isBattOptimized = mutableStateOf(false)

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleQrCode(result.contents)
        } else {
            Toast.makeText(this, "Scan cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedPrefs = getSharedPreferences("CouchSyncPrefs", Context.MODE_PRIVATE)

        enableEdgeToEdge()
        refreshStates()
        
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CouchSyncMainScreen(
                        onScanClick = { launchScanner() },
                        onPermissionClick = { promptNotificationPermission() },
                        sharedPrefs = sharedPrefs,
                        onSaveManual = { ip, p, c -> savePairingToPrefs(ip, p, c) },
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
    }

    private fun refreshStates() {
        isNotifEnabled.value = isNotificationAccessGranted()
        isBattOptimized.value = isBatteryOptimized()
    }

    private fun launchScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan PC CouchSync QR Code")
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
            val port = json.getInt("port")
            val code = json.getString("code")

            savePairingToPrefs(ip, port, code)
            Toast.makeText(this, "Paired to $ip successfully!", Toast.LENGTH_LONG).show()

            if (!isNotificationAccessGranted()) {
                promptNotificationPermission()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid QR Code format", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private fun savePairingToPrefs(ip: String, port: Int, code: String) {
        sharedPrefs.edit().apply {
            putString("ip", ip)
            putInt("port", port)
            putString("code", code)
            apply()
        }
        
        val intent = Intent(this, NotificationRelayService::class.java).apply {
            action = "com.iamhachiman.couchsync.CONNECT"
        }
        startService(intent)
        recreate()
    }

    private fun disconnectPairing() {
        sharedPrefs.edit().clear().apply()
        // Stop the service from relaying
        val intent = Intent(this, NotificationRelayService::class.java).apply {
            action = "com.iamhachiman.couchsync.DISCONNECT"
        }
        startService(intent)
        recreate()
    }

    private fun isNotificationAccessGranted(): Boolean {
        val sets = NotificationManagerCompat.getEnabledListenerPackages(this)
        return sets.contains(packageName)
    }

    private fun isBatteryOptimized(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun promptNotificationPermission() {
        if (!isNotificationAccessGranted()) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } else {
            Toast.makeText(this, "Permission already granted!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun promptBatteryOptimization() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CouchSyncMainScreen(
    onScanClick: () -> Unit,
    onPermissionClick: () -> Unit,
    sharedPrefs: SharedPreferences,
    onSaveManual: (String, Int, String) -> Unit,
    onDisconnect: () -> Unit,
    isNotificationEnabled: Boolean,
    onRequestBattery: () -> Unit,
    isBatteryOptimized: Boolean
) {
    var ipStr by remember { mutableStateOf(sharedPrefs.getString("ip", "") ?: "") }
    var portStr by remember { mutableStateOf((sharedPrefs.getInt("port", 0).takeIf { it != 0 }?.toString() ?: "")) }
    var codeStr by remember { mutableStateOf(sharedPrefs.getString("code", "") ?: "") }
    val isConnected = CouchSyncState.isConnected.value

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CouchSync \uD83D\uDFE2", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            
            if (isBatteryOptimized) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text("Background Restricted", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        }
                        Text("Allow CouchSync to run in background to keep connection alive.", fontSize = 12.sp, modifier = Modifier.padding(top=4.dp, bottom=8.dp))
                        Button(onClick = onRequestBattery, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                            Text("FIX NOW")
                        }
                    }
                }
            }

            if (!isConnected) {
                // Not Connected - Show Pairing Forms
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Connection Status", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Not Paired", color = MaterialTheme.colorScheme.error)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onScanClick,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("SCAN QR CODE TO PAIR", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                Text("— OR ENTER MANUALLY —", color = MaterialTheme.colorScheme.onSurfaceVariant)

                OutlinedTextField(
                    value = ipStr,
                    onValueChange = { ipStr = it },
                    label = { Text("Windows PC IP Address") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(
                        value = portStr,
                        onValueChange = { portStr = it },
                        label = { Text("Port") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = codeStr,
                        onValueChange = { codeStr = it },
                        label = { Text("Pairing Code") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                Button(
                    onClick = {
                        val p = portStr.toIntOrNull() ?: 50505
                        onSaveManual(ipStr, p, codeStr)
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                ) {
                    Text("SAVE CONNECTION")
                }
            } else {
                // Connected View
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Connected", fontWeight = FontWeight.Black, fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Text("PC Interface: $ipStr:$portStr")
                        Text("Ready to relay notifications silently inside background.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier=Modifier.padding(top=4.dp))
                        
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = onDisconnect,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("DISCONNECT")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (!isNotificationEnabled) {
                OutlinedButton(
                    onClick = onPermissionClick,
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Enable Notification Access")
                }
                Text("Required for CouchSync to capture notifications.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Notification Access Granted", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}