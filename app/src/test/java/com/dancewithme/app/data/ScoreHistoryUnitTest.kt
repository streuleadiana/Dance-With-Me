package com.dancewithme.app.data

import com.dancewithme.app.ui.DrillPhase
import com.dancewithme.app.ui.DrillViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
 * Teste unitare pentru [ScoreHistoryRepository], [InMemoryScoreHistoryRepository]
 * și integrarea persistenței scorurilor în [DrillViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScoreHistoryUnitTest {

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
    fun `first attempt records attemptsCount 1 and marks isNewBestScore true`() = runTest(testDispatcher) {
        val repo = InMemoryScoreHistoryRepository()

        val result = repo.recordAttempt(
            danceId = DefaultDanceRepository.ID_HORA_SIMPLA,
            score = 78.5f,
            timestampMs = 1_000L
        )

        assertTrue(result.isNewBestScore)
        assertEquals(1, result.updatedProgress.attemptsCount)
        assertEquals(78.5f, result.updatedProgress.bestScore, 0.01f)
        assertEquals(78.5f, result.updatedProgress.lastScore, 0.01f)
        assertEquals(1_000L, result.updatedProgress.lastPlayedTimestampMs)
    }

    @Test
    fun `lower subsequent score increments attemptsCount without changing bestScore and returns isNewBestScore false`() = runTest(testDispatcher) {
        val repo = InMemoryScoreHistoryRepository()
        val danceId = DefaultDanceRepository.ID_GANGNAM_STYLE

        repo.recordAttempt(danceId = danceId, score = 85.0f, timestampMs = 1_000L)
        val secondAttempt = repo.recordAttempt(danceId = danceId, score = 72.0f, timestampMs = 2_000L)

        assertFalse("Lower score should not be a new best score", secondAttempt.isNewBestScore)
        assertEquals(2, secondAttempt.updatedProgress.attemptsCount)
        assertEquals(85.0f, secondAttempt.updatedProgress.bestScore, 0.01f)
        assertEquals(72.0f, secondAttempt.updatedProgress.lastScore, 0.01f)
        assertEquals(2_000L, secondAttempt.updatedProgress.lastPlayedTimestampMs)
    }

    @Test
    fun `higher subsequent score updates bestScore and returns isNewBestScore true`() = runTest(testDispatcher) {
        val repo = InMemoryScoreHistoryRepository()
        val danceId = DefaultDanceRepository.ID_SARBA

        repo.recordAttempt(danceId = danceId, score = 70.0f, timestampMs = 1_000L)
        repo.recordAttempt(danceId = danceId, score = 65.0f, timestampMs = 2_000L)
        val thirdAttempt = repo.recordAttempt(danceId = danceId, score = 94.2f, timestampMs = 3_000L)

        assertTrue("Higher score should be a new best score", thirdAttempt.isNewBestScore)
        assertEquals(3, thirdAttempt.updatedProgress.attemptsCount)
        assertEquals(94.2f, thirdAttempt.updatedProgress.bestScore, 0.01f)
        assertEquals(94.2f, thirdAttempt.updatedProgress.lastScore, 0.01f)
    }

    @Test
    fun `DrillViewModel finishAndEvaluateDrill updates danceProgressMap and isNewBestScore in uiState`() = runTest(testDispatcher) {
        val danceRepo = DefaultDanceRepository(ioDispatcher = testDispatcher)
        val scoreRepo = InMemoryScoreHistoryRepository()
        val viewModel = DrillViewModel(
            repository = danceRepo,
            scoreHistoryRepository = scoreRepo,
            defaultDispatcher = testDispatcher
        )
        advanceUntilIdle()

        viewModel.selectDance(DefaultDanceRepository.ID_GANGNAM_STYLE)
        viewModel.startDrill(withPreCountdown = false)

        // Simulăm o primă sesiune cu eroare moderată (-15° pe unghiuri)
        val referenceFrames = viewModel.uiState.value.selectedDance!!.frames
        referenceFrames.forEach { ref ->
            viewModel.onAngleVectorCaptured(
                ref.copy(
                    genunchiStang = ref.genunchiStang - 15f,
                    cotStang = ref.cotStang - 15f
                )
            )
        }
        advanceUntilIdle()

        assertEquals(DrillPhase.COMPLETED, viewModel.uiState.value.phase)
        assertTrue(viewModel.uiState.value.isNewBestScore)
        val firstProgress = viewModel.uiState.value.danceProgressMap[DefaultDanceRepository.ID_GANGNAM_STYLE]
        assertNotNull(firstProgress)
        assertEquals(1, firstProgress!!.attemptsCount)
        val firstScore = firstProgress.bestScore
        assertTrue(firstScore in 50f..99f)

        // Simulăm a doua sesiune perfectă (100%) -> Nou Record Personal!
        viewModel.resetDrill()
        assertFalse(viewModel.uiState.value.isNewBestScore)
        viewModel.startDrill(withPreCountdown = false)
        referenceFrames.forEach { ref ->
            viewModel.onAngleVectorCaptured(ref)
        }
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isNewBestScore)
        val secondProgress = viewModel.uiState.value.danceProgressMap[DefaultDanceRepository.ID_GANGNAM_STYLE]
        assertNotNull(secondProgress)
        assertEquals(2, secondProgress!!.attemptsCount)
        assertEquals(100.0f, secondProgress.bestScore, 0.1f)
    }
}
