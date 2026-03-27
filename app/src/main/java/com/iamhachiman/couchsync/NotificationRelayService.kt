package com.iamhachiman.couchsync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipboardManager
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
    val isClipboardSyncEnabled = mutableStateOf(false)
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
    private val sentNotificationSignatures = LinkedHashMap<String, String>()
    @Volatile private var needsHistoricResync: Boolean = false

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
        private const val MAX_NOTIFICATION_SIGNATURE_CACHE = 600

        const val ACTION_CONNECT = "com.iamhachiman.couchsync.CONNECT"
        const val ACTION_DISCONNECT = "com.iamhachiman.couchsync.DISCONNECT"
        const val ACTION_UPDATE_BG = "com.iamhachiman.couchsync.UPDATE_BG"
        const val ACTION_UPDATE_CLIPBOARD_SYNC = "com.iamhachiman.couchsync.UPDATE_CLIPBOARD_SYNC"
        const val ACTION_APP_FOREGROUND = "com.iamhachiman.couchsync.APP_FOREGROUND"
        const val ACTION_APP_BACKGROUND = "com.iamhachiman.couchsync.APP_BACKGROUND"
        const val ACTION_PUSH_CLIPBOARD_TEXT = "com.iamhachiman.couchsync.PUSH_CLIPBOARD_TEXT"

        const val EXTRA_CLIPBOARD_TEXT = "extra_clipboard_text"
    }

    private var clipboardManager: ClipboardManager? = null
    private var lastClipboardTextSentToPc: String? = null
    private var lastClipboardTextAppliedFromPc: String? = null
    private var pendingClipboardText: String? = null
    private var isHandlingIncomingClipboard = false
    private var isUiForeground = false
    private var isClipboardListenerRegistered = false

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        pushClipboardToPcFromPhoneClipboard()
    }

    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        loadPrefs()
        updateClipboardListenerRegistration()
        updateForegroundState()
        connectToServer(force = true)
        startPingLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                allowReconnect = true
                loadPrefs()
                updateForegroundState()
                connectToServer(force = true)
            }
            ACTION_DISCONNECT -> {
                allowReconnect = false
                reconnectJob?.cancel()
                disconnect(manual = true)
                stopForeground(true)
            }
            ACTION_UPDATE_BG -> {
                loadPrefs()
                updateForegroundState()
                if (allowReconnect) {
                    connectToServer(force = false)
                }
            }
            ACTION_UPDATE_CLIPBOARD_SYNC -> {
                loadPrefs()
            }
            ACTION_APP_FOREGROUND -> {
                isUiForeground = true
                updateClipboardListenerRegistration()
                pushClipboardToPcFromPhoneClipboard()
            }
            ACTION_APP_BACKGROUND -> {
                isUiForeground = false
                updateClipboardListenerRegistration()
            }
            ACTION_PUSH_CLIPBOARD_TEXT -> {
                val text = intent.getStringExtra(EXTRA_CLIPBOARD_TEXT).orEmpty()
                if (text.isNotBlank()) {
                    Log.d(TAG, "Received explicit clipboard push request")
                    sendClipboardToPc(text, force = true)
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
        CouchSyncState.isClipboardSyncEnabled.value = prefs.getBoolean("sync_clipboard", true)
        updateClipboardListenerRegistration()

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
                flushPendingClipboardToPc()
                syncHistoricNotifications()
                needsHistoricResync = false
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
                        "clipboard" -> {
                            if (CouchSyncState.isClipboardSyncEnabled.value) {
                                val text = payload.optString("text")
                                if (text.isNotBlank() && text != lastClipboardTextAppliedFromPc) {
                                    Log.d(TAG, "Received clipboard from PC (${text.length} chars)")
                                    handleIncomingClipboard(text)
                                }
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
        sentNotificationSignatures.remove(sbn.key)

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
        if (sbn.isOngoing) {
            return
        }

        val notificationKey = sbn.key
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) {
            return
        }

        val signature = "${sbn.packageName}|$title|$text"
        if (!historic && sentNotificationSignatures[notificationKey] == signature) {
            return
        }

        val payload = JSONObject().apply {
            put("type", "notification")
            put("app", getAppName(sbn.packageName))
            put("title", title)
            put("text", text)
            put("key", notificationKey)
            if (historic) {
                put("historic", true)
            }
        }

        if (!isSocketReady()) {
            if (!historic) {
                needsHistoricResync = true
                connectToServer(force = false)
            }
            return
        }

        try {
            synchronized(this@NotificationRelayService) {
                writer?.println(payload.toString())
                if (writer?.checkError() == true) {
                    disconnect(manual = false)
                    scheduleReconnect("Trying again soon")
                } else if (!historic) {
                    sentNotificationSignatures[notificationKey] = signature
                    trimNotificationSignatureCache()
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "notification send failed: ${error.message}", error)
            disconnect(manual = false)
            scheduleReconnect("Trying again soon")
        }
    }

    private fun trimNotificationSignatureCache() {
        while (sentNotificationSignatures.size > MAX_NOTIFICATION_SIGNATURE_CACHE) {
            val oldestKey = sentNotificationSignatures.entries.firstOrNull()?.key ?: return
            sentNotificationSignatures.remove(oldestKey)
        }
    }

    private fun handleIncomingClipboard(text: String) {
        serviceScope.launch(Dispatchers.Main) {
            try {
                isHandlingIncomingClipboard = true
                // Track both directions so this incoming write does not bounce back to PC.
                lastClipboardTextAppliedFromPc = text
                lastClipboardTextSentToPc = text
                val clip = android.content.ClipData.newPlainText("CouchSync", text)
                clipboardManager?.setPrimaryClip(clip)
                Log.d(TAG, "Applied clipboard from PC to phone")
            } catch (e: Exception) {
                Log.e(TAG, "Failed setting clipboard", e)
            } finally {
                delay(300)
                isHandlingIncomingClipboard = false
            }
        }
    }

    private fun updateClipboardListenerRegistration() {
        val shouldListen = CouchSyncState.isClipboardSyncEnabled.value && isUiForeground
        if (shouldListen == isClipboardListenerRegistered) {
            return
        }

        if (shouldListen) {
            clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
            isClipboardListenerRegistered = true
        } else {
            clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
            isClipboardListenerRegistered = false
        }
    }

    private fun pushClipboardToPcFromPhoneClipboard() {
        if (!CouchSyncState.isClipboardSyncEnabled.value) return
        if (isHandlingIncomingClipboard) return
        if (!isSocketReady()) return

        try {
            val clip = clipboardManager?.primaryClip ?: return
            if (clip.itemCount <= 0) return

            val text = clip.getItemAt(0).text?.toString()
            if (!text.isNullOrBlank()) {
                sendClipboardToPc(text, force = false)
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "Clipboard read blocked by Android while app is not in focus", error)
        } catch (error: Exception) {
            Log.e(TAG, "Clipboard sync read failed: ${error.message}", error)
        }
    }

    private fun sendClipboardToPc(text: String, force: Boolean) {
        if (!CouchSyncState.isClipboardSyncEnabled.value) return
        if (!force && text == lastClipboardTextSentToPc) return

        if (!isSocketReady()) {
            pendingClipboardText = text
            connectToServer(force = false)
            Log.d(TAG, "Queued clipboard for send after reconnect (${text.length} chars)")
            return
        }

        lastClipboardTextSentToPc = text
        val payload = JSONObject().apply {
            put("type", "clipboard")
            put("text", text)
        }

        synchronized(this@NotificationRelayService) {
            writer?.println(payload.toString())
            if (writer?.checkError() == true) {
                disconnect(manual = false)
                scheduleReconnect("Trying again soon")
            } else {
                pendingClipboardText = null
                Log.d(TAG, "Clipboard pushed to PC (${text.length} chars)")
            }
        }
    }

    private fun flushPendingClipboardToPc() {
        val pending = pendingClipboardText ?: return
        sendClipboardToPc(pending, force = true)
    }

    override fun onDestroy() {
        if (isClipboardListenerRegistered) {
            clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
            isClipboardListenerRegistered = false
        }
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