package com.animesh.fitnesstracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.animesh.fitnesstracker.FitnessApplication
import com.animesh.fitnesstracker.di.AppContainer

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as FitnessApplication).container
