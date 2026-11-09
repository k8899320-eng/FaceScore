package com.facescore.app

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface Analysis {
    data class Ok(val result: ScoreResult) : Analysis
    data class Fail(val message: String) : Analysis
}

object FaceAnalyzer {

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setMinFaceSize(0.2f)
                .build()
        )
    }

    suspend fun analyze(bitmap: Bitmap): Analysis {
        val faces: List<Face> = suspendCancellableCoroutine { cont ->
            detector.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        if (faces.isEmpty()) {
            return Analysis.Fail("Лицо не найдено. Сними крупнее, при хорошем свете и смотри прямо в камеру.")
        }
        val face = faces.maxBy { it.boundingBox.width() * it.boundingBox.height() }

        fun lm(type: Int): P? = face.getLandmark(type)?.position?.let { P(it.x, it.y) }

        val eyeA = lm(FaceLandmark.LEFT_EYE)
        val eyeB = lm(FaceLandmark.RIGHT_EYE)
        val nose = lm(FaceLandmark.NOSE_BASE)
        val mouthA = lm(FaceLandmark.MOUTH_LEFT)
        val mouthB = lm(FaceLandmark.MOUTH_RIGHT)
        val contour = face.getContour(FaceContour.FACE)?.points?.map { P(it.x, it.y) }

        if (eyeA == null || eyeB == null || nose == null || mouthA == null || mouthB == null ||
            contour == null || contour.size < 10
        ) {
            return Analysis.Fail("Не получилось разглядеть черты лица. Убери волосы и очки, сними лицо целиком и в анфас.")
        }

        return Analysis.Ok(
            Scoring.evaluate(
                FaceInput(
                    eyeA = eyeA, eyeB = eyeB, noseBase = nose,
                    mouthA = mouthA, mouthB = mouthB, contour = contour,
                    yaw = face.headEulerAngleY, pitch = face.headEulerAngleX
                )
            )
        )
    }
}
