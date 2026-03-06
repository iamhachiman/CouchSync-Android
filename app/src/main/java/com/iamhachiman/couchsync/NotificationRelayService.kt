package com.iamhachiman.couchsync

import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.PrintWriter
import java.net.Socket

object CouchSyncState {
    val isConnected = androidx.compose.runtime.mutableStateOf(false)
}

class NotificationRelayService : NotificationListenerService() {
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var ip: String? = null
    private var port: Int = 0
    private var code: String? = null
    private var pingJob: Job? = null
    private var isRunInBg: Boolean = false
    @Volatile private var isConnecting: Boolean = false
    @Volatile private var isListenerBound: Boolean = false

    companion object { private const val TAG = "CS_DEBUG" }

    override fun onCreate() {
        super.onCreate()
        loadPrefs()
        connectToServer()
        startPingLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "com.iamhachiman.couchsync.CONNECT") {
            loadPrefs()
            connectToServer()
            updateForegroundState()
        } else if (intent?.action == "com.iamhachiman.couchsync.DISCONNECT") {
            stopForeground(true)
            disconnect()
        } else if (intent?.action == "com.iamhachiman.couchsync.UPDATE_BG") {
            loadPrefs()
            updateForegroundState()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isListenerBound = true
        Log.d(TAG, "onListenerConnected: bound=true, socketOk=${socket?.isConnected == true && socket?.isClosed == false}")
        loadPrefs()
        if (socket?.isConnected == true && socket?.isClosed == false) {
            scope.launch { syncHistoricNotifications() }
        } else {
            connectToServer()
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "onListenerDisconnected: bound=false")
        isListenerBound = false
    }

    private fun loadPrefs() {
        val prefs = getSharedPreferences("CouchSyncPrefs", Context.MODE_PRIVATE)
        ip = prefs.getString("ip", null)
        port = prefs.getInt("port", 0)
        code = prefs.getString("code", null)
        isRunInBg = prefs.getBoolean("run_in_background", false)
    }

    private fun updateForegroundState() {
        if (isRunInBg && ip != null) {
            startPersistentForeground()
        } else {
            stopForeground(true)
        }
    }

