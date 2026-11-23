package com.example.dancewithme

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.dancewithme.app.audio.TtsFeedbackHelper
import com.dancewithme.app.data.DefaultDanceRepository
import com.dancewithme.app.data.SharedPreferencesScoreHistoryRepository
import com.dancewithme.app.ui.DanceWithMeNavHost
import com.dancewithme.app.ui.DrillViewModel
import com.example.dancewithme.ui.theme.DanceWithMETheme

class MainActivity : ComponentActivity() {

    private var ttsFeedbackHelper: TtsFeedbackHelper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = DefaultDanceRepository(context = applicationContext)
        val scoreHistoryRepository = SharedPreferencesScoreHistoryRepository(context = applicationContext)
        ttsFeedbackHelper = TtsFeedbackHelper(context = applicationContext)

        setContent {
            DanceWithMETheme {
                val viewModel: DrillViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                    factory = DrillViewModel.provideFactory(
                        repository = repository,
                        scoreHistoryRepository = scoreHistoryRepository
                    )
                )
                DanceWithMeNavHost(
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                    onSpeakResult = { drillResult, onFinished ->
                        val helper = ttsFeedbackHelper
                        if (helper != null) {
                            helper.speakDrillResult(drillResult, onFinished)
                        } else {
                            onFinished()
                        }
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        ttsFeedbackHelper?.shutdown()
        ttsFeedbackHelper = null
        super.onDestroy()
    }
}
