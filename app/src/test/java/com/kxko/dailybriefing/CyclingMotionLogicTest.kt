package com.kxko.dailybriefing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CyclingMotionLogicTest {

    @Test
    fun recognisesSlowCyclingNearThreeKilometresPerHour() {
        assertTrue(
            CyclingMotionLogic.isPlausibleMovementSegment(
                distanceMetres = 8f,
                elapsedSeconds = 10f,
                accuracyMetres = 5f
            )
        )
    }

    @Test
    fun rejectsStationaryGpsDriftBelowCyclingSpeed() {
        assertFalse(
            CyclingMotionLogic.isPlausibleMovementSegment(
                distanceMetres = 5f,
                elapsedSeconds = 10f,
                accuracyMetres = 3f
            )
        )
    }

    @Test
    fun rejectsPoorAccuracyAndImplausibleJumps() {
        assertFalse(CyclingMotionLogic.isPlausibleMovementSegment(20f, 2f, 30f))
        assertFalse(CyclingMotionLogic.isPlausibleMovementSegment(150f, 5f, 5f))
        assertFalse(CyclingMotionLogic.isPlausibleMovementSegment(10f, 0f, 5f))
    }

    @Test
    fun reliableGpsSpeedCanRejectAStationaryFix() {
        assertFalse(
            CyclingMotionLogic.isPlausibleMovementSegment(
                distanceMetres = 12f,
                elapsedSeconds = 10f,
                accuracyMetres = 5f,
                reportedSpeedMps = 0.2f,
                speedAccuracyMps = 0.5f
            )
        )
        assertTrue(
            CyclingMotionLogic.isPlausibleMovementSegment(
                distanceMetres = 12f,
                elapsedSeconds = 10f,
                accuracyMetres = 5f,
                reportedSpeedMps = 1.1f,
                speedAccuracyMps = 0.5f
            )
        )
    }

    @Test
    fun slowGpsSamplesCanConfirmWithinTwentySeconds() {
        assertFalse(CyclingMotionLogic.candidateWindowExpired(1_000L, 15_000L))
        assertTrue(CyclingMotionLogic.candidateWindowExpired(1_000L, 22_000L))
    }

    @Test
    fun trackerPausesAfterTenSecondsWithoutAcceptedMovement() {
        assertFalse(CyclingMotionLogic.shouldPause(10_000L, 19_000L))
        assertTrue(CyclingMotionLogic.shouldPause(10_000L, 20_001L))
        assertFalse(CyclingMotionLogic.shouldPause(0L, 50_000L))
    }
}
