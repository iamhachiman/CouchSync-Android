package com.iamhachiman.couchsync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Collections

object CouchSyncState {
    val isConnected = mutableStateOf(false)
    val isConnecting = mutableStateOf(false)
    val statusMessage = mutableStateOf("Scan your PC to pair")
}

private data class HandshakeConnection(
    val socket: Socket,
    val writer: PrintWriter,
    val reader: BufferedReader,
    val resolvedIp: String
)

private class PairRejectedException : Exception()

class NotificationRelayService : NotificationListenerService() {
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var readerJob: Job? = null
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var ip: String? = null
    private var port: Int = 0
    private var code: String? = null
    private var deviceName: String = "Windows PC"
    private var isRunInBg: Boolean = false
    private var reconnectAttempt: Int = 0
    @Volatile private var isConnectingSocket: Boolean = false
    @Volatile private var isListenerBound: Boolean = false
    @Volatile private var allowReconnect: Boolean = true

    companion object {
        private const val TAG = "CS_DEBUG"
        private const val CHANNEL_ID = "couchsync_bg_channel"
        private const val FOREGROUND_ID = 7531
        private const val CONNECT_TIMEOUT_MS = 2500
        private const val HANDSHAKE_TIMEOUT_MS = 3000
    }

    override fun onCreate() {
        super.onCreate()
        loadPrefs()
        updateForegroundState()
        connectToServer(force = true)
        startPingLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "com.iamhachiman.couchsync.CONNECT" -> {
                allowReconnect = true
                loadPrefs()
                updateForegroundState()
                connectToServer(force = true)
            }
            "com.iamhachiman.couchsync.DISCONNECT" -> {
                allowReconnect = false
                reconnectJob?.cancel()
                disconnect(manual = true)
                stopForeground(true)
            }
            "com.iamhachiman.couchsync.UPDATE_BG" -> {
                loadPrefs()
                updateForegroundState()
                if (allowReconnect) {
                    connectToServer(force = false)
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isListenerBound = true
        loadPrefs()
        if (isSocketReady()) {
            serviceScope.launch { syncHistoricNotifications() }
        } else {
            connectToServer(force = false)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isListenerBound = false
    }

    private fun loadPrefs() {
        val prefs = getSharedPreferences("CouchSyncPrefs", Context.MODE_PRIVATE)
        ip = prefs.getString("ip", null)
        port = prefs.getInt("port", 0)
        code = prefs.getString("code", null)
        deviceName = prefs.getString("device_name", "Windows PC").orEmpty().ifBlank { "Windows PC" }
        isRunInBg = prefs.getBoolean("run_in_background", false)
        if (ip.isNullOrBlank() || code.isNullOrBlank() || port == 0) {
            pushState(connected = false, connecting = false, status = "Scan your PC to pair")
        } else if (!isSocketReady() && !isConnectingSocket) {
            pushState(connected = false, connecting = false, status = "Waiting for $deviceName")
        }
    }

    private fun updateForegroundState() {
        if (isRunInBg && ip != null && hasPostNotificationPermission()) {
            startPersistentForeground()
        } else {
            stopForeground(true)
        }
    }

    private fun hasPostNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun startPersistentForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CouchSync Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
            .setContentTitle(if (isSocketReady()) "CouchSync connected" else "CouchSync reconnecting")
            .setContentText(statusMessageForForeground())
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(Notification.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FOREGROUND_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(FOREGROUND_ID, notification)
        }
    }

    private fun statusMessageForForeground(): String {
        return when {
            isSocketReady() -> "Relaying notifications for $deviceName"
            ip != null -> "Trying to reconnect to $deviceName"
            else -> "Waiting for pairing"
        }
    }

    private fun isSocketReady(): Boolean {
        return socket?.isConnected == true && socket?.isClosed == false
    }

    private fun connectToServer(force: Boolean) {
        if (ip.isNullOrBlank() || code.isNullOrBlank() || port == 0) {
            pushState(connected = false, connecting = false, status = "Scan your PC to pair")
            return
        }
        if (!force && (isSocketReady() || isConnectingSocket)) {
            return
        }

        serviceScope.launch {
            if (!force && (isSocketReady() || isConnectingSocket)) {
                return@launch
            }
            reconnectJob?.cancel()
            isConnectingSocket = true
            pushState(connected = false, connecting = true, status = "Connecting to $deviceName")
            try {
                closeSocket()
                val connection = connectKnownOrDiscover() ?: error("Server not reachable")
                socket = connection.socket
                writer = connection.writer
                reconnectAttempt = 0

                if (connection.resolvedIp != ip) {
                    persistResolvedIp(connection.resolvedIp)
                }

                pushState(connected = true, connecting = false, status = "Connected to $deviceName")
                updateForegroundState()
                startReaderLoop(connection.reader)
                syncHistoricNotifications()
            } catch (_: PairRejectedException) {
                allowReconnect = false
                disconnect(manual = false)
                pushState(connected = false, connecting = false, status = "Pairing expired. Scan the Windows QR code again")
            } catch (error: Exception) {
                Log.e(TAG, "connectToServer failed: ${error.message}", error)
                disconnect(manual = false)
                scheduleReconnect("Trying again soon")
            } finally {
                isConnectingSocket = false
            }
        }
    }

    private suspend fun connectKnownOrDiscover(): HandshakeConnection? {
        val currentIp = ip ?: return null
        attemptHandshake(currentIp, CONNECT_TIMEOUT_MS, HANDSHAKE_TIMEOUT_MS)?.let { return it }

        pushState(connected = false, connecting = true, status = "Searching your current network for $deviceName")
        return discoverServerOnCurrentSubnet()
    }

    private suspend fun discoverServerOnCurrentSubnet(): HandshakeConnection? {
        val localIp = getCurrentIpv4Address() ?: return null
        val prefix = localIp.substringBeforeLast('.', missingDelimiterValue = "")
        if (prefix.isBlank()) {
            return null
        }

        val preferredLastOctet = ip?.substringAfterLast('.', "")?.toIntOrNull()
        val localLastOctet = localIp.substringAfterLast('.', "")
        val baseCandidates = (1..254)
            .filter { it.toString() != localLastOctet }
            .filter { it != preferredLastOctet }

        val orderedCandidates = buildList {
            if (preferredLastOctet != null && preferredLastOctet in 1..254 && preferredLastOctet.toString() != localLastOctet) {
                add(preferredLastOctet)
            }
            addAll(baseCandidates)
        }.map { "$prefix.$it" }

        orderedCandidates.chunked(20).forEach { chunk ->
            val results = chunk.map { candidateIp ->
                serviceScope.async {
                    attemptHandshake(candidateIp, 350, 650)
                }
            }.awaitAll()

            results.firstOrNull()?.let { found ->
                pushState(connected = false, connecting = true, status = "Found $deviceName on ${found.resolvedIp}")
                return found
            }
        }

        return null
    }

    private fun attemptHandshake(targetIp: String, connectTimeoutMs: Int, readTimeoutMs: Int): HandshakeConnection? {
        val pairingCode = code ?: return null
        val attemptSocket = Socket().apply {
            tcpNoDelay = true
            keepAlive = true
            soTimeout = readTimeoutMs
        }

        try {
            attemptSocket.connect(InetSocketAddress(targetIp, port), connectTimeoutMs)
            val attemptWriter = PrintWriter(attemptSocket.getOutputStream(), true, StandardCharsets.UTF_8)
            val attemptReader = BufferedReader(InputStreamReader(attemptSocket.getInputStream(), StandardCharsets.UTF_8))
            val pairRequest = JSONObject().apply {
                put("type", "pair")
                put("code", pairingCode)
                put("deviceName", buildPhoneName())
            }
            attemptWriter.println(pairRequest.toString())
            if (attemptWriter.checkError()) {
                attemptSocket.close()
                return null
            }

            var line: String?
            do {
                line = attemptReader.readLine()
            } while (line != null && line.isBlank())

            if (line.isNullOrBlank()) {
                attemptSocket.close()
                return null
            }

            return when (JSONObject(line).optString("type")) {
                "paired" -> {
                    // Handshake can use a short timeout, but the live socket should block for data.
                    attemptSocket.soTimeout = 0
                    HandshakeConnection(attemptSocket, attemptWriter, attemptReader, targetIp)
                }
                "rejected" -> {
                    attemptSocket.close()
                    throw PairRejectedException()
                }
                else -> {
                    attemptSocket.close()
                    null
                }
            }
        } catch (_: PairRejectedException) {
            throw PairRejectedException()
        } catch (_: Exception) {
            try {
                attemptSocket.close()
            } catch (_: Exception) {
            }
            return null
        }
    }

    private fun persistResolvedIp(resolvedIp: String) {
        ip = resolvedIp
        getSharedPreferences("CouchSyncPrefs", Context.MODE_PRIVATE)
            .edit()
            .putString("ip", resolvedIp)
            .apply()
    }

    private fun startReaderLoop(reader: BufferedReader) {
        readerJob?.cancel()
        readerJob = serviceScope.launch {
            try {
                while (allowReconnect && isSocketReady()) {
                    val line = try {
                        reader.readLine()
                    } catch (_: SocketTimeoutException) {
                        continue
                    } ?: break
                    if (line.isBlank()) {
                        continue
                    }
                    val payload = JSONObject(line)
                    when (payload.optString("type")) {
                        "clear_all" -> cancelAllNotifications()
                        "clear" -> {
                            val key = payload.optString("key")
                            if (key.isNotBlank()) {
                                cancelNotification(key)
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "reader loop failed: ${error.message}", error)
            } finally {
                if (allowReconnect) {
                    disconnect(manual = false)
                    scheduleReconnect("Connection lost")
                }
            }
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (!allowReconnect || ip.isNullOrBlank() || code.isNullOrBlank() || port == 0) {
            return
        }
        if (reconnectJob?.isActive == true || isConnectingSocket) {
            return
        }
        reconnectAttempt += 1
        val delayMs = minOf(15_000L, 1_000L shl (reconnectAttempt - 1).coerceAtMost(4))
        reconnectJob = serviceScope.launch {
            pushState(connected = false, connecting = false, status = "$reason. Reconnecting in ${delayMs / 1000}s")
            delay(delayMs)
            connectToServer(force = false)
        }
    }

    private fun disconnect(manual: Boolean) {
        closeSocket()
        if (manual || ip.isNullOrBlank()) {
            pushState(connected = false, connecting = false, status = "Scan your PC to pair")
        } else {
            pushState(connected = false, connecting = false, status = "Waiting for $deviceName")
        }
        updateForegroundState()
    }

    private fun closeSocket(socketToClose: Socket? = socket) {
        readerJob?.cancel()
        readerJob = null
        try {
            socketToClose?.close()
        } catch (_: Exception) {
        }
        if (socketToClose == socket) {
            socket = null
            writer = null
        }
    }

    private fun startPingLoop() {
        if (pingJob?.isActive == true) {
            return
        }
        pingJob = serviceScope.launch {
            while (true) {
                delay(3_000)
                if (isSocketReady()) {
                    try {
                        synchronized(this@NotificationRelayService) {
                            writer?.println(JSONObject().put("type", "ping").toString())
                            if (writer?.checkError() == true) {
                                disconnect(manual = false)
                                scheduleReconnect("Trying again soon")
                            }
                        }
                    } catch (error: Exception) {
                        Log.e(TAG, "ping failed: ${error.message}", error)
                        disconnect(manual = false)
                        scheduleReconnect("Trying again soon")
                    }
                } else if (allowReconnect && ip != null && port != 0 && code != null) {
                    scheduleReconnect("Trying again soon")
                }
            }
        }
    }

    private suspend fun syncHistoricNotifications() {
        if (!isListenerBound || !isSocketReady()) {
            return
        }
        val active = try {
            activeNotifications
        } catch (error: Exception) {
            Log.e(TAG, "Unable to read active notifications: ${error.message}", error)
            null
        }
        active?.forEach { notification -> sendJsonDirect(notification, historic = true) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        serviceScope.launch {
            sendJsonDirect(sbn, historic = false)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) {
            return
        }

        serviceScope.launch {
            if (!isSocketReady()) {
                return@launch
            }
            try {
                val payload = JSONObject().apply {
                    put("type", "notification_removed")
                    put("app", getAppName(sbn.packageName))
                    put("title", title)
                    put("text", text)
                    put("key", sbn.key)
                }
                synchronized(this@NotificationRelayService) {
                    writer?.println(payload.toString())
                    if (writer?.checkError() == true) {
                        disconnect(manual = false)
                        scheduleReconnect("Trying again soon")
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "notification remove send failed: ${error.message}", error)
                disconnect(manual = false)
                scheduleReconnect("Trying again soon")
            }
        }
    }

    private fun sendJsonDirect(sbn: StatusBarNotification, historic: Boolean) {
        if (sbn.isOngoing || !isSocketReady()) {
            return
        }
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) {
            return
        }

        try {
            val payload = JSONObject().apply {
                put("type", "notification")
                put("app", getAppName(sbn.packageName))
                put("title", title)
                put("text", text)
                put("key", sbn.key)
                if (historic) {
                    put("historic", true)
                }
            }
            synchronized(this@NotificationRelayService) {
                writer?.println(payload.toString())
                if (writer?.checkError() == true) {
                    disconnect(manual = false)
                    scheduleReconnect("Trying again soon")
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "notification send failed: ${error.message}", error)
            disconnect(manual = false)
            scheduleReconnect("Trying again soon")
        }
    }

    override fun onDestroy() {
        pingJob?.cancel()
        reconnectJob?.cancel()
        readerJob?.cancel()
        closeSocket()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun getAppName(packageName: String): String {
        return try {
            val packageManager = applicationContext.packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    private fun pushState(connected: Boolean, connecting: Boolean, status: String) {
        CoroutineScope(Dispatchers.Main).launch {
            CouchSyncState.isConnected.value = connected
            CouchSyncState.isConnecting.value = connecting
            CouchSyncState.statusMessage.value = status
        }
    }

    private fun buildPhoneName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return listOf(manufacturer, model)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Android phone" }
    }

    private fun getCurrentIpv4Address(): String? {
        return try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { networkInterface -> Collections.list(networkInterface.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}