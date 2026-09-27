package com.jarvis.tv

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioManager
import android.media.Image
import android.media.ImageReader
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Size
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Detector local de probable sueño para Javistv.
 * El vídeo nunca sale del Fire TV: solo se procesan postura/rostro localmente.
 * Cuando se confirma un posible sueño se emite un aviso local y, si existe,
 * un pulso al webhook de Homey configurado por el usuario.
 */
class SofaVisionService : Service() {
    private val prefs by lazy { getSharedPreferences("jarvis", MODE_PRIVATE) }
    private val busy = AtomicBoolean(false)

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private val poseDetector by lazy {
        PoseDetection.getClient(
            PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build()
        )
    }

    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setMinFaceSize(0.12f)
                .enableTracking()
                .build()
        )
    }

    private var lastAnalyzedAt = 0L
    private var candidateSince = 0L
    private var lastCandidateX = Float.NaN
    private var lastCandidateY = Float.NaN
    private var lastPulseAt = 0L
    private var lastFaceSeenAt = 0L
    private var lastEyesOpenAt = 0L
    private var closedEyeFrames = 0

    override fun onCreate() {
        super.onCreate()
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        startCameraThread()
        startAsForeground("Preparando webcam USB…")
        openBestCamera()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCameraThread() {
        cameraThread = HandlerThread("JavistvSofaVision").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    private fun notification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Javistv · detector de sueño",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Detección local de probable sueño en el sofá mediante webcam USB"
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle("Javistv · visión local")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startAsForeground(text: String) {
        val n = notification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateStatus(text: String) {
        prefs.edit().putString("sofaVisionStatus", text).apply()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    @SuppressLint("MissingPermission")
    private fun openBestCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            updateStatus("Permiso de cámara pendiente")
            stopSelf()
            return
        }

        val ids = runCatching { cameraManager.cameraIdList.toList() }.getOrElse { emptyList() }
        if (ids.isEmpty()) {
            updateStatus("No se detecta ninguna cámara")
            scheduleRetry()
            return
        }

        val external = ids.firstOrNull { id ->
            runCatching {
                val c = cameraManager.getCameraCharacteristics(id)
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_EXTERNAL ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                        c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ==
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL)
            }.getOrDefault(false)
        }

        val cameraId = external ?: ids.first()
        updateStatus(if (external != null) "Webcam USB detectada · IA local activa" else "Cámara detectada · IA local activa")

        runCatching {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createSession(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                    updateStatus("Webcam desconectada")
                    scheduleRetry()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                    updateStatus("Error de webcam ($error)")
                    scheduleRetry()
                }
            }, cameraHandler)
        }.onFailure {
            updateStatus("No se puede abrir la webcam: ${it.message ?: "error"}")
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        cameraHandler?.postDelayed({
            if (cameraDevice == null) openBestCamera()
        }, 10_000L)
    }

    private fun chooseAnalysisSize(cameraId: String): Size {
        val sizes = runCatching {
            cameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.toList()
                .orEmpty()
        }.getOrDefault(emptyList())

        return sizes
            .filter { it.width >= 480 && it.height >= 360 }
            .minByOrNull { it.width.toLong() * it.height.toLong() }
            ?: sizes.minByOrNull { it.width.toLong() * it.height.toLong() }
            ?: Size(640, 480)
    }

    private fun createSession(camera: CameraDevice) {
        val size = chooseAnalysisSize(camera.id)
        imageReader?.close()
        imageReader = ImageReader.newInstance(
            size.width,
            size.height,
            ImageFormat.YUV_420_888,
            2
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                analyze(image)
            }, cameraHandler)
        }

        val surface = imageReader!!.surface
        @Suppress("DEPRECATION")
        camera.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    runCatching {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        }.build()
                        session.setRepeatingRequest(request, null, cameraHandler)
                        updateStatus("Vigilando sofá · postura + rostro · sin grabar vídeo")
                    }.onFailure {
                        updateStatus("Error iniciando análisis: ${it.message ?: "error"}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    updateStatus("La webcam no permite análisis YUV")
                }
            },
            cameraHandler
        )
    }

    private fun analyze(image: Image) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalyzedAt < ANALYSIS_INTERVAL_MS || !busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastAnalyzedAt = now

        val input = runCatching { InputImage.fromMediaImage(image, 0) }.getOrElse {
            busy.set(false)
            image.close()
            return
        }

        val width = image.width
        val height = image.height
        val remaining = AtomicInteger(2)
        var pose: Pose? = null
        var faces: List<Face> = emptyList()

        fun finishOne() {
            if (remaining.decrementAndGet() != 0) return
            try {
                val detectedPose = pose
                if (detectedPose != null) evaluatePose(detectedPose, faces, width, height)
                else resetCandidate()
            } finally {
                image.close()
                busy.set(false)
            }
        }

        poseDetector.process(input)
            .addOnSuccessListener { pose = it }
            .addOnFailureListener { updateStatus("IA de postura: ${it.message ?: "error"}") }
            .addOnCompleteListener { finishOne() }

        faceDetector.process(input)
            .addOnSuccessListener { faces = it }
            .addOnFailureListener {
                // La postura sigue funcionando aunque el rostro no pueda analizarse.
            }
            .addOnCompleteListener { finishOne() }
    }

    private fun evaluatePose(pose: Pose, faces: List<Face>, width: Int, height: Int) {
        val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER) ?: run { resetCandidate(); return }
        val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER) ?: run { resetCandidate(); return }
        val leftHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP) ?: run { resetCandidate(); return }
        val rightHip = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP) ?: run { resetCandidate(); return }

        val points = listOf(leftShoulder, rightShoulder, leftHip, rightHip)
        if (points.any { it.inFrameLikelihood < MIN_LANDMARK_CONFIDENCE }) {
            resetCandidate()
            return
        }

        val shoulderX = (leftShoulder.position.x + rightShoulder.position.x) / 2f
        val shoulderY = (leftShoulder.position.y + rightShoulder.position.y) / 2f
        val hipX = (leftHip.position.x + rightHip.position.x) / 2f
        val hipY = (leftHip.position.y + rightHip.position.y) / 2f
        val centerX = ((shoulderX + hipX) / 2f) / width.toFloat()
        val centerY = ((shoulderY + hipY) / 2f) / height.toFloat()

        val torsoDx = abs(hipX - shoulderX)
        val torsoDy = abs(hipY - shoulderY)
        val lying = torsoDx / (torsoDy + 1f) >= LYING_RATIO

        val left = prefs.getFloat("sofaRoiLeft", 0.03f)
        val top = prefs.getFloat("sofaRoiTop", 0.10f)
        val right = prefs.getFloat("sofaRoiRight", 0.97f)
        val bottom = prefs.getFloat("sofaRoiBottom", 0.97f)
        val inSofaZone = centerX in left..right && centerY in top..bottom

        if (!lying || !inSofaZone) {
            resetCandidate()
            prefs.edit().putString(
                "sofaVisionStatus",
                if (!inSofaZone) "Persona fuera de la zona sofá" else "Persona detectada · no tumbada"
            ).apply()
            return
        }

        val now = SystemClock.elapsedRealtime()
        val moved = if (lastCandidateX.isNaN()) 0f else hypot(centerX - lastCandidateX, centerY - lastCandidateY)
        if (candidateSince == 0L || moved > MAX_NORMALIZED_MOVEMENT) {
            candidateSince = now
            closedEyeFrames = 0
        }
        lastCandidateX = centerX
        lastCandidateY = centerY

        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        var eyesClosedNow = false
        if (face != null) {
            lastFaceSeenAt = now
            val leftOpen = face.leftEyeOpenProbability
            val rightOpen = face.rightEyeOpenProbability
            if (leftOpen != null && rightOpen != null) {
                val averageOpen = (leftOpen + rightOpen) / 2f
                if (averageOpen <= CLOSED_EYE_PROBABILITY) {
                    closedEyeFrames++
                    eyesClosedNow = true
                } else if (averageOpen >= OPEN_EYE_PROBABILITY) {
                    closedEyeFrames = 0
                    lastEyesOpenAt = now
                }
            }
        }

        val stableFor = now - candidateSince
        val lastTvInteractionAt = prefs.getLong("lastTvInteractionElapsed", 0L)
        val userIdleFor = if (lastTvInteractionAt > 0L) now - lastTvInteractionAt else Long.MAX_VALUE

        val confirmedByEyes =
            stableFor >= MIN_STABLE_WITH_CLOSED_EYES_MS &&
            userIdleFor >= MIN_USER_IDLE_WITH_EYES_MS &&
            closedEyeFrames >= CLOSED_EYE_FRAMES_REQUIRED

        val noRecentFace = lastFaceSeenAt == 0L || now - lastFaceSeenAt >= FALLBACK_STABLE_MS
        val noRecentOpenEyes = lastEyesOpenAt == 0L || now - lastEyesOpenAt >= FALLBACK_STABLE_MS
        val confirmedByFallback =
            stableFor >= FALLBACK_STABLE_MS &&
            userIdleFor >= FALLBACK_STABLE_MS &&
            noRecentFace &&
            noRecentOpenEyes

        val eyeStatus = when {
            face == null -> "rostro no visible"
            eyesClosedNow -> "ojos cerrados ${closedEyeFrames}/${CLOSED_EYE_FRAMES_REQUIRED}"
            else -> "rostro visible"
        }

        prefs.edit()
            .putLong("sofaLastSeenAt", System.currentTimeMillis())
            .putString(
                "sofaVisionStatus",
                "Tumbado · inmóvil ${stableFor / 1000}s · $eyeStatus"
            )
            .apply()

        if ((confirmedByEyes || confirmedByFallback) && now - lastPulseAt >= ALERT_COOLDOWN_MS) {
            lastPulseAt = now
            prefs.edit()
                .putLong("sofaProbableSleepAt", System.currentTimeMillis())
                .putString(
                    "sofaVisionStatus",
                    if (confirmedByEyes) "Probable sueño · ojos cerrados confirmados" else "Probable sueño · inmovilidad prolongada"
                )
                .apply()
            localWakeAlert()
            sendHomeyPulse(if (confirmedByEyes) "eyes_closed" else "prolonged_stillness")
        }
    }

    private fun resetCandidate() {
        candidateSince = 0L
        lastCandidateX = Float.NaN
        lastCandidateY = Float.NaN
        closedEyeFrames = 0
    }

    private fun localWakeAlert() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 90)
            tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1800)
            cameraHandler?.postDelayed({ runCatching { tone.release() } }, 2200L)
        }
    }

    private fun sendHomeyPulse(reason: String) {
        val url = prefs.getString("homeySofaWebhook", "").orEmpty().trim()
        if (url.isBlank()) {
            updateStatus("Probable sueño detectado · falta configurar webhook de Homey")
            return
        }

        Thread {
            val result = runCatching {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6_000
                    readTimeout = 8_000
                    setRequestProperty("User-Agent", "Javistv-SofaVision/2")
                    setRequestProperty("X-Javistv-Event", "probable_sleep")
                    setRequestProperty("X-Javistv-Reason", reason)
                }
                val code = c.responseCode
                (if (code in 200..299) c.inputStream else c.errorStream)?.close()
                c.disconnect()
                code
            }

            val code = result.getOrNull()
            if (code != null && code in 200..299) {
                prefs.edit()
                    .putLong("sofaLastPulseAt", System.currentTimeMillis())
                    .putString("sofaVisionStatus", "Probable sueño · aviso enviado a Homey")
                    .apply()
            } else {
                prefs.edit().putString(
                    "sofaVisionStatus",
                    "Probable sueño · fallo Homey ${result.exceptionOrNull()?.message ?: result.getOrNull() ?: ""}"
                ).apply()
            }
        }.start()
    }

    override fun onDestroy() {
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.close() }
        runCatching { cameraDevice?.close() }
        runCatching { imageReader?.close() }
        runCatching { poseDetector.close() }
        runCatching { faceDetector.close() }
        cameraThread?.quitSafely()
        captureSession = null
        cameraDevice = null
        imageReader = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "javistv_sofa_vision"
        private const val NOTIFICATION_ID = 2307
        private const val ANALYSIS_INTERVAL_MS = 1_500L
        private const val MIN_STABLE_WITH_CLOSED_EYES_MS = 45_000L
        private const val MIN_USER_IDLE_WITH_EYES_MS = 60_000L
        private const val FALLBACK_STABLE_MS = 180_000L
        private const val ALERT_COOLDOWN_MS = 300_000L
        private const val CLOSED_EYE_FRAMES_REQUIRED = 4
        private const val CLOSED_EYE_PROBABILITY = 0.32f
        private const val OPEN_EYE_PROBABILITY = 0.58f
        private const val MAX_NORMALIZED_MOVEMENT = 0.045f
        private const val MIN_LANDMARK_CONFIDENCE = 0.55f
        private const val LYING_RATIO = 0.80f
    }
}