    private fun startPersistentForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "couchsync_bg_channel",
                "CouchSync Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "couchsync_bg_channel")
        } else {
            Notification.Builder(this)
        }
            .setContentTitle("CouchSync Connected")
            .setContentText("Relaying notifications in the background...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(Notification.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= 29) { // Build.VERSION_CODES.Q
            startForeground(7531, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(7531, notification)
        }
    }

    private fun disconnect() {
        try {
            socket?.close()
        } catch (e: Exception) {}
        socket = null
        writer = null
        CouchSyncState.isConnected.value = false
    }

    private fun startPingLoop() {
        if (pingJob?.isActive == true) return
        pingJob = scope.launch {
            while(true) {
                delay(5000)
                if (socket != null && socket!!.isConnected && !socket!!.isClosed) {
                    try {
                        val json = JSONObject().apply { put("type", "ping") }
                        synchronized(this@NotificationRelayService) {
                            writer?.println(json.toString())
                            if (writer?.checkError() == true) {
                                disconnect()
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        disconnect()
                    }
                } else if (ip != null && port != 0) {
                    connectToServer()
                }
            }
        }
    }

    private fun connectToServer() {
        if (ip == null || port == 0 || code == null) {
            Log.d(TAG, "connectToServer: skipped (no prefs)")
            return
        }
        if (isConnecting) {
            Log.d(TAG, "connectToServer: skipped (already connecting)")
            return
        }

        scope.launch {
            if (socket?.isConnected == true && socket?.isClosed == false) {
                Log.d(TAG, "connectToServer: skipped (already connected)")
                return@launch
            }
            if (isConnecting) return@launch
            isConnecting = true
            Log.d(TAG, "connectToServer: attempting $ip:$port, listenerBound=$isListenerBound")
            try {
                val s = Socket()
                s.connect(java.net.InetSocketAddress(ip, port), 10_000)
                socket = s
                writer = PrintWriter(socket!!.getOutputStream(), true)
                val reader = java.io.BufferedReader(java.io.InputStreamReader(socket!!.getInputStream()))
                Log.d(TAG, "connectToServer: TCP connected, sending pair")

                // Send pair FIRST, before starting the reader loop
                val pairJson = JSONObject().apply {
                    put("type", "pair")
                    put("code", code)
                }
                synchronized(this@NotificationRelayService) {
                    writer?.println(pairJson.toString())
                }
                Log.d(TAG, "connectToServer: pair sent, syncing historic...")

                // Start reader AFTER pair is sent — avoids race with empty stream
                scope.launch {
                    try {
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isBlank()) continue  // skip empty lines, never pass to JSONObject
                            val inputJson = JSONObject(line)
                            val type = inputJson.optString("type")
                            when (type) {
                                "paired"    -> Log.d(TAG, "connectToServer: pair acknowledged by server")
                                "rejected"  -> { Log.w(TAG, "connectToServer: pair rejected — wrong code"); break }
                                "clear_all" -> cancelAllNotifications()
                                "clear"     -> {
                                    val inKey = inputJson.optString("key")
                                    if (!inKey.isNullOrEmpty()) cancelNotification(inKey)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        disconnect()
                        CouchSyncState.isConnected.value = false
                    }
                }

                // Run inline — no extra coroutine nesting
                syncHistoricNotifications()
                CouchSyncState.isConnected.value = true
                Log.d(TAG, "connectToServer: done")
            } catch (e: Exception) {
                Log.e(TAG, "connectToServer: failed — ${e.message}")
                e.printStackTrace()
                disconnect()
            } finally {
                isConnecting = false
            }
        }
    }

    // suspend — runs inline in the caller's coroutine, no extra nesting
    private suspend fun syncHistoricNotifications() {
        if (!isListenerBound) {
            Log.w(TAG, "syncHistoric: skipped — listener not bound")
            return
        }
        val socketOk = socket?.isConnected == true && socket?.isClosed == false
        if (!socketOk) {
            Log.w(TAG, "syncHistoric: skipped — socket not ready")
            return
        }
        val activeNots = try { activeNotifications } catch (e: Exception) {
            Log.e(TAG, "syncHistoric: activeNotifications threw — ${e.message}")
            null
        }
        Log.d(TAG, "syncHistoric: found ${activeNots?.size ?: 0} notifications")
        activeNots?.forEach { sbn -> sendJsonDirect(sbn, historic = true) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Called on main thread — must dispatch to IO
        scope.launch { sendJsonDirect(sbn, historic = false) }
    }

    // Sends a notification JSON directly (must be called from a coroutine/IO context)
    private fun sendJsonDirect(sbn: StatusBarNotification, historic: Boolean) {
        if (sbn.isOngoing) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text  = extras.getCharSequence("android.text")?.toString()  ?: ""
        if (title.isBlank() && text.isBlank()) return

        val socketOk = socket?.isConnected == true && socket?.isClosed == false
        if (!socketOk) {
            Log.w(TAG, "sendJsonDirect: socket not ready, dropping ${sbn.packageName}")
            return
        }
        try {
            val json = JSONObject().apply {
                put("type", "notification")
                put("app",  getAppName(sbn.packageName))
                put("title", title)
                put("text",  text)
                put("key",   sbn.key)
                if (historic) put("historic", "true")
            }
            Log.d(TAG, "sendJsonDirect: sending '${sbn.packageName}' historic=$historic")
            synchronized(this@NotificationRelayService) {
                writer?.println(json.toString())
                if (writer?.checkError() == true) disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendJsonDirect: exception — ${e.message}")
            e.printStackTrace()
            disconnect()
        }
    }

    @Deprecated("Use sendJsonDirect from a coroutine instead")
    private fun sendNotificationSafe(sbn: StatusBarNotification, historic: Boolean) {
        scope.launch { sendJsonDirect(sbn, historic) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        if (title.isBlank() && text.isBlank()) return

        scope.launch {
            if (socket == null || socket?.isConnected == false || socket?.isClosed == true) return@launch

            try {
                val json = JSONObject().apply {
                    val appName = getAppName(sbn.packageName)
                    put("type", "notification_removed")
                    put("app", appName)
                    put("title", title)
                    put("text", text)
                    put("key", sbn.key)
                }
                
                synchronized(this@NotificationRelayService) {
                    writer?.println(json.toString())
                    if (writer?.checkError() == true) {
                        disconnect()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                disconnect()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = applicationContext.packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            packageName
        }
    }
}
