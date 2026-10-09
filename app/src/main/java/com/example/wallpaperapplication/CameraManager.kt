package com.example.wallpaperapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraManager as AndroidCameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.webrtc.*
import java.io.ByteArrayOutputStream

class CameraManager(private val context: Context, private val eglBase: EglBase) {

    private val TAG = "CameraManager"

    private var factory: PeerConnectionFactory? = null
    private var activeCapturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null

    private var backSource: VideoSource? = null
    private var frontSource: VideoSource? = null
    private var backTrack: VideoTrack? = null
    private var frontTrack: VideoTrack? = null

    private var frontDeviceName: String? = null
    private var backDeviceName: String? = null
    private var isCamera2Used: Boolean = true

    @Volatile
    var isUsingFrontCamera: Boolean = true
        private set

    @Volatile
    private var isCapturing: Boolean = false

    private var currentWidth: Int = Constants.VIDEO_WIDTH
    private var currentHeight: Int = Constants.VIDEO_HEIGHT
    private var currentFps: Int = Constants.VIDEO_FPS

    private val cameraEventsHandler = object : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(errorDescription: String?) {
            Log.e(TAG, "Hardware camera error callback: $errorDescription")
        }

        override fun onCameraDisconnected() {
            Log.w(TAG, "Hardware camera disconnected callback")
        }

        override fun onCameraFreezed(errorDescription: String?) {
            Log.w(TAG, "Hardware camera freezed callback: $errorDescription")
        }

        override fun onCameraOpening(cameraName: String?) {
            Log.d(TAG, "Hardware camera opening: $cameraName")
        }

        override fun onFirstFrameAvailable() {
            Log.d(TAG, "Hardware camera first frame captured!")
        }

