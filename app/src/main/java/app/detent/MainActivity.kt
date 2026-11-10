package app.detent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import app.detent.data.loadLibrary
import app.detent.playback.PlayerController
import app.detent.ui.DetentApp
import app.detent.ui.DetentController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val controller by lazy { DetentController(applicationContext, PlayerController(applicationContext)) }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRedirect(intent)
    }

    /** Spotify's login page sends the browser back as app.detent://spotify-callback?code=... */
    private fun handleRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "app.detent" && data.host == "spotify-callback") controller.onAuthRedirect(data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleRedirect(intent)
        // The screen is black at the top, so the status bar needs light icons.
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        setContent {
            val audioPermission = if (Build.VERSION.SDK_INT >= 33) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            var granted by remember {
                mutableStateOf(ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED)
            }
            // Notifications only matter for the lock-screen player; playback works without them.
            val wanted = buildList {
                add(audioPermission)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray()
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
                granted = result[audioPermission] == true
            }

            LaunchedEffect(Unit) {
                val missing = wanted.any { ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED }
                if (missing) launcher.launch(wanted)
            }
            LaunchedEffect(granted) {
                if (granted) {
                    val real = withContext(Dispatchers.IO) { loadLibrary(contentResolver) }
                    if (real.tracks.isNotEmpty()) controller.loadLibrary(real)
                }
            }

            DetentApp(controller)
        }
    }
}
