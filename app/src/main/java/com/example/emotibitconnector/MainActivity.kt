package com.example.emotibitconnector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.emotibitconnector.ui.theme.EmotiBitConnectorTheme
import com.example.emotibitconnector.ui.EmotiBitConnectorApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EmotiBitConnectorTheme {
                EmotiBitConnectorApp()
            }
        }
    }
}