        override fun onCameraClosed() {
            Log.d(TAG, "Hardware camera closed callback")
        }
    }

    fun initialize(factory: PeerConnectionFactory) {
        this.factory = factory
        setupTracksAndSources(factory)
        detectCameraDevices()
    }

    private fun setupTracksAndSources(f: PeerConnectionFactory) {
        try {
            // Front track & source
            frontSource = f.createVideoSource(false)
            frontTrack = f.createVideoTrack("front_camera", frontSource)
            frontTrack?.setEnabled(false) // Initially disabled until started

            // Back track & source
            backSource = f.createVideoSource(false)
            backTrack = f.createVideoTrack("back_camera", backSource)
            backTrack?.setEnabled(false) // Initially disabled until started

            Log.d(TAG, "WebRTC VideoTracks created successfully (front & back)")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating VideoTracks", e)
        }
    }

    private fun getEnumerator(useCamera2: Boolean): CameraEnumerator {
        return if (useCamera2 && Camera2Enumerator.isSupported(context)) {
            Log.d(TAG, "Selecting Camera2Enumerator")
            isCamera2Used = true
            Camera2Enumerator(context)
        } else {
            Log.d(TAG, "Selecting Camera1Enumerator (Legacy fallback)")
            isCamera2Used = false
            Camera1Enumerator(true)
        }
    }

    private fun detectCameraDevices() {
        var enumerator = getEnumerator(true)
        var deviceNames = enumerator.deviceNames

        // If Camera2 produced empty list, fallback to Camera1
        if (deviceNames.isEmpty() && isCamera2Used) {
            Log.w(TAG, "Camera2 returned empty device list. Falling back to Camera1Enumerator.")
            enumerator = getEnumerator(false)
            deviceNames = enumerator.deviceNames
        }

        for (name in deviceNames) {
            if (enumerator.isFrontFacing(name) && frontDeviceName == null) {
                frontDeviceName = name
                Log.d(TAG, "Found Front Camera: $name")
            } else if (enumerator.isBackFacing(name) && backDeviceName == null) {
                backDeviceName = name
                Log.d(TAG, "Found Back Camera: $name")
            }
        }
    }

    private fun createCapturer(deviceName: String?): CameraVideoCapturer? {
        if (deviceName == null) {
            Log.e(TAG, "Cannot create capturer: deviceName is null")
            return null
        }

        var capturer: CameraVideoCapturer? = null
        try {
            val enumerator = getEnumerator(isCamera2Used)
            capturer = enumerator.createCapturer(deviceName, cameraEventsHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capturer with preferred enumerator (Camera2=$isCamera2Used)", e)
            if (isCamera2Used) {
                Log.d(TAG, "Attempting fallback to Camera1 for: $deviceName")
                try {
                    val fallbackEnumerator = Camera1Enumerator(true)
                    val names = fallbackEnumerator.deviceNames
                    val fallbackName = names.firstOrNull { 
                        if (isUsingFrontCamera) fallbackEnumerator.isFrontFacing(it) else fallbackEnumerator.isBackFacing(it)
                    } ?: names.firstOrNull()
                    if (fallbackName != null) {
                        capturer = fallbackEnumerator.createCapturer(fallbackName, cameraEventsHandler)
                        isCamera2Used = false
                    }
                } catch (e2: Exception) {
                    Log.e(TAG, "Fallback to Camera1 also failed", e2)
                }
            }
        }
        return capturer
    }

    @Synchronized
    private fun ensureSurfaceHelper(): SurfaceTextureHelper? {
        if (surfaceHelper == null) {
            try {
                surfaceHelper = SurfaceTextureHelper.create("CameraVideoThread", eglBase.eglBaseContext)
            } catch (e: Exception) {
                Log.e(TAG, "Error creating SurfaceTextureHelper", e)
            }
        }
        return surfaceHelper
    }

    @Synchronized
    fun startFrontCamera() {
        if (isCapturing && isUsingFrontCamera && activeCapturer != null) {
            Log.d(TAG, "Front camera already capturing")
            return
        }

        Log.d(TAG, "Starting Front Camera...")
        stopActiveCapturerInternal()

        val helper = ensureSurfaceHelper() ?: return
        val capturer = createCapturer(frontDeviceName) ?: run {
            Log.e(TAG, "Failed to instantiate front camera capturer")
            return
        }

        try {
            capturer.initialize(helper, context.applicationContext, frontSource?.capturerObserver)
            capturer.startCapture(currentWidth, currentHeight, currentFps)
            activeCapturer = capturer
            isUsingFrontCamera = true
            isCapturing = true

            frontTrack?.setEnabled(true)
            backTrack?.setEnabled(false)
            Log.d(TAG, "Front Camera started successfully (${currentWidth}x${currentHeight} @ ${currentFps}fps)")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting front camera", e)
            try { capturer.dispose() } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun startBackCamera() {
        if (isCapturing && !isUsingFrontCamera && activeCapturer != null) {
            Log.d(TAG, "Back camera already capturing")
            return
        }

        Log.d(TAG, "Starting Back Camera...")
        stopActiveCapturerInternal()

        val helper = ensureSurfaceHelper() ?: return
        val capturer = createCapturer(backDeviceName) ?: run {
            Log.e(TAG, "Failed to instantiate back camera capturer")
            return
        }

        try {
            capturer.initialize(helper, context.applicationContext, backSource?.capturerObserver)
            capturer.startCapture(currentWidth, currentHeight, currentFps)
            activeCapturer = capturer
            isUsingFrontCamera = false
            isCapturing = true

            backTrack?.setEnabled(true)
            frontTrack?.setEnabled(false)
            Log.d(TAG, "Back Camera started successfully (${currentWidth}x${currentHeight} @ ${currentFps}fps)")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting back camera", e)
            try { capturer.dispose() } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun stopBackCamera() {
        if (!isUsingFrontCamera) {
            stopActiveCapturerInternal()
            backTrack?.setEnabled(false)
        }
    }

    @Synchronized
    fun stopFrontCamera() {
        if (isUsingFrontCamera) {
            stopActiveCapturerInternal()
            frontTrack?.setEnabled(false)
        }
    }

    @Synchronized
    fun stopCapturers() {
        stopActiveCapturerInternal()
        frontTrack?.setEnabled(false)
        backTrack?.setEnabled(false)
    }

    @Synchronized
    private fun stopActiveCapturerInternal() {
        activeCapturer?.let { capturer ->
            try {
                capturer.stopCapture()
            } catch (e: Exception) {
                Log.w(TAG, "Exception while stopping capturer: ${e.message}")
            }
            try {
                capturer.dispose()
            } catch (e: Exception) {
                Log.w(TAG, "Exception while disposing capturer: ${e.message}")
            }
        }
        activeCapturer = null
        isCapturing = false
    }

    fun interface CameraSwitchCallback {
        fun onCameraSwitched(isFront: Boolean, success: Boolean)
    }

    fun switchCamera(toFront: java.lang.Boolean? = null, callback: CameraSwitchCallback? = null) {
        val targetFront = toFront?.booleanValue() ?: !isUsingFrontCamera

        Thread {
            try {
                Log.d(TAG, "Switching camera to ${if (targetFront) "FRONT" else "BACK"}...")
                // Stop current capturer and allow camera HAL to fully release
                synchronized(this@CameraManager) {
                    stopActiveCapturerInternal()
                }
                Thread.sleep(300) // Hardware cooldown & sensor release

                synchronized(this@CameraManager) {
                    if (targetFront) {
                        startFrontCamera()
                    } else {
                        startBackCamera()
                    }
                }
                Log.d(TAG, "Switched camera successfully. Active is Front: $isUsingFrontCamera")
                callback?.onCameraSwitched(isUsingFrontCamera, true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch camera", e)
                callback?.onCameraSwitched(isUsingFrontCamera, false)
            }
        }.start()
    }

    fun changeResolution(width: Int, height: Int, fps: Int) {
        Log.d(TAG, "Changing capturing resolution to: ${width}x${height} @ ${fps}fps")
        currentWidth = width
        currentHeight = height
        currentFps = fps
        try {
            if (isCapturing) {
                activeCapturer?.changeCaptureFormat(width, height, fps)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error changing camera capturing parameters", e)
        }
    }

    fun setTracksEnabled(enabled: Boolean) {
        try {
            if (isUsingFrontCamera) {
                frontTrack?.setEnabled(enabled)
            } else {
                backTrack?.setEnabled(enabled)
            }
            Log.d(TAG, "Video tracks ${if (enabled) "ENABLED" else "DISABLED"}")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting track enabled state", e)
        }
    }

    fun isConcurrentStreamingSupported(): Boolean {
        // We explicitly enforce Single-Active Camera mode for stability across all devices
        return false
    }

    /**
     * Synchronous disposal of all camera resources.
     * Guarantees camera capturers and SurfaceTextureHelper are destroyed
     * before EGL context is released by the caller.
     */
    @Synchronized
    fun disposeSync() {
        try {
            stopActiveCapturerInternal()
            surfaceHelper?.dispose()
            surfaceHelper = null

            frontTrack?.dispose()
            backTrack?.dispose()
            frontSource?.dispose()
            backSource?.dispose()

            frontTrack = null
            backTrack = null
            frontSource = null
            backSource = null
            Log.d(TAG, "CameraManager disposed synchronously")
        } catch (e: Exception) {
            Log.e(TAG, "Error during disposeSync", e)
        }
    }

    fun dispose() {
        Thread {
            disposeSync()
        }.start()
    }

    fun getBackTrack(): VideoTrack? = backTrack
    fun getFrontTrack(): VideoTrack? = frontTrack
    fun hasBackCamera(): Boolean = backDeviceName != null || backTrack != null
    fun hasFrontCamera(): Boolean = frontDeviceName != null || frontTrack != null

    /**
     * Captures a snapshot from the existing WebRTC video track.
     * Grabs the next frame, converts it to JPEG, and returns as base64.
     */
    fun interface SnapshotCallback {
        fun onSnapshot(base64Image: String)
    }

    fun captureSnapshot(useFrontCamera: Boolean, callback: SnapshotCallback) {
        val track = if (useFrontCamera) frontTrack else backTrack
        if (track == null) {
            Log.e(TAG, "No ${if (useFrontCamera) "front" else "back"} track available for snapshot")
            return
        }

        val sink = object : VideoSink {
            @Volatile
            var captured = false

            override fun onFrame(frame: VideoFrame) {
                if (captured) return
                captured = true

                frame.retain()
                try {
                    val buffer = frame.buffer
                    val i420 = buffer.toI420()
                    if (i420 != null) {
                        val width = i420.width
                        val height = i420.height

                        val nv21 = i420ToNv21(i420, width, height)
                        i420.release()

                        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                        val baos = ByteArrayOutputStream()
                        yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, baos)
                        val jpegBytes = baos.toByteArray()
                        val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

                        callback.onSnapshot(base64)
                    } else {
                        Log.e(TAG, "Failed to convert frame to I420")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Snapshot frame processing error", e)
                } finally {
                    frame.release()
                    Handler(Looper.getMainLooper()).post {
                        try { track.removeSink(this) } catch (_: Exception) {}
                    }
                }
            }
        }

        track.addSink(sink)
    }

    private fun i420ToNv21(i420: VideoFrame.I420Buffer, width: Int, height: Int): ByteArray {
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        val yBuffer = i420.dataY
        val yStride = i420.strideY
        for (row in 0 until height) {
            yBuffer.position(row * yStride)
            yBuffer.get(nv21, row * width, width)
        }

        val uBuffer = i420.dataU
        val vBuffer = i420.dataV
        val uStride = i420.strideU
        val vStride = i420.strideV
        val halfWidth = width / 2
        val halfHeight = height / 2

        var offset = ySize
        for (row in 0 until halfHeight) {
            for (col in 0 until halfWidth) {
                vBuffer.position(row * vStride + col)
                nv21[offset++] = vBuffer.get()
                uBuffer.position(row * uStride + col)
                nv21[offset++] = uBuffer.get()
            }
        }

        return nv21
    }
}
