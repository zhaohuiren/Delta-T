package app.deltat.commute

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.SurfaceView
import android.view.TextureView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.deltat.commute.domain.LocationFix
import app.deltat.commute.map.MapScene
import app.deltat.commute.map.MapCameraPolicy
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/** Uses the real SDK renderer and compositor screenshot, not Compose's root capture. */
class MapCompositingTest {
    @Test fun rendererParticipatesInParentComposition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val out = File(instrumentation.targetContext.getExternalFilesDir(null), "map-compositing").apply { mkdirs() }
        val klass = Class.forName("app.deltat.commute.ui.AmapSceneController")
        fun call(obj: Any, name: String, vararg args: Any?): Any? = klass.declaredMethods.first { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }.invoke(obj, *args)
        var controller: Any? = null
        var parent: FrameLayout? = null
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val c = klass.declaredConstructors.single().apply { isAccessible = true }.newInstance(activity.applicationContext, { error("SDK rendering failed") })
                controller = c
                val root = FrameLayout(activity).apply { setBackgroundColor(android.graphics.Color.RED) }
                parent = FrameLayout(activity).apply { setBackgroundColor(android.graphics.Color.BLUE) }
                root.addView(parent, FrameLayout.LayoutParams(900, 1400))
                val view = call(c, "createView", activity) as View
                parent!!.addView(view, FrameLayout.LayoutParams(900, 900))
                activity.setContentView(root)
                call(c, "onResume")
                val points = (0..20).map { LocationFix(31.1, 121.3 + it * .001, it * 1_000_000_000L, 0, 5.0) }
                call(c, "update", MapScene(points, points, emptyList(), points.last(), emptyList(), emptyList(), MapCameraPolicy.FULL_ROUTE_LOCKED))
            }
            Thread.sleep(1800)
            fun capture(name: String) {
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    if (InstrumentationRegistry.getArguments().getString("requireTexture") == "true") {
                        val red = android.graphics.Color.red(bitmap.getPixel(100, 200))
                        if (name == "half-alpha.png") assertTrue("Map must blend with red parent, red=$red", red in 110..155)
                        if (name == "transparent.png") assertEquals(255, red)
                    }
                    bitmap.recycle()
                }
            }
            capture("opaque.png")
            scenario.onActivity {
                fun tree(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { tree(view.getChildAt(it)) } else emptyList()
                val views = tree(parent!!)
                File(out, "renderer.txt").writeText(views.joinToString("\n") { it.javaClass.name })
                if (InstrumentationRegistry.getArguments().getString("requireTexture") == "true") {
                    assertTrue(views.any { it is TextureView })
                    assertFalse(views.any { it is SurfaceView })
                }
                parent!!.alpha = .5f
            }
            Thread.sleep(300)
            capture("half-alpha.png")
            scenario.onActivity { parent!!.alpha = 0f }
            Thread.sleep(300)
            capture("transparent.png")
            scenario.onActivity { call(controller!!, "onPause"); call(controller!!, "onResume"); call(controller!!, "release"); call(controller!!, "release") }
        } finally { scenario.close() }
    }
}
