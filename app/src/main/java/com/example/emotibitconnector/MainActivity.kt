package com.example.emotibitconnector

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.emotibitconnector.ui.theme.EmotiBitConnectorTheme
import com.example.emotibitconnector.ui.EmotiBitConnectorApp

class MainActivity : ComponentActivity() {

    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifiManager?.createMulticastLock("emotibit-mlock")?.apply {
            setReferenceCounted(false)
        }
        enableEdgeToEdge()
        setContent {
            EmotiBitConnectorTheme {
                EmotiBitConnectorApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        multicastLock?.let { lock ->
            if (!lock.isHeld) {
                lock.acquire()
            }
        }
    }

    override fun onStop() {
        multicastLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        multicastLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
        multicastLock = null
        super.onDestroy()
    }
}
