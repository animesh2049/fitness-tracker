package com.animesh.workouttracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.animesh.workouttracker.WorkoutApplication
import com.animesh.workouttracker.di.AppContainer

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as WorkoutApplication).container
