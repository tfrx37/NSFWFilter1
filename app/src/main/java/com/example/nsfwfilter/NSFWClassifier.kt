package com.example.nsfwfilter

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

enum class ClassificationStatus {
    SAFE,
    NSFW,
    UNKNOWN
}

data class ClassificationResult(
    val status: ClassificationStatus,
    val score: Float? = null,
    val reason: String? = null
)

class NSFWClassifier(context: Context) : AutoCloseable {

    companion object {
        private const val TAG = "NSFWClassifier"
        private const val MODEL_FILE = "nsfw_model.tflite"

        private const val INPUT_SIZE = 224
        private const val SKIN_CHECK_SIZE = 40

        private const val CROP_THRESHOLD = 0.25f
        private const val GLOBAL_THRESHOLD = 0.25f
        private const val STRICT_NO_SKIN_THRESHOLD = 0.40f
    }

    private val appContext = context.applicationContext

    @Volatile
    private var interpreter: Interpreter? = null

    @Volatile
    private var closed = false

    private val lock = Any()

    init {
        initializeInterpreter()
    }

    private fun initializeInterpreter() {
        synchronized(lock) {
            if (closed || interpreter != null) return

            try {
                val options = Interpreter.Options().apply {
                    setNumThreads(2)
                }

                val model = loadModelFile(appContext, MODEL_FILE)
                interpreter = Interpreter(model, options)
            } catch (e: Exception) {
                Log.e(TAG, "Échec du chargement du modèle", e)
                interpreter = null
            }
        }
    }

    private fun loadModelFile(context: Context, fileName: String): MappedByteBuffer {
        context.assets.openFd(fileName).use { asset ->
            FileInputStream(asset.fileDescriptor).use { stream ->
                return stream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    asset.startOffset,
                    asset.declaredLength
                )
            }
        }
    }

    fun classify(
        bitmap: Bitmap,
        allowSkinPrefilterSkip: Boolean = false
    ): ClassificationResult {
        if (closed) return unknown("Classifier fermé")
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return unknown("Bitmap invalide")

        val skinRatio = calculateSkinRatio(bitmap)

        if (isDarkOrSystemScreen(bitmap, skinRatio)) {
            return ClassificationResult(status = ClassificationStatus.SAFE, score = 0.0f)
        }

        if (allowSkinPrefilterSkip && skinRatio < 0.005f) {
            return unknown("Zone ignorée par le préfiltre de peau")
        }

        val currentInterpreter = interpreter ?: return unknown("Interpréteur indisponible")

        return try {
            synchronized(lock) {
                if (closed) return unknown("Classifier fermé")

                val activeInterpreter = interpreter ?: return unknown("Interpréteur indisponible")
                runInference(activeInterpreter, bitmap, skinRatio)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur pendant l'inférence", e)
            restartInterpreter()
            unknown("Erreur d'inférence")
        }
    }

    private fun runInference(
        activeInterpreter: Interpreter,
        bitmap: Bitmap,
        skinRatio: Float
    ): ClassificationResult {
        val inputTensor = activeInterpreter.getInputTensor(0)
        val outputTensor = activeInterpreter.getOutputTensor(0)

        if (inputTensor.dataType() != DataType.FLOAT32 || outputTensor.dataType() != DataType.FLOAT32) {
            return unknown("Type de tenseur non pris en charge")
        }

        val processor = ImageProcessor.Builder()
            .add(ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(127.5f, 127.5f))
            .build()

        var tensorImage = TensorImage(inputTensor.dataType())
        tensorImage.load(bitmap)
        tensorImage = processor.process(tensorImage)

        val output = Array(1) { FloatArray(5) }
        activeInterpreter.run(tensorImage.buffer, output)

        val scores = output[0]
        if (scores.any { !it.isFinite() }) return unknown("Score non fini")

        val drawings = scores[0]
        val hentai = scores[1]
        val neutral = scores[2]
        val porn = scores[3]
        val sexy = scores[4]

        Log.d("NSFW_DEBUG", "TFLite Bruts -> drawings: %.2f, hentai: %.2f, neutral: %.2f, porn: %.2f, sexy: %.2f | SkinRatio: %.3f"
            .format(drawings, hentai, neutral, porn, sexy, skinRatio))

        val combinedScore = maxOf(
            porn + hentai,
            sexy * 0.75f,
            (porn + hentai + sexy * 0.50f)
        )
        val score = combinedScore.coerceIn(0.0f, 1.0f)

        return ClassificationResult(
            status = ClassificationStatus.SAFE,
            score = score
        )
    }

    fun classifyForCrop(bitmap: Bitmap): ClassificationResult {
        val result = classify(bitmap, allowSkinPrefilterSkip = true)
        return applyThreshold(result, CROP_THRESHOLD)
    }

    fun classifyForScreen(bitmap: Bitmap): ClassificationResult {
        val skinRatio = calculateSkinRatio(bitmap)
        val result = classify(bitmap, allowSkinPrefilterSkip = false)

        // Si l'écran ne contient quasiment pas de peau (banque/empreinte/graphiques), on exige un score très élevé (0.55f)
        val effectiveThreshold = if (skinRatio < 0.015f) STRICT_NO_SKIN_THRESHOLD else GLOBAL_THRESHOLD
        return applyThreshold(result, effectiveThreshold)
    }

    private fun applyThreshold(
        result: ClassificationResult,
        threshold: Float
    ): ClassificationResult {
        if (result.status == ClassificationStatus.UNKNOWN) return result
        val score = result.score ?: return unknown("Score absent")

        return ClassificationResult(
            status = if (score >= threshold) ClassificationStatus.NSFW else ClassificationStatus.SAFE,
            score = score
        )
    }

    private fun isDarkOrSystemScreen(bitmap: Bitmap, skinRatio: Float): Boolean {
        if (skinRatio > 0.005f) return false

        val sampleSize = 30
        val small = Bitmap.createScaledBitmap(bitmap, sampleSize, sampleSize, false)
        var totalLuminance = 0L
        val totalPixels = sampleSize * sampleSize

        for (y in 0 until sampleSize) {
            for (x in 0 until sampleSize) {
                val p = small.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                totalLuminance += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
            }
        }
        small.recycle()

        val avgLuminance = totalLuminance / totalPixels
        return avgLuminance < 18
    }

    private fun calculateSkinRatio(bitmap: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(bitmap, SKIN_CHECK_SIZE, SKIN_CHECK_SIZE, false)
        var skinCount = 0
        val total = SKIN_CHECK_SIZE * SKIN_CHECK_SIZE

        for (y in 0 until SKIN_CHECK_SIZE) {
            for (x in 0 until SKIN_CHECK_SIZE) {
                val pixel = small.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                if (r > 40 && g > 20 && b > 10 && r >= g && (maxOf(r, g, b) - minOf(r, g, b) > 5)) {
                    skinCount++
                }
            }
        }
        small.recycle()
        return skinCount.toFloat() / total
    }

    private fun unknown(reason: String) = ClassificationResult(ClassificationStatus.UNKNOWN, reason = reason)

    fun restartInterpreter(): Boolean {
        synchronized(lock) {
            if (closed) return false
            try { interpreter?.close() } catch (_: Exception) {}
            interpreter = null
            initializeInterpreter()
            return interpreter != null
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try { interpreter?.close() } catch (_: Exception) {}
            interpreter = null
        }
    }
}