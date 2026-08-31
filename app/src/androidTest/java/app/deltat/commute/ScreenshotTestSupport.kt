package app.deltat.commute

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

internal fun ComposeContentTestRule.saveScreenshot(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val file = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), name)
    file.outputStream().use { output ->
        onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)
    }
    instrumentation.uiAutomation.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/$name").close()
}
