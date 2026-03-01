# CouchSync-Android 🛋️📲

This is the Android client for CouchSync. It's built to capture and send notifications over your local network directly to your Windows PC.

## 🚀 Vision
CouchSync-Android handles the "source" side of your connectivity:
- Capturing notifications in real-time.
- Securely relaying data to the Windows app without any middleman (Direct Local Connection).

## ✨ Planned Features

### 🔔 Notification Mirroring (Core)
*   **Listener Service:** Reliable background service using the `NotificationListenerService` API.
*   **Direct Local Connection:** Connects directly to the Windows client over the local router.

### 📷 Virtual Camera (Upcoming)
*   Relay high-quality video from your Android's camera to your PC with low latency.

## 🏗️ Technical Details
- **Platform:** Android (Kotlin)
- **Networking:** Local PC discovery and raw TCP/UDP or WebSocket relay for P2P connection.

---

*Part of the [CouchSync](https://github.com/iamhachiman/CouchSync) project.*
