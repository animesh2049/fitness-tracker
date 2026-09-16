package com.animesh.fitnesstracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.animesh.fitnesstracker.ui.navigation.FitnessApp
import com.animesh.fitnesstracker.ui.theme.WorkoutTheme

class MainActivity : ComponentActivity() {
    /** A route requested by a notification tap, consumed once by [FitnessApp]. */
    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only a fresh launch honours the extra; a recreated activity keeps the user's place.
        if (savedInstanceState == null) pendingRoute = intent?.getStringExtra(EXTRA_NAVIGATE_TO)
        setContent {
            WorkoutTheme {
                FitnessApp(startRoute = pendingRoute, onStartRouteConsumed = { pendingRoute = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_NAVIGATE_TO)?.let { pendingRoute = it }
    }

    companion object {
        /** String extra naming a navigation route, for example "diet" or "diet/meal/4". */
        const val EXTRA_NAVIGATE_TO = "navigateTo"
    }
}
