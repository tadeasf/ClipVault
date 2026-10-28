package cz.tadeasf.clipvault.ui.sync

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.delay

@Composable
fun PairingScreen(viewModel: PairingViewModel, scanner: @Composable (Modifier, (String) -> Unit) -> Unit, back: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val prompt by viewModel.prompt.collectAsState()
    DisposableEffect(viewModel) { onDispose { viewModel.reset() } }
    Column(
        Modifier.safeDrawingPadding().fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = back) { Text("← Settings") }
        Text("Pair a device", style = MaterialTheme.typography.titleLarge)
        when (val current = state) {
            PairingState.ChooseMethod -> {
                Methods(viewModel)
                prompt?.let { PermissionNotice(it, viewModel::continuePermission, viewModel::dismissPermission, viewModel::openAppSettings) }
            }
            PairingState.Scanning -> {
                Text("Point the camera at the QR code the other device shows.")
                scanner(Modifier.fillMaxWidth().aspectRatio(1f), viewModel::scanned)
            }
            PairingState.EnteringCode -> CodeEntry(viewModel::submitCode)
            is PairingState.Running -> Progress(current.progress, viewModel, back)
        }
    }
}

@Composable
private fun Methods(viewModel: PairingViewModel) {
    Text("On the other device, run clipvault-agent pair (QR) or pair --code (6 digits), then:")
    Method("Scan its QR code", "pairScan", viewModel::scan)
    Method("Enter its 6-digit code", "pairEnterCode", viewModel::enterCode)
    Text("Or let the other device find this phone:", style = MaterialTheme.typography.bodySmall)
    Method("Show a QR code", "pairShowQr", viewModel::showQr)
    Method("Show a 6-digit code", "pairShowCode", viewModel::showCode)
}

@Composable
private fun Method(label: String, tag: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag)) { Text(label) }
}

@Composable
private fun CodeEntry(submit: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.filter(Char::isDigit).take(PairingViewModel.CODE_LENGTH) },
        label = { Text("6-digit code") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        textStyle = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.fillMaxWidth().testTag("pairCodeField"),
    )
    Button(onClick = { submit(code) }, enabled = PairingViewModel.isCode(code)) { Text("Pair") }
}

@Composable
private fun Progress(progress: PairingProgress, viewModel: PairingViewModel, back: () -> Unit) {
    when (progress) {
        PairingProgress.Connecting -> {
            CircularProgressIndicator()
            Text("Connecting…")
        }
        is PairingProgress.ShowingQr -> {
            Text("Scan this with the other device.")
            QrCode(progress.qr)
            Countdown(progress.expiresAt)
        }
        is PairingProgress.ShowingCode -> {
            Text("Type this code on the other device.")
            Text(progress.code, style = MaterialTheme.typography.displayLarge, letterSpacing = 8.sp, modifier = Modifier.testTag("pairCode"))
            Countdown(progress.expiresAt)
        }
        is PairingProgress.Confirm -> {
            Text("Check that ${progress.peerName} shows the same six words.")
            progress.words.chunked(3).forEach { Text(it.joinToString("  "), style = MaterialTheme.typography.headlineMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.answer(true) }, modifier = Modifier.testTag("pairConfirm")) { Text("Confirm") }
                OutlinedButton(onClick = { viewModel.answer(false) }, modifier = Modifier.testTag("pairReject")) { Text("Reject") }
            }
        }
        PairingProgress.WaitingForPeer -> {
            CircularProgressIndicator()
            Text("Waiting for the other device to confirm…")
        }
        is PairingProgress.Paired -> {
            Text("Paired with ${progress.peerName}.", style = MaterialTheme.typography.titleMedium)
            Button(onClick = back) { Text("Done") }
        }
        is PairingProgress.Failed -> {
            Text("Pairing failed: ${progress.reason}", modifier = Modifier.testTag("pairFailed"))
            Button(onClick = viewModel::reset) { Text("Try again") }
        }
    }
}

/** Black modules on white with a 4-module quiet zone, snapped to whole pixels so no seams show between modules. */
@Composable
private fun QrCode(qr: QrImage) {
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f).testTag("pairQr")) {
        drawRect(Color.White)
        val modules = qr.size + 2 * QUIET_ZONE
        val cell = floor(size.minDimension / modules)
        val origin = (size.minDimension - cell * qr.size) / 2
        for (y in 0 until qr.size) {
            for (x in 0 until qr.size) {
                if (qr[x, y]) drawRect(Color.Black, Offset(origin + x * cell, origin + y * cell), Size(cell, cell))
            }
        }
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun Countdown(expiresAt: Long) {
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(expiresAt) {
        while (now < expiresAt) {
            delay(1_000)
            now = Clock.System.now().toEpochMilliseconds()
        }
    }
    val seconds = ((expiresAt - now) / 1_000).coerceAtLeast(0)
    Text(
        if (seconds > 0) "Expires in ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}" else "Expired",
        style = MaterialTheme.typography.bodySmall,
    )
}

private const val QUIET_ZONE = 4
