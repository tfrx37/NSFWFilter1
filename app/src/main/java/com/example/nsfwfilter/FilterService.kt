package com.example.nsfwfilter

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class FilterService : AccessibilityService() {

    companion object {
        private const val TAG = "FilterService"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "nsfw_filter_foreground_channel"

        private const val NORMAL_CAPTURE_INTERVAL_MS = 350L
        private const val STATIC_RECHECK_INTERVAL_MS = 1200L
        private const val OVERLAY_CAPTURE_INTERVAL_MS = 500L
        private const val HOLD_MASK_MS = 1800L

        private const val WATCHDOG_TIMEOUT_MS = 8000L
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val CIRCUIT_BREAKER_DELAY_MS = 4000L

        private const val GLOBAL_CHECK_EVERY_N_CAPTURES = 1
    }

    private lateinit var classifier: NSFWClassifier

    private var windowManager: WindowManager? = null
    private var overlayView: ImageView? = null
    private var isOverlayAttached = false

    private val screenshotExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val requestCounter = AtomicLong(0L)
    private val activeRequestId = AtomicLong(0L)

    @Volatile
    private var isAnalyzing = false

    @Volatile
    private var isOverlayShowing = false

    @Volatile
    private var isCircuitBreakerActive = false

    @Volatile
    private var lastNsfwDetectedTime = 0L

    private var lastCaptureTime = 0L
    private var lastImageNodesHash = 0
    private var consecutiveFailures = 0
    private var captureSequence = 0L

    private var watchdogJob: Job? = null
    private var circuitBreakerJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        classifier = NSFWClassifier(this)

        startForegroundNotification()
        setupOverlayView()

        Log.i(TAG, "Service d'accessibilité connecté")
    }

    private fun startForegroundNotification() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Protection active NSFWFilter",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "État de la protection de l'écran"
                setShowBadge(false)
            }

            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NSFWFilter est actif")
            .setContentText("Protection de l'écran en cours")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setupOverlayView() {
        if (isOverlayAttached) return

        val view = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            alpha = 0f
            visibility = View.VISIBLE
            contentDescription = "Écran masqué par NSFWFilter"
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            windowManager?.addView(view, params)
            overlayView = view
            isOverlayAttached = true
        } catch (e: Exception) {
            Log.e(TAG, "Impossible d'attacher l'overlay", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val rootNode = rootInActiveWindow
        val currentPackage = rootNode?.packageName?.toString() ?: event?.packageName?.toString()

        if (isAuthOrBiometricPackage(currentPackage)) {
            if (isOverlayShowing) hideOverlay()
            rootNode?.recycle()
            return
        }
        rootNode?.recycle()

        if (checkAndBlockSettingsAccess(event)) {
            return
        }

        if (event == null || isCircuitBreakerActive || isAnalyzing) return

        val now = SystemClock.elapsedRealtime()

        if (isOverlayShowing && now - lastNsfwDetectedTime < HOLD_MASK_MS) {
            return
        }

        val bounds = getVisibleImageBounds()
        val boundsHash = bounds.hashCode()

        val isStaticScreen = boundsHash == lastImageNodesHash
        val minInterval = when {
            isOverlayShowing -> OVERLAY_CAPTURE_INTERVAL_MS
            isStaticScreen -> STATIC_RECHECK_INTERVAL_MS
            else -> NORMAL_CAPTURE_INTERVAL_MS
        }

        if (now - lastCaptureTime < minInterval) return

        lastImageNodesHash = boundsHash
        lastCaptureTime = now

        captureSequence++

        captureAndAnalyzeScreen(
            imageBounds = bounds.map { Rect(it) },
            sequence = captureSequence
        )
    }

    private fun isAuthOrBiometricPackage(packageName: String?): Boolean {
        if (packageName == null) return false
        val pkg = packageName.lowercase()
        return pkg.contains("biometric") ||
                pkg.contains("keyguard") ||
                pkg.contains("fingerprint") ||
                pkg.contains("credentials") ||
                pkg == "com.samsung.android.biometrics" ||
                pkg == "com.android.systemui" ||
                pkg == "com.google.android.systemui" ||
                pkg == "android"
    }

    private fun checkAndBlockSettingsAccess(event: AccessibilityEvent?): Boolean {
        val packageName = event?.packageName?.toString() ?: return false

        if (packageName == "com.android.settings" || packageName.contains("setting", ignoreCase = true)) {
            val rootNode = rootInActiveWindow ?: return false

            try {
                val hasAppName = isNodeTreeContainsText(rootNode, listOf("NSFWFilter", "nsfwfilter"))

                if (hasAppName) {
                    val isAppInfoPage = isNodeTreeContainsText(
                        rootNode,
                        listOf("Désinstaller", "Uninstall", "Forcer l'arrêt", "Force stop")
                    )

                    val isAccessibilityTogglePage = isNodeTreeContainsText(
                        rootNode,
                        listOf(
                            "Utiliser NSFWFilter", "Use NSFWFilter",
                            "Raccourci NSFWFilter", "NSFWFilter shortcut",
                            "Autoriser NSFWFilter", "Allow NSFWFilter"
                        )
                    ) || hasSwitchOrToggle(rootNode)

                    if (isAppInfoPage || isAccessibilityTogglePage) {
                        Log.w(TAG, "--> TENTATIVE DE DÉSACTIVATION BLOQUÉE : RETOUR ACCUEIL")
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        return true
                    }
                }
            } finally {
                rootNode.recycle()
            }
        }
        return false
    }

    private fun hasSwitchOrToggle(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val className = node.className?.toString() ?: ""
        if (className.contains("Switch", ignoreCase = true) ||
            className.contains("ToggleButton", ignoreCase = true) ||
            className.contains("CheckBox", ignoreCase = true)) {
            return true
        }

        val text = node.text?.toString() ?: ""
        if (text.contains("Utiliser", ignoreCase = true) ||
            text.contains("Raccourci", ignoreCase = true) ||
            text.contains("Shortcut", ignoreCase = true)) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (hasSwitchOrToggle(child)) {
                child?.recycle()
                return true
            }
            child?.recycle()
        }
        return false
    }

    private fun isNodeTreeContainsText(node: AccessibilityNodeInfo?, keywords: List<String>): Boolean {
        if (node == null) return false

        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""

        for (keyword in keywords) {
            if (text.contains(keyword, ignoreCase = true) || contentDesc.contains(keyword, ignoreCase = true)) {
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (isNodeTreeContainsText(child, keywords)) {
                child?.recycle()
                return true
            }
            child?.recycle()
        }
        return false
    }

    private fun captureAndAnalyzeScreen(imageBounds: List<Rect>, sequence: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "La capture d'écran d'accessibilité nécessite Android 11+")
            return
        }

        if (isAnalyzing || isCircuitBreakerActive) return

        val requestId = requestCounter.incrementAndGet()

        activeRequestId.set(requestId)
        isAnalyzing = true

        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            delay(WATCHDOG_TIMEOUT_MS)

            if (activeRequestId.compareAndSet(requestId, 0L)) {
                Log.w(TAG, "Watchdog : analyse $requestId expirée ; son résultat sera ignoré")
                isAnalyzing = false
            }
        }

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        val hardwareBuffer = screenshotResult.hardwareBuffer
                        var hardwareBitmap: Bitmap? = null
                        var softwareBitmap: Bitmap? = null

                        try {
                            if (!isCurrentRequest(requestId)) return

                            hardwareBitmap = Bitmap.wrapHardwareBuffer(
                                hardwareBuffer,
                                screenshotResult.colorSpace
                            )

                            softwareBitmap = hardwareBitmap?.copy(
                                Bitmap.Config.ARGB_8888,
                                false
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Conversion de capture impossible", e)
                        } finally {
                            try {
                                hardwareBitmap?.recycle()
                            } catch (_: Exception) {
                            }
                            hardwareBuffer.close()
                        }

                        val bitmap = softwareBitmap

                        if (bitmap == null) {
                            finishAnalysis(requestId)
                            recordCaptureFailure()
                            return
                        }

                        if (!isCurrentRequest(requestId)) {
                            bitmap.recycle()
                            return
                        }

                        serviceScope.launch {
                            analyzeBitmap(
                                requestId = requestId,
                                bitmap = bitmap,
                                imageBounds = imageBounds,
                                sequence = sequence
                            )
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (!isCurrentRequest(requestId)) return

                        Log.e(TAG, "Échec de capture : code=$errorCode")
                        finishAnalysis(requestId)
                        recordCaptureFailure()
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Impossible de demander une capture", e)
            finishAnalysis(requestId)
            recordCaptureFailure()
        }
    }

    private suspend fun analyzeBitmap(
        requestId: Long,
        bitmap: Bitmap,
        imageBounds: List<Rect>,
        sequence: Long
    ) {
        Log.d("NSFW_DEBUG", "=== ANALYSE DEBUT (Req: $requestId, Nœuds trouvés: ${imageBounds.size}, Dim: ${bitmap.width}x${bitmap.height}) ===")

        var replacementOverlay: Bitmap? = null

        try {
            if (!isCurrentRequest(requestId)) return

            var detectedNsfw = false
            var globalResult: ClassificationResult? = null

            for ((index, rect) in imageBounds.withIndex()) {
                if (!isCurrentRequest(requestId)) return

                val crop = cropBitmap(bitmap, rect) ?: continue

                val result = try {
                    classifier.classifyForCrop(crop)
                } finally {
                    crop.recycle()
                }

                Log.d("NSFW_DEBUG", "Nœud #$index $rect -> Status: ${result.status}, Score: ${result.score}, Motif: ${result.reason}")

                when (result.status) {
                    ClassificationStatus.NSFW -> {
                        detectedNsfw = true
                        break
                    }
                    ClassificationStatus.SAFE -> {}
                    ClassificationStatus.UNKNOWN -> {}
                }
            }

            val shouldRunGlobalCheck = !detectedNsfw

            if (shouldRunGlobalCheck) {
                if (!isCurrentRequest(requestId)) return

                globalResult = classifier.classifyForScreen(bitmap)
                Log.d("NSFW_DEBUG", "Analyse Globale -> Status: ${globalResult.status}, Score: ${globalResult.score}, Motif: ${globalResult.reason}")

                if (globalResult.status == ClassificationStatus.NSFW) {
                    detectedNsfw = true
                }
            }

            if (!isCurrentRequest(requestId)) return

            if (detectedNsfw) {
                replacementOverlay = createSafeLightBlurBitmap(bitmap)
            }

            withContext(Dispatchers.Main) {
                if (!isCurrentRequest(requestId)) {
                    replacementOverlay?.recycle()
                    replacementOverlay = null
                    return@withContext
                }

                val now = SystemClock.elapsedRealtime()

                when {
                    detectedNsfw -> {
                        lastNsfwDetectedTime = now
                        replacementOverlay?.let {
                            showOverlay(it)
                            replacementOverlay = null
                        }
                    }
                    globalResult?.status == ClassificationStatus.UNKNOWN -> {
                        Log.d(TAG, "État inconnu : conservation de l'état du masque")
                    }
                    globalResult?.status == ClassificationStatus.SAFE || (!detectedNsfw && imageBounds.isNotEmpty()) -> {
                        val maskAge = now - lastNsfwDetectedTime
                        if (!isOverlayShowing || maskAge >= HOLD_MASK_MS) {
                            hideOverlay()
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Erreur pendant l'analyse", e)
        } finally {
            replacementOverlay?.recycle()
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
            finishAnalysis(requestId)
        }
    }

    private fun isCurrentRequest(requestId: Long): Boolean {
        return activeRequestId.get() == requestId
    }

    private fun finishAnalysis(requestId: Long) {
        if (activeRequestId.compareAndSet(requestId, 0L)) {
            watchdogJob?.cancel()
            isAnalyzing = false
        }
    }

    private fun recordCaptureFailure() {
        consecutiveFailures++
        if (consecutiveFailures < MAX_CONSECUTIVE_FAILURES) return

        isCircuitBreakerActive = true
        circuitBreakerJob?.cancel()

        circuitBreakerJob = serviceScope.launch {
            delay(CIRCUIT_BREAKER_DELAY_MS)
            consecutiveFailures = 0
            isCircuitBreakerActive = false
            Log.i(TAG, "Circuit breaker réinitialisé")
        }
    }

    private fun createSafeLightBlurBitmap(source: Bitmap): Bitmap {
        var tinyBitmap: Bitmap? = null
        var midBitmap: Bitmap? = null

        try {
            val lowWidth = (source.width * 0.05f).toInt().coerceAtLeast(1)
            val lowHeight = (source.height * 0.05f).toInt().coerceAtLeast(1)

            tinyBitmap = Bitmap.createScaledBitmap(source, lowWidth, lowHeight, true)

            val midWidth = (source.width * 0.15f).toInt().coerceAtLeast(1)
            val midHeight = (source.height * 0.15f).toInt().coerceAtLeast(1)

            midBitmap = Bitmap.createScaledBitmap(tinyBitmap, midWidth, midHeight, true)

            val blurredBitmap = Bitmap.createScaledBitmap(midBitmap, source.width, source.height, true)

            val canvas = Canvas(blurredBitmap)
            val lightPaint = Paint().apply {
                color = 0x55FFFFFF
                style = Paint.Style.FILL
            }

            canvas.drawRect(0f, 0f, blurredBitmap.width.toFloat(), blurredBitmap.height.toFloat(), lightPaint)

            return blurredBitmap
        } finally {
            tinyBitmap?.recycle()
            midBitmap?.recycle()
        }
    }

    private fun getVisibleImageBounds(): List<Rect> {
        val root = rootInActiveWindow ?: return emptyList()
        val boundsList = mutableListOf<Rect>()

        try {
            findImageNodesRecursive(root, boundsList)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur de lecture de l'arbre d'accessibilité", e)
        } finally {
            root.recycle()
        }

        return boundsList
            .sortedByDescending { it.width().toLong() * it.height() }
            .take(6)
    }

    private fun findImageNodesRecursive(node: AccessibilityNodeInfo?, boundsList: MutableList<Rect>) {
        if (node == null) return

        val className = node.className?.toString() ?: ""
        val resourceId = node.viewIdResourceName?.lowercase() ?: ""

        val isImageOrMediaView =
            className.contains("Image", ignoreCase = true) ||
                    className.contains("Graphic", ignoreCase = true) ||
                    (
                            className.contains("View", ignoreCase = true) &&
                                    (
                                            resourceId.contains("photo") ||
                                                    resourceId.contains("image") ||
                                                    resourceId.contains("media") ||
                                                    resourceId.contains("picture")
                                            )
                            )

        if (isImageOrMediaView) {
            val rect = Rect()
            node.getBoundsInScreen(rect)

            if (rect.width() > 80 && rect.height() > 80) {
                boundsList.add(rect)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                try {
                    findImageNodesRecursive(child, boundsList)
                } finally {
                    child.recycle()
                }
            }
        }
    }

    private fun cropBitmap(source: Bitmap, screenRect: Rect): Bitmap? {
        val x = screenRect.left.coerceIn(0, source.width)
        val y = screenRect.top.coerceIn(0, source.height)

        if (x >= source.width || y >= source.height) return null

        val width = screenRect.width().coerceAtMost(source.width - x)
        val height = screenRect.height().coerceAtMost(source.height - y)

        if (width <= 15 || height <= 15) return null

        return try {
            Bitmap.createBitmap(source, x, y, width, height)
        } catch (e: Exception) {
            Log.w(TAG, "Impossible de découper la zone $screenRect", e)
            null
        }
    }

    private fun showOverlay(blurredBitmap: Bitmap) {
        val view = overlayView

        if (view == null || !isOverlayAttached) {
            blurredBitmap.recycle()
            return
        }

        val oldDrawable = view.drawable as? BitmapDrawable

        view.setImageBitmap(blurredBitmap)
        view.alpha = 1f
        isOverlayShowing = true

        val oldBitmap = oldDrawable?.bitmap

        if (oldBitmap != null && oldBitmap !== blurredBitmap && !oldBitmap.isRecycled) {
            oldBitmap.recycle()
        }
    }

    private fun hideOverlay() {
        val view = overlayView

        isOverlayShowing = false

        if (view == null) return

        val oldDrawable = view.drawable as? BitmapDrawable
        val oldBitmap = oldDrawable?.bitmap

        view.alpha = 0f
        view.setImageDrawable(null)

        if (oldBitmap != null && !oldBitmap.isRecycled) {
            oldBitmap.recycle()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        activeRequestId.set(0L)
        isAnalyzing = false

        watchdogJob?.cancel()
        circuitBreakerJob?.cancel()
        serviceScope.cancel()

        screenshotExecutor.shutdownNow()

        if (isOverlayAttached && overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {
                Log.w(TAG, "Erreur lors du retrait de l'overlay", e)
            }
        }

        overlayView = null
        isOverlayAttached = false

        if (::classifier.isInitialized) {
            classifier.close()
        }

        super.onDestroy()
    }
}