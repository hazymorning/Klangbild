package io.github.hazymorning.klangbild.data

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Pictures decoded by Android's [BitmapFactory], subsampled while they decode. */
object AndroidPictureDecoder : PictureDecoder {
    override fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }
}
