package com.animesh.fitnesstracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.animesh.fitnesstracker.ui.navigation.FitnessApp
import com.animesh.fitnesstracker.ui.theme.WorkoutTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WorkoutTheme {
                FitnessApp()
            }
        }
    }
}
