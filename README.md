# CouchSync-Android 🛋️📲

This is the Android client for CouchSync. It's built to capture and send notifications over your local network directly to your Windows PC.

## 🚀 Vision
CouchSync-Android handles the "source" side of your connectivity:
- Capturing notifications in real-time.
- Securely relaying data to the Windows app without any middleman (Direct Local Connection).

## 🌟 Implemented Features

### 🔔 Notification Mirroring (Core)
*   **Listener Service:** Reliable background service using the `NotificationListenerService` API.
*   **Direct Local Connection:** Connects directly to the Windows client over the local router using a continuous TCP socket.
*   **QR Code Pairing:** Easily pair your phone with your Windows PC by scanning the QR code, or enter manual IP settings.
*   **Modern Interface:** Very sleek Dark Material 3 architecture matching modern standards.

## ✨ Upcoming Features

### 📷 Virtual Camera
*   Relay high-quality video from your Android's camera to your PC with low latency.

## 🏗️ Technical Details
- **Platform:** Android (Kotlin)
- **Networking:** Local PC discovery via QR Code and raw TCP connection for real-time pushing.
- **UI Framework:** Jetpack Compose

---

*Part of the [CouchSync](https://github.com/iamhachiman/CouchSync) project.*
