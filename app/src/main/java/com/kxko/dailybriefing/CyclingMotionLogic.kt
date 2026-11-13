package com.kxko.dailybriefing

/**
 * Pure GPS motion rules used by the foreground cycling tracker.
 * Keeping these rules independent makes the pause/start thresholds testable.
 */
internal object CyclingMotionLogic {
    const val MIN_CYCLING_SPEED_MPS = 0.8f // approximately 2.9 km/h
    const val MAX_CYCLING_SPEED_MPS = 16.7f // approximately 60 km/h
    const val MOVEMENT_CANDIDATE_TIMEOUT_MS = 20_000L
    const val STOP_AFTER_STILL_MS = 10_000L

    fun minimumDisplacement(accuracyMetres: Float): Float =
        maxOf(8f, accuracyMetres * 1.2f)

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
