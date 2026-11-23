package com.dancewithme.app.ui

/**
 * Rutele de navigare pentru ecranele aplicației Dance with ME.
 */
sealed class AppScreen(val route: String) {
    data object MainMenu : AppScreen("main_menu")
    data object Tutorial : AppScreen("tutorial/{danceId}") {
        fun createRoute(danceId: String) = "tutorial/$danceId"
    }
    data object Calibration : AppScreen("calibration/{danceId}") {
        fun createRoute(danceId: String) = "calibration/$danceId"
    }
    data object Drill : AppScreen("drill/{danceId}") {
        fun createRoute(danceId: String) = "drill/$danceId"
    }
    data object RecordDance : AppScreen("record_dance")
    data object Results : AppScreen("results")
}
