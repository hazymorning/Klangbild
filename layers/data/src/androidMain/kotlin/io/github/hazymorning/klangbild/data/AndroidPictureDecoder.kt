package io.github.hazymorning.klangbild.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.hazymorning.klangbild.data.ArtworkLoader.Companion.MAX_DECODE_PX

/** Pictures decoded by Android's [BitmapFactory], subsampled while they decode. */
object AndroidPictureDecoder : PictureDecoder {
    override fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val w = bounds.outWidth
        val h = bounds.outHeight
        var sample = 1
        while (minOf(w, h) / (sample * 2) >= MAX_DECODE_PX / 2 && maxOf(w, h) / sample > MAX_DECODE_PX) sample *= 2
        // However odd its shape, no picture takes more memory than a square of MAX_DECODE_PX.
        while ((w / sample).toLong() * (h / sample) > MAX_DECODE_PX.toLong() * MAX_DECODE_PX) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }
}
