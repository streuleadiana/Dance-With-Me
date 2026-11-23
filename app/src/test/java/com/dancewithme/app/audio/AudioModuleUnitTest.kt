package com.dancewithme.app.audio

import com.dancewithme.app.data.AngleVector
import com.dancewithme.app.data.DrillResult
import com.dancewithme.app.sensors.ProximitySensorManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Teste unitare pentru modulul `.audio` ([VoskVoiceCommandManager], [TtsFeedbackHelper], [RhythmMetronomeHelper])
 * și logica de declanșare a senzorului de proximitate ([ProximitySensorManager]).
 */
class AudioModuleUnitTest {

    @Test
    fun `RhythmMetronomeHelper bpmToIntervalMs converts BPM accurately and clamps extreme values`() {
        // 60 BPM -> 1000 ms per bătaie
        assertEquals(1000L, RhythmMetronomeHelper.bpmToIntervalMs(60))
        // 90 BPM -> 666 ms per bătaie
        assertEquals(666L, RhythmMetronomeHelper.bpmToIntervalMs(90))
        // 120 BPM -> 500 ms per bătaie
        assertEquals(500L, RhythmMetronomeHelper.bpmToIntervalMs(120))
        // 132 BPM (Gangnam Style) -> 454 ms per bătaie
        assertEquals(454L, RhythmMetronomeHelper.bpmToIntervalMs(132))

        // Clamping pentru valori sub MIN_BPM (40 BPM -> 1500 ms) și peste MAX_BPM (220 BPM -> 272 ms)
        assertEquals(1500L, RhythmMetronomeHelper.bpmToIntervalMs(10))
        assertEquals(1500L, RhythmMetronomeHelper.bpmToIntervalMs(-50))
        assertEquals(60_000L / 220L, RhythmMetronomeHelper.bpmToIntervalMs(500))
    }

    @Test
    fun `Vosk trigger detector matches start keyword in partial and final JSON payloads`() {
        assertTrue(VoskVoiceCommandManager.containsTriggerWordStart("""{"partial" : "start"}"""))
        assertTrue(VoskVoiceCommandManager.containsTriggerWordStart("""{"text" : "hai start acum"}"""))
        assertTrue(VoskVoiceCommandManager.containsTriggerWordStart("START"))
        assertFalse(VoskVoiceCommandManager.containsTriggerWordStart("""{"partial" : "salut"}"""))
        assertFalse(VoskVoiceCommandManager.containsTriggerWordStart("""{"text" : "restart"}"""))
    }

    @Test
    fun `Vosk trigger detector matches repeat and next keywords in partial and final JSON payloads`() {
        // Test "repeat"
        assertTrue(VoskVoiceCommandManager.containsTriggerWordRepeat("""{"partial" : "repeat"}"""))
        assertTrue(VoskVoiceCommandManager.containsTriggerWordRepeat("""{"text" : "please repeat now"}"""))
        assertTrue(VoskVoiceCommandManager.containsTriggerWordRepeat("REPEAT"))
        assertFalse(VoskVoiceCommandManager.containsTriggerWordRepeat("""{"partial" : "start"}"""))
        assertFalse(VoskVoiceCommandManager.containsTriggerWordRepeat("""{"text" : "repeating"}"""))

        // Test "next"
        assertTrue(VoskVoiceCommandManager.containsTriggerWordNext("""{"partial" : "next"}"""))
        assertTrue(VoskVoiceCommandManager.containsTriggerWordNext("""{"text" : "go next"}"""))
        assertTrue(VoskVoiceCommandManager.containsTriggerWordNext("NEXT"))
        assertFalse(VoskVoiceCommandManager.containsTriggerWordNext("""{"partial" : "repeat"}"""))
        assertFalse(VoskVoiceCommandManager.containsTriggerWordNext("""{"text" : "nextdoor"}"""))
    }

