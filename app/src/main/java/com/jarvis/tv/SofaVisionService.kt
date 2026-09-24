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
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Size
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Local-only sofa presence detector for Jarvis TV.
 * Frames never leave Fire TV. Only a Homey presence pulse is sent.
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

    private val detector by lazy {
        PoseDetection.getClient(
            PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build()
        )
    }

    private var lastAnalyzedAt = 0L
    private var candidateSince = 0L
    private var lastCandidateX = Float.NaN
    private var lastCandidateY = Float.NaN
    private var lastPulseAt = 0L

    override fun onCreate() {
        super.onCreate()
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        startCameraThread()
        startAsForeground("Preparando webcam USB…")
        openBestCamera()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCameraThread() {
        cameraThread = HandlerThread("JarvisSofaVision").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    private fun notification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Jarvis · detector sofá",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Detección local de presencia en el sofá mediante la webcam USB"
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle("Jarvis · visión local")
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
                        updateStatus("Vigilando sofá localmente · sin grabar vídeo")
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

        detector.process(input)
            .addOnSuccessListener { pose -> evaluatePose(pose, image.width, image.height) }
            .addOnFailureListener { updateStatus("IA de postura: ${it.message ?: "error"}") }
            .addOnCompleteListener {
                image.close()
                busy.set(false)
            }
    }

    private fun evaluatePose(pose: Pose, width: Int, height: Int) {
        val ls = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rs = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        val lh = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
        val rh = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)

        val points = listOfNotNull(ls, rs, lh, rh)
        if (points.size < 4 || points.any { it.inFrameLikelihood < MIN_LANDMARK_CONFIDENCE }) {
            resetCandidate()
            return
        }

        val shoulderX = (ls!!.position.x + rs!!.position.x) / 2f
        val shoulderY = (ls.position.y + rs.position.y) / 2f
        val hipX = (lh!!.position.x + rh!!.position.x) / 2f
        val hipY = (lh.position.y + rh.position.y) / 2f
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
        if (candidateSince == 0L || moved > MAX_NORMALIZED_MOVEMENT) candidateSince = now
        lastCandidateX = centerX
        lastCandidateY = centerY

        val stableFor = now - candidateSince
        prefs.edit()
            .putLong("sofaLastSeenAt", System.currentTimeMillis())
            .putString("sofaVisionStatus", "Persona tumbada en sofá · confirmando ${stableFor / 1000}s")
            .apply()

        if (stableFor >= CONFIRM_LYING_MS && now - lastPulseAt >= HOMEY_PULSE_MS) {
            lastPulseAt = now
            sendHomeyPulse()
        }
    }

    private fun resetCandidate() {
        candidateSince = 0L
        lastCandidateX = Float.NaN
        lastCandidateY = Float.NaN
    }

    private fun sendHomeyPulse() {
        val url = prefs.getString("homeySofaWebhook", "").orEmpty().trim()
        if (url.isBlank()) {
            updateStatus("Sofá detectado · falta configurar webhook de Homey")
            return
        }

        Thread {
            val result = runCatching {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6_000
                    readTimeout = 8_000
                    setRequestProperty("User-Agent", "JarvisTV-SofaVision/1")
                }
                val code = c.responseCode
                (if (code in 200..299) c.inputStream else c.errorStream)?.close()
                c.disconnect()
                code
            }

            if (result.getOrNull() in 200..299) {
                prefs.edit()
                    .putLong("sofaLastPulseAt", System.currentTimeMillis())
                    .putString("sofaVisionStatus", "Persona tumbada en sofá · pulso enviado a Homey")
                    .apply()
            } else {
                prefs.edit().putString(
                    "sofaVisionStatus",
                    "Sofá detectado · fallo Homey ${result.exceptionOrNull()?.message ?: result.getOrNull() ?: ""}"
                ).apply()
            }
        }.start()
    }

    override fun onDestroy() {
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.close() }
        runCatching { cameraDevice?.close() }
        runCatching { imageReader?.close() }
        runCatching { detector.close() }
        cameraThread?.quitSafely()
        captureSession = null
        cameraDevice = null
        imageReader = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_sofa_vision"
        private const val NOTIFICATION_ID = 2307
        private const val ANALYSIS_INTERVAL_MS = 900L
        private const val CONFIRM_LYING_MS = 30_000L
        private const val HOMEY_PULSE_MS = 60_000L
        private const val MAX_NORMALIZED_MOVEMENT = 0.10f
        private const val MIN_LANDMARK_CONFIDENCE = 0.55f
        private const val LYING_RATIO = 0.80f
    }
}
