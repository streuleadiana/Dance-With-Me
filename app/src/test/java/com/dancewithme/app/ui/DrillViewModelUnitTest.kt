package com.dancewithme.app.ui

import com.dancewithme.app.data.AngleVector
import com.dancewithme.app.data.DefaultDanceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Teste unitare pentru [DrillViewModel]:
 * - Verifică bufferul de 600 de cadre (20s * 30 FPS).
 * - Verifică declanșarea automată a DTW-ului în `viewModelScope` la expirarea celor 20 de secunde.
 * - Verifică feedback-ul live pe întregul corp și pe fiecare membru individual (`liveJointAccuracies`).
 * - Verifică calibrarea la 90 de grade.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DrillViewModelUnitTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `DrillViewModel buffers up to 600 AngleVectors and evaluates DTW automatically`() = runTest(testDispatcher) {
        val repository = DefaultDanceRepository(ioDispatcher = testDispatcher)
        val viewModel = DrillViewModel(
            repository = repository,
            defaultDispatcher = testDispatcher
        )
        advanceUntilIdle()

        val selectedDance = viewModel.uiState.value.selectedDance
        assertNotNull("Selected dance should be loaded", selectedDance)
        assertEquals(DefaultDanceRepository.ID_HORA_SIMPLA, selectedDance!!.id)

        // Pornim sesiunea de 20 secunde fără pre-countdown
        viewModel.startDrill(withPreCountdown = false)
        assertEquals(DrillPhase.RECORDING, viewModel.uiState.value.phase)

        // Trimitem toate cele 600 de cadre (20 secunde * 30 FPS)
        val referenceFrames = selectedDance.frames
        assertEquals(600, referenceFrames.size)

        referenceFrames.forEach { angleVector ->
            viewModel.onAngleVectorCaptured(angleVector)
        }

        // Avansăm corutina de evaluare DTW lansată în viewModelScope
        advanceUntilIdle()

        assertEquals(600, viewModel.getCapturedBufferSnapshot().size)
        assertEquals(DrillPhase.COMPLETED, viewModel.uiState.value.phase)

        val result = viewModel.uiState.value.lastDrillResult
        assertNotNull("DrillResult should be computed after 600 frames", result)
        assertEquals(100.0f, result!!.totalScore, 0.1f)
    }

    @Test
    fun `DrillViewModel triggers DTW evaluation when 20 second countdown expires`() = runTest(testDispatcher) {
        val repository = DefaultDanceRepository(ioDispatcher = testDispatcher)
        val viewModel = DrillViewModel(
            repository = repository,
            defaultDispatcher = testDispatcher
        )
        advanceUntilIdle()

        viewModel.startDrill(withPreCountdown = false)
        assertEquals(DrillPhase.RECORDING, viewModel.uiState.value.phase)
        assertEquals(20, viewModel.uiState.value.remainingSeconds)

        // Capturăm 300 de cadre (de ex. camera a rulat la 15 FPS timp de 20 de secunde)
        val refFrames = viewModel.uiState.value.selectedDance!!.frames
        for (i in 0 until 300) {
            viewModel.onAngleVectorCaptured(refFrames[i * 2])
        }
        assertEquals(300, viewModel.uiState.value.capturedFramesCount)

        // Simulăm trecerea celor 20 de secunde în viewModelScope
        advanceTimeBy(20_500L)
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.remainingSeconds)
        assertEquals(DrillPhase.COMPLETED, viewModel.uiState.value.phase)
        assertNotNull(viewModel.uiState.value.lastDrillResult)
        assertTrue(viewModel.uiState.value.lastDrillResult!!.formScore > 90f)
    }

    @Test
    fun `live form accuracy and per-joint accuracies toggle green or red based on angle deviation`() = runTest(testDispatcher) {
        val repository = DefaultDanceRepository(ioDispatcher = testDispatcher)
        val viewModel = DrillViewModel(
            repository = repository,
            defaultDispatcher = testDispatcher
        )
        advanceUntilIdle()

        viewModel.startDrill(withPreCountdown = false)
        val firstRefFrame = viewModel.uiState.value.selectedDance!!.frames.first()

        // 1. Cadru identic cu referința -> Toate cele 8 articulații sunt VERZI
        viewModel.onAngleVectorCaptured(firstRefFrame)
        assertTrue(viewModel.uiState.value.isLiveFormAccurate)
        assertEquals(8, viewModel.uiState.value.liveJointAccuracies.size)
        assertTrue(viewModel.uiState.value.liveJointAccuracies.values.all { it })

        // 2. Cadru unde doar cotul stâng și umărul drept greșesc cu 45° -> Doar acestea sunt ROȘII, restul VERZI
        val secondRefFrame = viewModel.uiState.value.selectedDance!!.frames[1]
        val singleLimbVsSecond = secondRefFrame.copy(
            cotStang = secondRefFrame.cotStang - 45f,
            umarDrept = secondRefFrame.umarDrept - 45f
        )
        viewModel.onAngleVectorCaptured(singleLimbVsSecond)

        val accuracies = viewModel.uiState.value.liveJointAccuracies
        assertFalse("Left elbow should be red (false)", accuracies[AngleVector.JOINT_LEFT_ELBOW] ?: true)
        assertFalse("Right shoulder should be red (false)", accuracies[AngleVector.JOINT_RIGHT_SHOULDER] ?: true)
        assertTrue("Left shoulder should remain green (true)", accuracies[AngleVector.JOINT_LEFT_SHOULDER] ?: false)
        assertTrue("Left knee should remain green (true)", accuracies[AngleVector.JOINT_LEFT_KNEE] ?: false)
        assertTrue("Right knee should remain green (true)", accuracies[AngleVector.JOINT_RIGHT_KNEE] ?: false)

        // 3. Cadru cu eroare mare pe toate cele 8 articulații (-50 grade) -> Toate membrele ROȘII
        val badFrame = AngleVector(
            genunchiStang = firstRefFrame.genunchiStang - 50f,
            genunchiDrept = firstRefFrame.genunchiDrept - 50f,
            soldStang = firstRefFrame.soldStang - 50f,
            soldDrept = firstRefFrame.soldDrept - 50f,
            cotStang = firstRefFrame.cotStang - 50f,
            cotDrept = firstRefFrame.cotDrept - 50f,
            umarStang = firstRefFrame.umarStang - 50f,
            umarDrept = firstRefFrame.umarDrept - 50f
        )
        viewModel.onAngleVectorCaptured(badFrame)
        assertFalse(viewModel.uiState.value.isLiveFormAccurate)
        assertTrue(viewModel.uiState.value.liveJointAccuracies.values.none { it })
    }

    @Test
    fun `updatePhoneTilt marks calibration true around 90 degrees`() = runTest(testDispatcher) {
        val viewModel = DrillViewModel(
            repository = DefaultDanceRepository(ioDispatcher = testDispatcher),
            defaultDispatcher = testDispatcher
        )

        viewModel.updatePhoneTilt(45f)
        assertFalse(viewModel.uiState.value.isCalibrated)

        viewModel.updatePhoneTilt(89.5f)
        assertTrue(viewModel.uiState.value.isCalibrated)
    }

    @Test
    fun `DrillViewModel loads 4 dances including gangnam_style_04 with ghostFrames and toggles Ghost Overlay`() = runTest(testDispatcher) {
        val repository = DefaultDanceRepository(ioDispatcher = testDispatcher)
        val viewModel = DrillViewModel(
            repository = repository,
            defaultDispatcher = testDispatcher
        )
        advanceUntilIdle()

        val dances = viewModel.uiState.value.availableDances
        assertEquals(4, dances.size)

        val gangnam = dances.find { it.id == DefaultDanceRepository.ID_GANGNAM_STYLE }
        assertNotNull("Gangnam Style dance (gangnam_style_04) must exist", gangnam)
        assertEquals("Gangnam Style", gangnam!!.title)
        assertTrue(gangnam.description.isNotBlank())
        assertEquals(600, gangnam.frames.size)
        assertEquals(600, gangnam.ghostFrames.size)
        assertEquals(33, gangnam.ghostFrames.first().size)

        // Selectăm Gangnam Style și verificăm sincronizarea Ghost Skeleton-ului
        viewModel.selectDance(DefaultDanceRepository.ID_GANGNAM_STYLE)
        assertEquals(DefaultDanceRepository.ID_GANGNAM_STYLE, viewModel.uiState.value.selectedDance?.id)
        assertEquals(33, viewModel.uiState.value.currentGhostLandmarks.size)

        // Verificăm toggleGhostOverlay() (implicit true -> false -> true)
        assertTrue(viewModel.uiState.value.showGhostOverlay)
        viewModel.toggleGhostOverlay()
        assertFalse(viewModel.uiState.value.showGhostOverlay)
        viewModel.toggleGhostOverlay()
        assertTrue(viewModel.uiState.value.showGhostOverlay)
    }

    @Test
    fun `DrillViewModel verifies BPM of the 4 built-in dances and toggles metronome state`() = runTest(testDispatcher) {
        val repository = DefaultDanceRepository(ioDispatcher = testDispatcher)
        val viewModel = DrillViewModel(
            repository = repository,
            defaultDispatcher = testDispatcher
        )
        advanceUntilIdle()

        val dancesById = viewModel.uiState.value.availableDances.associateBy { it.id }
        assertEquals(60, dancesById[DefaultDanceRepository.ID_HORA_SIMPLA]?.bpm)
        assertEquals(90, dancesById[DefaultDanceRepository.ID_SARBA]?.bpm)
        assertEquals(75, dancesById[DefaultDanceRepository.ID_MAKEDONEASCA]?.bpm)
        assertEquals(132, dancesById[DefaultDanceRepository.ID_GANGNAM_STYLE]?.bpm)

        // Verificăm toggleMetronome() (implicit true -> false -> true)
        assertTrue(viewModel.uiState.value.isMetronomeEnabled)
        viewModel.toggleMetronome()
        assertFalse(viewModel.uiState.value.isMetronomeEnabled)
        viewModel.toggleMetronome()
        assertTrue(viewModel.uiState.value.isMetronomeEnabled)
    }

    @Test
    fun `DrillViewModel saves and deletes custom dances and preserves JSON serialization with 8 angles, BPM and ghostFrames`() = runTest(testDispatcher) {
        val repository = DefaultDanceRepository(ioDispatcher = testDispatcher)
        val viewModel = DrillViewModel(
            repository = repository,
            defaultDispatcher = testDispatcher
        )
        advanceUntilIdle()

        val initialCount = viewModel.uiState.value.availableDances.size
        assertEquals(4, initialCount)

        // Pregătim o coregrafie custom de 90 de cadre (3 secunde @ 30 FPS) din Hora Simplă
        val templateDance = viewModel.uiState.value.availableDances.first()
        val customFrames = templateDance.frames.take(90)
        val customGhostFrames = templateDance.ghostFrames.take(90)

        var onSavedCalled = false
        viewModel.saveCustomDance(
            title = "  Coregrafia Mea TikTok  ",
            bpm = 110,
            frames = customFrames,
            ghostFrames = customGhostFrames,
            onSaved = { onSavedCalled = true }
        )
        advanceUntilIdle()

        assertTrue("onSaved callback should be invoked", onSavedCalled)
        assertEquals(5, viewModel.uiState.value.availableDances.size)

        val savedCustom = viewModel.uiState.value.availableDances.last()
        assertTrue(DefaultDanceRepository.isCustomDance(savedCustom.id))
        assertEquals("Coregrafia Mea TikTok", savedCustom.title)
        assertEquals(110, savedCustom.bpm)
        assertEquals(3, savedCustom.durationSec)
        assertEquals(90, savedCustom.frames.size)
        assertEquals(90, savedCustom.ghostFrames.size)
        assertEquals(33, savedCustom.ghostFrames.first().size)

        // Verificăm serializarea și deserializarea JSON completă (8 unghiuri + bpm + ghostFrames)
        val jsonPayload = repository.serializeReferenceDance(savedCustom)
        val deserialized = repository.parseReferenceDanceJson(jsonPayload)
        assertEquals(savedCustom.id, deserialized.id)
        assertEquals(savedCustom.title, deserialized.title)
        assertEquals(110, deserialized.bpm)
        assertEquals(90, deserialized.frames.size)
        assertEquals(savedCustom.frames.first().umarStang, deserialized.frames.first().umarStang, 0.01f)
        assertEquals(savedCustom.frames.first().umarDrept, deserialized.frames.first().umarDrept, 0.01f)
        assertEquals(90, deserialized.ghostFrames.size)

        // Ștergem dansul custom și verificăm revenirea la cele 4 dansuri built-in
        viewModel.deleteCustomDance(savedCustom.id)
        advanceUntilIdle()

        assertEquals(4, viewModel.uiState.value.availableDances.size)
        assertFalse(viewModel.uiState.value.availableDances.any { it.id == savedCustom.id })
    }

    @Test
    fun `computeTimeBasedFrameIndex and DrillViewModel synchronize reference and ghost frames by elapsed time regardless of FPS`() = runTest(testDispatcher) {
        // 1. Verificăm funcția pură computeTimeBasedFrameIndex pentru 20s (600 cadre: 0..599)
        assertEquals(-1, DrillViewModel.computeTimeBasedFrameIndex(elapsedMs = 5_000L, durationSec = 20, totalFrames = 0))
        assertEquals(0, DrillViewModel.computeTimeBasedFrameIndex(elapsedMs = 0L, durationSec = 20, totalFrames = 600))
        val midIdx = DrillViewModel.computeTimeBasedFrameIndex(elapsedMs = 10_000L, durationSec = 20, totalFrames = 600)
        assertTrue("Expected index ~299 at 10s, got $midIdx", midIdx in 299..300)
        assertEquals(599, DrillViewModel.computeTimeBasedFrameIndex(elapsedMs = 20_000L, durationSec = 20, totalFrames = 600))
        assertEquals(599, DrillViewModel.computeTimeBasedFrameIndex(elapsedMs = 25_000L, durationSec = 20, totalFrames = 600))

        // 2. Verificăm în DrillViewModel că la secunda 10.0s (chiar dacă s-au capturat doar 3 cadre pe un telefon lent),
        // Ghost Skeleton-ul și cadrul de referință sar la jumătatea coregrafiei (~cadrul 299)
        var fakeClockMs = 1_000L
        val repository = DefaultDanceRepository(ioDispatcher = testDispatcher)
        val viewModel = DrillViewModel(
            repository = repository,
            defaultDispatcher = testDispatcher,
            timeProviderMs = { fakeClockMs }
        )
        advanceUntilIdle()

        viewModel.selectDance(DefaultDanceRepository.ID_GANGNAM_STYLE)
        val gangnam = viewModel.uiState.value.selectedDance!!

        viewModel.startDrill(withPreCountdown = false)

        // Simulăm că au trecut 10 secunde (10_000 ms), dar camera a livrat un singur cadru
        fakeClockMs += 10_000L
        val expectedIdxAt10s = DrillViewModel.computeTimeBasedFrameIndex(10_000L, 20, 600)
        viewModel.onAngleVectorCaptured(gangnam.frames[expectedIdxAt10s])

        assertEquals(1, viewModel.uiState.value.capturedFramesCount)
        assertEquals(gangnam.ghostFrames[expectedIdxAt10s], viewModel.uiState.value.currentGhostLandmarks)
        assertTrue(viewModel.uiState.value.isLiveFormAccurate)
    }

    @Test
    fun `CoordinateMapper mapFillCenter aligns 3x4 portrait camera frame onto 9x16 screen canvas`() {
        // Imagine portret 480x640 (raport 3:4) afișată pe un ecran 1080x1920 (raport 9:16) cu FILL_CENTER:
        // scale = max(1080/480 = 2.25, 1920/640 = 3.0) = 3.0
        // scaledWidth = 480 * 3.0 = 1440, offsetX = (1080 - 1440) / 2 = -180
        // scaledHeight = 640 * 3.0 = 1920, offsetY = (1920 - 1920) / 2 = 0
        val (centerX, centerY) = CoordinateMapper.mapFillCenter(
            normX = 0.5f,
            normY = 0.5f,
            sourceWidth = 480,
            sourceHeight = 640,
            viewWidth = 1080f,
            viewHeight = 1920f
        )
        assertEquals(540f, centerX, 0.01f)
        assertEquals(960f, centerY, 0.01f)

        // Marginea stângă a imaginii camerei (normX = 0f) este decupată în afara ecranului la -180px
        val (leftEdgeX, topEdgeY) = CoordinateMapper.mapFillCenter(
            normX = 0f,
            normY = 0f,
            sourceWidth = 480,
            sourceHeight = 640,
            viewWidth = 1080f,
            viewHeight = 1920f
        )
        assertEquals(-180f, leftEdgeX, 0.01f)
        assertEquals(0f, topEdgeY, 0.01f)

        // Marginea dreaptă a imaginii camerei (normX = 1f) este la 1440 - 180 = 1260px
        val (rightEdgeX, bottomEdgeY) = CoordinateMapper.mapFillCenter(
            normX = 1f,
            normY = 1f,
            sourceWidth = 480,
            sourceHeight = 640,
            viewWidth = 1080f,
            viewHeight = 1920f
        )
        assertEquals(1260f, rightEdgeX, 0.01f)
        assertEquals(1920f, bottomEdgeY, 0.01f)
    }
}
