package com.iamhachiman.couchsync

import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.PrintWriter
import java.net.Socket

object CouchSyncState {
    val isConnected = androidx.compose.runtime.mutableStateOf(false)
}

class NotificationRelayService : NotificationListenerService() {
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    
    private var ip: String? = null
    private var port: Int = 0
    private var code: String? = null
    private var pingJob: Job? = null
    private var isRunInBg: Boolean = false

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
        loadPrefs()
        connectToServer()
        syncHistoricNotifications()
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
        if (ip == null || port == 0 || code == null) return

        scope.launch {
            try {
                if (socket?.isConnected == true && !socket!!.isClosed) {
                    return@launch
                }

                socket = Socket(ip, port)
                writer = PrintWriter(socket!!.getOutputStream(), true)
                
                // Send pairing request
                val pairJson = JSONObject().apply {
                    put("type", "pair")
                    put("code", code)
                }
                synchronized(this@NotificationRelayService) {
                    writer?.println(pairJson.toString())
                }

                syncHistoricNotifications()
                CouchSyncState.isConnected.value = true
            } catch (e: Exception) {
                e.printStackTrace()
                disconnect()
            }
        }
    }

    private fun syncHistoricNotifications() {
        scope.launch {
            try {
                if (socket == null || socket?.isConnected == false || socket?.isClosed == true) return@launch
                val activeNots = try { activeNotifications } catch (e: Exception) { null }
                if (activeNots != null) {
                    for (sbn in activeNots) {
                        sendNotificationSafe(sbn, historic = true)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        sendNotificationSafe(sbn, historic = false)
    }

    private fun sendNotificationSafe(sbn: StatusBarNotification, historic: Boolean) {
        if (sbn.isOngoing) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        if (title.isBlank() && text.isBlank()) return

        scope.launch {
            if (socket == null || socket?.isConnected == false || socket?.isClosed == true) {
                loadPrefs()
                connectToServer()
            }

            try {
                val json = JSONObject().apply {
                    val appName = getAppName(sbn.packageName)
                    put("type", "notification")
                    put("app", appName)
                    put("title", title)
                    put("text", text)
                    if (historic) {
                        put("historic", "true")
                    }
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
