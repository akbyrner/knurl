package app.detent

import android.app.Activity
import android.app.Application
import android.os.Bundle
import app.detent.playback.PlayerController
import app.detent.ui.DetentController
import java.lang.ref.WeakReference

/**
 * Holds the one DetentController for the whole app process. If Android recreates the Activity (it does when the
 * person goes to another app and comes back), the screen state and Spotify connection survive.
 * Also remembers which Activity is on screen, because Spotify's "Allow Detent" screen can only be opened from one.
 */
class DetentApplication : Application() {
    private var visible: WeakReference<Activity>? = null

    val controller: DetentController by lazy {
        DetentController(this, PlayerController(this)) { visible?.get() }
    }

    override fun onCreate() {
        super.onCreate()
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
