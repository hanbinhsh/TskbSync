package com.ice.tskbsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ice.tskbsync.ui.theme.TskbSyncTheme

class MainActivity : ComponentActivity() {
    private val viewModel: TaskbarViewModel by viewModels()

    override fun onStart() {
        super.onStart()
        viewModel.onEnterForeground()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onEnterBackground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TskbSyncTheme {
                val navController = rememberNavController()

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NavHost(navController = navController, startDestination = "main") {
                        composable("main") {
                            TaskbarScreen(viewModel, navController)
                        }
                        composable("settings") {
                            SettingsScreen(viewModel, navController)
                        }
                    }
                }
            }
        }
    }
}