    @Test
    fun `TtsFeedbackHelper builds actionable feedback based on signedJointErrors`() {
        // Cazul 1: Genunchi stâng prea întins (eroare cu semn pozitivă +22°) -> "Îndoaie mai mult genunchiul stâng"
        val resultTooExtended = DrillResult(
            totalScore = 82.4f,
            formScore = 78.0f,
            timingScore = 92.5f,
            jointErrors = mapOf(
                AngleVector.JOINT_LEFT_KNEE to 22.0f,
                AngleVector.JOINT_RIGHT_KNEE to 5.0f
            ),
            signedJointErrors = mapOf(
                AngleVector.JOINT_LEFT_KNEE to 22.0f,
                AngleVector.JOINT_RIGHT_KNEE to -5.0f
            )
        )

        val speechExtended = TtsFeedbackHelper.buildFeedbackText(resultTooExtended)
        assertTrue(speechExtended.contains("82 la sută"))
        assertTrue(speechExtended.contains(AngleVector.JOINT_LEFT_KNEE))
        assertTrue(
            "Expected 'Îndoaie mai mult genunchiul stâng', got: $speechExtended",
            speechExtended.contains("Îndoaie mai mult genunchiul stâng")
        )

        // Cazul 2: Cot stâng prea îndoit (eroare cu semn negativă -24.8°) -> "Întinde mai mult brațul stâng"
        val resultTooBent = DrillResult(
            totalScore = 75.0f,
            formScore = 70.0f,
            timingScore = 85.0f,
            jointErrors = mapOf(
                AngleVector.JOINT_LEFT_ELBOW to 24.8f
            ),
            signedJointErrors = mapOf(
                AngleVector.JOINT_LEFT_ELBOW to -24.8f
            )
        )

        val speechBent = TtsFeedbackHelper.buildFeedbackText(resultTooBent)
        assertTrue(
            "Expected 'Întinde mai mult brațul stâng', got: $speechBent",
            speechBent.contains("Întinde mai mult brațul stâng")
        )

        // Cazul 3: Umăr drept prea jos (eroare cu semn negativă -32°) -> "Ridică mai sus brațul drept"
        val resultRightShoulderLow = DrillResult(
            totalScore = 71.0f,
            formScore = 68.0f,
            timingScore = 80.0f,
            jointErrors = mapOf(
                AngleVector.JOINT_RIGHT_SHOULDER to 32.0f
            ),
            signedJointErrors = mapOf(
                AngleVector.JOINT_RIGHT_SHOULDER to -32.0f
            )
        )
        val speechShoulderLow = TtsFeedbackHelper.buildFeedbackText(resultRightShoulderLow)
        assertTrue(
            "Expected 'Ridică mai sus brațul drept', got: $speechShoulderLow",
            speechShoulderLow.contains("Ridică mai sus brațul drept")
        )

        // Cazul 4: Umăr stâng prea ridicat (eroare cu semn pozitivă +25°) -> "Coboară puțin brațul stâng"
        val resultLeftShoulderHigh = DrillResult(
            totalScore = 76.0f,
            formScore = 72.0f,
            timingScore = 85.0f,
            jointErrors = mapOf(
                AngleVector.JOINT_LEFT_SHOULDER to 25.0f
            ),
            signedJointErrors = mapOf(
                AngleVector.JOINT_LEFT_SHOULDER to 25.0f
            )
        )
        val speechShoulderHigh = TtsFeedbackHelper.buildFeedbackText(resultLeftShoulderHigh)
        assertTrue(
            "Expected 'Coboară puțin brațul stâng', got: $speechShoulderHigh",
            speechShoulderHigh.contains("Coboară puțin brațul stâng")
        )
    }

    @Test
    fun `ProximitySensorManager shouldTriggerWave detects hand within near threshold`() {
        assertTrue(ProximitySensorManager.shouldTriggerWave(distanceCm = 0.0f, maxRange = 5.0f))
        assertTrue(ProximitySensorManager.shouldTriggerWave(distanceCm = 2.5f, maxRange = 5.0f))
        assertFalse(ProximitySensorManager.shouldTriggerWave(distanceCm = 5.0f, maxRange = 5.0f))
        assertFalse(ProximitySensorManager.shouldTriggerWave(distanceCm = 4.0f, maxRange = 8.0f))
    }
}
