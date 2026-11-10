package app.detent

import android.app.Application
import app.detent.playback.PlayerController
import app.detent.ui.DetentController

/**
 * Holds the one DetentController for the whole app process. If Android recreates the Activity (it does when the
 * person goes to the browser to sign in and comes back), the screen state, the Spotify login and the running
 * Spotify player all survive, instead of a second player being started next to the first.
 */
class DetentApplication : Application() {
    val controller: DetentController by lazy { DetentController(this, PlayerController(this)) }
}
