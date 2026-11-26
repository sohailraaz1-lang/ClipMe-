package com.clipme.app.captions

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Auto-detects the facecam region on the extracted frame using ML Kit.
 * Returns the bounding box in the frame's coordinate space, or null if no
 * face (or more than one) was found — in that case the UI falls back to a
 * heuristic box (top-right).
 */
object FaceDetectorBox {

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .enableTracking()
        .build()

    suspend fun detect(frame: Bitmap): RectF? = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val detector = FaceDetection.getClient(options)
            detector.process(InputImage.fromBitmap(frame, 0))
                .addOnSuccessListener { faces ->
                    val largest = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                    if (largest != null && largest.boundingBox.width() > 40) {
                        val r = largest.boundingBox
                        val padW = (r.width() * 0.35f).toInt()
                        val padH = (r.height() * 0.9f).toInt()
                        val left = (r.left - padW).coerceIn(0, frame.width)
                        val top = (r.top - padH).coerceIn(0, frame.height)
                        val right = (r.right + padW).coerceIn(0, frame.width)
                        val bottom = (r.bottom + padH).coerceIn(0, frame.height)
                        cont.resume(RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()))
                    } else {
                        cont.resume(null)
                    }
                }
                .addOnFailureListener { cont.resume(null) }
                .addOnCanceledListener { cont.resume(null) }
        }
    }
}
