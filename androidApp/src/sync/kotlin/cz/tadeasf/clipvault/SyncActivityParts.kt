package cz.tadeasf.clipvault

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import cz.tadeasf.clipvault.ui.sync.PermissionGate
import cz.tadeasf.clipvault.ui.sync.SyncPermission
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred

/** Runtime permissions for sync, asked through the activity. Construct in onCreate (launcher registration). */
class ActivityPermissionGate(private val activity: ComponentActivity) : PermissionGate {
    private var pending: CompletableDeferred<Boolean>? = null
    private var asked: SyncPermission? = null
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pending?.complete(asked?.let(::isGranted) == true)
        pending = null
    }

    override fun isGranted(permission: SyncPermission): Boolean =
        required(permission).all { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override suspend fun request(permission: SyncPermission): Boolean {
        if (isGranted(permission)) return true
        val result = CompletableDeferred<Boolean>().also { pending = it }
        asked = permission
        launcher.launch(asked(permission))
        return result.await()
    }

    override fun openAppSettings() {
        activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)))
    }

    /** What must be granted. Approximate location hides the SSID, so the allowlist needs fine. */
    private fun required(permission: SyncPermission): List<String> = when (permission) {
        // Defined from Android 17 (API 37); earlier releases have no such permission and allow LAN access.
        SyncPermission.LOCAL_NETWORK -> if (Build.VERSION.SDK_INT >= 37) listOf(LOCAL_NETWORK) else emptyList()
        SyncPermission.CAMERA -> listOf(Manifest.permission.CAMERA)
        SyncPermission.LOCATION -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // Android 12+ only grants fine location when coarse is asked in the same request.
    private fun asked(permission: SyncPermission): Array<String> =
        (required(permission) + if (permission == SyncPermission.LOCATION) listOf(Manifest.permission.ACCESS_COARSE_LOCATION) else emptyList()).toTypedArray()

    private companion object {
        const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}

/** The back camera with a zxing QR decoder on each frame's luminance plane; [onText] runs on the main thread. */
@Composable
fun QrScanner(modifier: Modifier, onText: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onText)
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            executor.shutdown()
        }
    }
    AndroidView(modifier = modifier, factory = { ctx ->
        PreviewView(ctx).also { view ->
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val reader = QRCodeReader()
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { image ->
                    image.use { decode(reader, it) }?.let { text -> view.post { latest(text) } }
                }
                future.get().run {
                    unbindAll()
                    bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(ctx))
        }
    })
}

private fun decode(reader: QRCodeReader, image: ImageProxy): String? {
    val plane = image.planes[0]
    val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
    val source = PlanarYUVLuminanceSource(bytes, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
    return try {
        reader.decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true)).text
    } catch (_: ReaderException) {
        null
    } finally {
        reader.reset()
    }
}
