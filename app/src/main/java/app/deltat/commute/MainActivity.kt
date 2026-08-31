package app.deltat.commute

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import app.deltat.commute.ui.AppViewModel
import app.deltat.commute.ui.DeltaTApp
import app.deltat.commute.ui.DeltaTTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DeltaTTheme {
                val viewModel: AppViewModel = viewModel()
                var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
                var permissionRevision by remember { mutableIntStateOf(0) }
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) permissionRevision++
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                val required = remember {
                    buildList {
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                        add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }.toTypedArray()
                }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    permissionRevision++
                    if (hasRequiredPermissions()) pendingAction?.invoke()
                    pendingAction = null
                }
                val permissionGranted = remember(permissionRevision) { hasRequiredPermissions() }
                DeltaTApp(
                    viewModel = viewModel,
                    permissionGranted = permissionGranted,
                    withPermissions = { action ->
                        if (hasRequiredPermissions()) action()
                        else {
                            pendingAction = action
                            launcher.launch(required)
                        }
                    },
                )
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PermissionChecker.PERMISSION_GRANTED
        val notification = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PermissionChecker.PERMISSION_GRANTED
        return fine && notification
    }
}
