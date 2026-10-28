package cz.tadeasf.clipvault.sync.android

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import cz.tadeasf.clipvault.ui.sync.QrImage

/** The modules of [text]'s QR code without a quiet zone (the UI draws it); same settings as the desktop's `pair`. */
fun qrImage(text: String): QrImage {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0))
    return QrImage(m.width, BooleanArray(m.width * m.height) { m[it % m.width, it / m.width] })
}
