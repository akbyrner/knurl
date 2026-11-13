package app.knurl

import android.app.Activity
import android.app.Application
import android.os.Bundle
import app.knurl.playback.PlayerController
import app.knurl.ui.KnurlController
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Holds the one KnurlController for the whole app process. If Android recreates the Activity (it does when the
 * person goes to another app and comes back), the screen state and Spotify connection survive.
 * Also remembers which Activity is on screen, because Spotify's "Allow Knurl" screen can only be opened from one.
 */
class KnurlApplication : Application() {
    private var visible: WeakReference<Activity>? = null

    val controller: KnurlController by lazy {
        KnurlController(this, PlayerController(this)) { visible?.get() }
    }

    override fun onCreate() {
        super.onCreate()
        // Keep the home-screen widgets in step with what is playing.
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
        scope.launch {
            androidx.compose.runtime.snapshotFlow {
                val i = controller.nowPlayingInfo(0L)
                listOf(controller.currentTrackKey, i?.isPlaying, i?.title, controller.artVersion)
            }.collectLatest {
                kotlinx.coroutines.delay(400)
                app.knurl.widget.WidgetSync.push(this@KnurlApplication, controller)
            }
        }
        scope.launch {
            // While something plays, redraw now and then so the progress follows along.
            while (true) {
                kotlinx.coroutines.delay(30_000)
                if (controller.nowPlayingInfo(System.currentTimeMillis())?.isPlaying == true) app.knurl.widget.WidgetSync.push(this@KnurlApplication, controller)
            }
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { visible = WeakReference(activity) }
            override fun onActivityPaused(activity: Activity) { if (visible?.get() === activity) visible = null }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
