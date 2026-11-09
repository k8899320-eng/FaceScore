package com.facescore.app

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/** Точка в координатах фото (пиксели). */
data class P(val x: Float, val y: Float)

/** Всё, что нужно для расчёта, — уже найдено нейросетью на фото. */
data class FaceInput(
    val eyeA: P,
    val eyeB: P,
    val noseBase: P,
    val mouthA: P,
    val mouthB: P,
    val contour: List<P>,   // овал лица
    val yaw: Float,         // поворот головы влево-вправо, градусы
    val pitch: Float        // наклон вверх-вниз, градусы
)

data class Metric(val name: String, val value: String, val score: Float, val note: String)

data class Overlay(val points: List<P>, val lines: List<Pair<P, P>>, val contour: List<P>)

data class ScoreResult(
    val score: Float,
    val verdict: String,
    val metrics: List<Metric>,
    val warning: String?,
    val overlay: Overlay
)

object Scoring {

    // Ориентиры. Их удобно подкручивать, если оценки на реальных фото кажутся
    // систематически заниженными или завышенными.
    private const val IDEAL_EYES_TO_WIDTH = 0.46f      // Pallett et al., 2010
    private const val IDEAL_EYE_MOUTH_TO_LENGTH = 0.36f // Pallett et al., 2010
    private const val IDEAL_LENGTH_TO_WIDTH = 1.45f
    private const val IDEAL_LOWER_THIRD = 0.33f
    private const val IDEAL_MOUTH_TO_EYES = 1.0f

    /** 1 — точно в ориентир, плавно падает к 0 по мере отклонения. */
    private fun closeness(v: Float, ideal: Float, tol: Float): Float {
        val d = (v - ideal) / tol
        return exp(-(d * d))
    }

    fun evaluate(f: FaceInput): ScoreResult {
        // Упорядочим пары слева направо по фото
        val (eL, eR) = if (f.eyeA.x <= f.eyeB.x) f.eyeA to f.eyeB else f.eyeB to f.eyeA
        val (mL, mR) = if (f.mouthA.x <= f.mouthB.x) f.mouthA to f.mouthB else f.mouthB to f.mouthA

        // Выровняем лицо: повернём всё так, чтобы линия глаз стала горизонтальной
        val cx = (eL.x + eR.x) / 2f
        val cy = (eL.y + eR.y) / 2f
        val angle = atan2((eR.y - eL.y).toDouble(), (eR.x - eL.x).toDouble())
        val c = cos(-angle).toFloat()
        val s = sin(-angle).toFloat()
        fun rot(p: P) = P((p.x - cx) * c - (p.y - cy) * s, (p.x - cx) * s + (p.y - cy) * c)

        val r = f.contour.map(::rot)
        val iTop = r.indices.minBy { r[it].y }
        val iChin = r.indices.maxBy { r[it].y }
        val iLeft = r.indices.minBy { r[it].x }
        val iRight = r.indices.maxBy { r[it].x }
        val top = r[iTop]; val chin = r[iChin]; val left = r[iLeft]; val right = r[iRight]

        val EL = rot(eL); val ER = rot(eR)
        val ML = rot(mL); val MR = rot(mR)
        val N = rot(f.noseBase)

        val faceLen = chin.y - top.y
        val faceW = right.x - left.x
        val eyeDist = hypot(ER.x - EL.x, ER.y - EL.y)
        val mouthW = hypot(MR.x - ML.x, MR.y - ML.y)
        val eyeY = (EL.y + ER.y) / 2f
        val mouthY = (ML.y + MR.y) / 2f
        val axisX = (top.x + chin.x) / 2f   // центральная линия лица

        // 1) Симметрия — насколько парные точки одинаково удалены от центральной линии
        val asym = listOf(
            abs(abs(EL.x - axisX) - abs(ER.x - axisX)),
            abs(abs(ML.x - axisX) - abs(MR.x - axisX)),
            abs(abs(left.x - axisX) - abs(right.x - axisX)),
            abs(N.x - axisX) * 1.5f,
            abs(EL.y - ER.y),
            abs(ML.y - MR.y)
        ).map { it / faceW }
        val asymAvg = asym.average().toFloat()
        val symS = closeness(asymAvg, 0f, 0.075f)

        val r1 = eyeDist / faceW
        val r2 = (mouthY - eyeY) / faceLen
        val r3 = faceLen / faceW
        val r4 = (chin.y - N.y) / faceLen
        val r5 = mouthW / eyeDist

        val metrics = listOf(
            Metric("Симметрия", "${((1 - asymAvg) * 100).toInt()}%", symS,
                "Насколько левая и правая половины лица совпадают."),
            Metric("Глаза к ширине лица", "${(r1 * 100).toInt()}%", closeness(r1, IDEAL_EYES_TO_WIDTH, 0.07f),
                "Расстояние между зрачками от ширины лица. Ориентир — около 46%."),
            Metric("Глаза–рот к высоте лица", "${(r2 * 100).toInt()}%", closeness(r2, IDEAL_EYE_MOUTH_TO_LENGTH, 0.06f),
                "Ориентир — около 36% высоты лица."),
            Metric("Форма лица", "%.2f".format(r3), closeness(r3, IDEAL_LENGTH_TO_WIDTH, 0.22f),
                "Высота к ширине. Ориентир — около 1.45."),
            Metric("Нижняя треть", "${(r4 * 100).toInt()}%", closeness(r4, IDEAL_LOWER_THIRD, 0.06f),
                "От основания носа до подбородка. Ориентир — около трети высоты."),
            Metric("Ширина рта", "%.2f".format(r5), closeness(r5, IDEAL_MOUTH_TO_EYES, 0.2f),
                "К расстоянию между зрачками. Ориентир — около 1.0.")
        )
        val weights = listOf(0.30f, 0.18f, 0.18f, 0.12f, 0.12f, 0.10f)
        val total = metrics.zip(weights).sumOf { (m, w) -> (m.score * w).toDouble() }.toFloat()
        // Реальные лица редко идеальны во всём, поэтому шкала слегка «мягкая»
        val score = (1f + 9f * total.pow(0.75f)).coerceIn(1f, 10f)

        val verdict = when {
            score >= 8.5f -> "Пропорции почти как в учебнике"
            score >= 7f -> "Очень гармоничные пропорции"
            score >= 5.5f -> "Хорошие пропорции, есть свои особенности"
            score >= 4f -> "Необычные пропорции — или неудачный ракурс"
            else -> "Скорее всего, мешает ракурс — попробуй ещё раз"
        }

        val warning = if (abs(f.yaw) > 8f || abs(f.pitch) > 12f)
            "Голова повёрнута или наклонена — это сильно занижает оценку. Для честного результата снимай строго в анфас, камера на уровне глаз."
        else null

        val tOrig = f.contour[iTop]; val cOrig = f.contour[iChin]
        val lOrig = f.contour[iLeft]; val rOrig = f.contour[iRight]
        val overlay = Overlay(
            points = listOf(eL, eR, f.noseBase, mL, mR, tOrig, cOrig, lOrig, rOrig),
            lines = listOf(tOrig to cOrig, eL to eR, mL to mR, lOrig to rOrig),
            contour = f.contour
        )

        return ScoreResult(score, verdict, metrics, warning, overlay)
    }
}
