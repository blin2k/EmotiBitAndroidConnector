package com.example.emotibitconnector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.emotibitconnector.ui.EmotiBitConnectorApp
import com.example.emotibitconnector.ui.theme.EmotiBitConnectorTheme
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import kotlin.jvm.Volatile

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logManifestPermissionsOnce()
        SessionService.ensureChannel(this)
        enableEdgeToEdge()
        setContent {
            EmotiBitConnectorTheme {
                EmotiBitConnectorApp()
            }
        }
    }

    private fun logManifestPermissionsOnce() {
        if (permissionsLogged) return
        permissionsLogged = true
        val required = REQUIRED_PERMISSIONS
        val declared = runCatching {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            }
            packageInfo.requestedPermissions?.toSet().orEmpty()
        }.getOrElse { emptySet() }
        required.forEach { permission ->
            if (!declared.contains(permission)) {
                Logx.e("Missing permission: $permission — Multicast/UDP may fail")
            }
        }
    }

    companion object {
        private val REQUIRED_PERMISSIONS = listOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.POST_NOTIFICATIONS
        )
        @Volatile
        private var permissionsLogged = false
    }
}
