package app.detent.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import app.detent.DetentApplication

abstract class DetentWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = WidgetRender.update(context, manager, javaClass, ids)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) =
        WidgetRender.update(context, manager, javaClass, intArrayOf(id))
}

class WidgetNowSmall : DetentWidget()
class WidgetPinned : DetentWidget()
class WidgetNowMedium : DetentWidget()
class WidgetNowLarge : DetentWidget()

/** Taps on the transport buttons: handed to the app's one controller, which drives the phone's player or Spotify. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val controller = (context.applicationContext as DetentApplication).controller
        when (intent.getStringExtra(WidgetRender.EXTRA_WHAT)) {
            "toggle" -> controller.widgetToggle()
            "next" -> controller.widgetSkip(true)
            "prev" -> controller.widgetSkip(false)
        }
    }
}
