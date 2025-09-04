package app.lawnchair.gestures

import android.view.MotionEvent
import app.lawnchair.LawnchairLauncher
import app.lawnchair.preferences.PreferenceManager
import com.android.launcher3.touch.BothAxesSwipeDetector
import com.android.launcher3.util.TouchController

class FeedSwipeController(
    private val launcher: LawnchairLauncher,
) : TouchController,
    BothAxesSwipeDetector.Listener {

    private val detector = BothAxesSwipeDetector(launcher, this)
    private val prefs = PreferenceManager.getInstance(launcher)

    override fun onControllerInterceptTouchEvent(ev: MotionEvent): Boolean {
        detector.setDetectableScrollConditions(BothAxesSwipeDetector.DIRECTION_RIGHT, false)
        detector.onTouchEvent(ev)
        return detector.isDraggingOrSettling
    }

    override fun onControllerTouchEvent(ev: MotionEvent): Boolean {
        detector.onTouchEvent(ev)
        return true
    }

    override fun onDragStart(start: Boolean) {}

    override fun onDrag(displacement: android.graphics.PointF, motionEvent: MotionEvent): Boolean {
        if (displacement.x > 0) {
            val feedProvider = prefs.feedProvider.get()
            val intent = launcher.packageManager.getLaunchIntentForPackage(feedProvider)
            if (intent != null) {
                val serviceIntent = android.content.Intent("com.android.launcher3.WINDOW_OVERLAY")
                    .setPackage(feedProvider)
                val resolveInfo = launcher.packageManager.resolveService(serviceIntent, 0)
                if (resolveInfo == null) {
                    launcher.startActivity(intent)
                }
            }
        }
        return true
    }

    override fun onDragEnd(velocity: android.graphics.PointF) {
        detector.finishedScrolling()
    }
}
