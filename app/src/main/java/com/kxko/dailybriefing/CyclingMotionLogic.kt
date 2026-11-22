package com.kxko.dailybriefing

/**
 * Pure GPS motion rules used by the foreground cycling tracker.
 * Keeping these rules independent makes the pause/start thresholds testable.
 */
internal object CyclingMotionLogic {
    // Lowered so slow restarts (walking pace on a bike, after a light) still count as movement.
    const val MIN_CYCLING_SPEED_MPS = 0.5f // approximately 1.8 km/h
    const val MAX_CYCLING_SPEED_MPS = 16.7f // approximately 60 km/h
    // Longer window so a slow restart can collect its two confirming fixes.
    const val MOVEMENT_CANDIDATE_TIMEOUT_MS = 30_000L
    // Longer quiet time before pausing, so short stops do not toggle the ride to paused and back.
    const val STOP_AFTER_STILL_MS = 15_000L

    // Smaller minimum step at good GPS accuracy, capped so poor accuracy does not block restarts.
    fun minimumDisplacement(accuracyMetres: Float): Float =
        (accuracyMetres * 0.8f).coerceIn(4f, 10f)

    fun isPlausibleMovementSegment(
        distanceMetres: Float,
        elapsedSeconds: Float,
        accuracyMetres: Float,
        reportedSpeedMps: Float? = null,
        speedAccuracyMps: Float? = null
    ): Boolean {
        if (!distanceMetres.isFinite() || !elapsedSeconds.isFinite() ||
            !accuracyMetres.isFinite() || elapsedSeconds <= 0f ||
            accuracyMetres < 0f || accuracyMetres > 25f
        ) return false

        val measuredSpeed = distanceMetres / elapsedSeconds
        if (distanceMetres < minimumDisplacement(accuracyMetres) ||
            distanceMetres > 120f ||
            measuredSpeed !in MIN_CYCLING_SPEED_MPS..MAX_CYCLING_SPEED_MPS
        ) return false

        // When Android reports a reliable GPS speed, use it to reject stationary GPS drift.
        val reliableSpeed = reportedSpeedMps != null &&
            speedAccuracyMps != null && speedAccuracyMps in 0f..1.5f
        if (reliableSpeed &&
            (reportedSpeedMps ?: 0f) !in MIN_CYCLING_SPEED_MPS..MAX_CYCLING_SPEED_MPS
        ) return false

        return true
    }

    fun candidateWindowExpired(lastCandidateAt: Long, now: Long): Boolean =
        lastCandidateAt == 0L || now - lastCandidateAt > MOVEMENT_CANDIDATE_TIMEOUT_MS

    fun shouldPause(lastAcceptedMovementAt: Long, now: Long): Boolean =
        lastAcceptedMovementAt > 0L && now - lastAcceptedMovementAt > STOP_AFTER_STILL_MS
}
