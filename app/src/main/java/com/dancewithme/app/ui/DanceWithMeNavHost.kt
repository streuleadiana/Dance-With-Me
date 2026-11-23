package com.dancewithme.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dancewithme.app.data.DefaultDanceRepository
import com.dancewithme.app.data.DrillResult

/**
 * Graficul de navigare Jetpack Compose (`NavHost`) care conectează:
 * - `MainMenuScreen`
 * - `TutorialScreen` (Învață Dansul: Video + Schelet 3D animat la 30 FPS)
 * - `CalibrationScreen`
 * - `DrillScreen` (cu Ghost Skeleton Overlay live)
 * - `ResultScreen` (cu suport pentru „Repetă”, „Următorul” și „Meniu Principal”)
 */
@Composable
fun DanceWithMeNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    viewModel: DrillViewModel = viewModel(factory = DrillViewModel.provideFactory()),
    onSpeakResult: (DrillResult, () -> Unit) -> Unit = { _, onDone -> onDone() }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Ascultăm evenimentele one-shot din ViewModel (ex: navigarea automată la ResultScreen după cele 20s)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is DrillEvent.NavigateToResults -> {
                    navController.navigate(AppScreen.Results.route) {
                        launchSingleTop = true
                    }
                }
                is DrillEvent.ShowMessage -> Unit
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = AppScreen.MainMenu.route,
        modifier = modifier
    ) {
        composable(route = AppScreen.MainMenu.route) {
            MainMenuScreen(
                dances = uiState.availableDances,
                danceProgressMap = uiState.danceProgressMap,
                onDanceSelected = { dance ->
                    viewModel.selectDance(dance.id)
                    navController.navigate(AppScreen.Tutorial.createRoute(dance.id))
                },
                onCreateCustomDance = {
                    navController.navigate(AppScreen.RecordDance.route)
                },
                onDeleteCustomDance = viewModel::deleteCustomDance
            )
        }

        composable(route = AppScreen.RecordDance.route) {
            RecordDanceScreen(
                onSaveCustomDance = { title, bpm, frames, ghostFrames ->
                    viewModel.saveCustomDance(
                        title = title,
                        bpm = bpm,
                        frames = frames,
                        ghostFrames = ghostFrames,
                        onSaved = {
                            navController.popBackStack(AppScreen.MainMenu.route, inclusive = false)
                        }
                    )
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(route = AppScreen.Tutorial.route) { backStackEntry ->
            val danceId = backStackEntry.arguments?.getString("danceId")
            LaunchedEffect(danceId) {
                if (danceId != null) {
                    viewModel.selectDance(danceId)
                }
            }

            val dances = uiState.availableDances.ifEmpty {
                DefaultDanceRepository.builtInReferenceDances()
            }
            val currentDance = uiState.selectedDance
                ?: dances.find { it.id == danceId }
                ?: dances.first()

            TutorialScreen(
                dance = currentDance,
                onProceedToCalibration = {
                    navController.navigate(AppScreen.Calibration.createRoute(currentDance.id))
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(route = AppScreen.Calibration.route) { backStackEntry ->
            val danceId = backStackEntry.arguments?.getString("danceId")
            LaunchedEffect(danceId) {
                if (danceId != null) {
                    viewModel.selectDance(danceId)
                }
            }

            CalibrationScreen(
                danceTitle = uiState.selectedDance?.title ?: "Antrenament Dans",
                currentTiltDeg = uiState.currentPhoneTiltDeg,
                isCalibrated = uiState.isCalibrated,
                onTiltUpdated = viewModel::updatePhoneTilt,
                onProceedToDrill = {
                    val targetId = uiState.selectedDance?.id ?: danceId ?: DefaultDanceRepository.ID_HORA_SIMPLA
                    viewModel.resetDrill()
                    navController.navigate(AppScreen.Drill.createRoute(targetId))
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(route = AppScreen.Drill.route) { backStackEntry ->
            val danceId = backStackEntry.arguments?.getString("danceId")
            LaunchedEffect(danceId) {
                if (danceId != null && uiState.selectedDance?.id != danceId) {
                    viewModel.selectDance(danceId)
                }
            }

            DrillScreen(
                uiState = uiState,
                onPoseOutput = viewModel::onPoseExtractionOutput,
                onStartDrill = { viewModel.startDrill(withPreCountdown = true) },
                onStopAndEvaluate = viewModel::finishAndEvaluateDrill,
                onToggleGhostOverlay = viewModel::toggleGhostOverlay,
                onToggleMetronome = viewModel::toggleMetronome,
                onBack = {
                    viewModel.resetDrill()
                    navController.popBackStack()
                }
            )
        }

        composable(route = AppScreen.Results.route) {
            val result = uiState.lastDrillResult ?: DrillResult(
                totalScore = 0f,
                formScore = 0f,
                timingScore = 0f,
                jointErrors = emptyMap()
            )
            val dances = uiState.availableDances.ifEmpty {
                DefaultDanceRepository.builtInReferenceDances()
            }
            val currentDance = uiState.selectedDance ?: dances.first()
            val currentProgress = uiState.danceProgressMap[currentDance.id]

            ResultScreen(
                danceTitle = currentDance.title,
                result = result,
                onSpeakResult = onSpeakResult,
                isNewBestScore = uiState.isNewBestScore,
                bestScore = currentProgress?.bestScore,
                onRetryDrill = {
                    viewModel.resetDrill()
                    navController.popBackStack()
                },
                onNextDance = {
                    val currentIndex = dances.indexOfFirst { it.id == currentDance.id }
                    val nextDance = if (currentIndex >= 0) {
                        dances[(currentIndex + 1) % dances.size]
                    } else {
                        dances.first()
                    }
                    viewModel.selectDance(nextDance.id)
                    viewModel.resetDrill()
                    navController.navigate(AppScreen.Tutorial.createRoute(nextDance.id)) {
                        popUpTo(AppScreen.MainMenu.route) { inclusive = false }
                    }
                },
                onBackToMenu = {
                    viewModel.resetDrill()
                    navController.popBackStack(AppScreen.MainMenu.route, inclusive = false)
                }
            )
        }
    }
}
