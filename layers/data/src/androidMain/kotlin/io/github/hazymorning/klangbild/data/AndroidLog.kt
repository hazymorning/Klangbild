package io.github.hazymorning.klangbild.data

/** Android's log, under the tag `Klangbild`. */
object AndroidLog : Log {
    private const val TAG = "Klangbild"

    override fun info(message: String) {
        android.util.Log.i(TAG, message)
    }

    override fun warn(message: String) {
        android.util.Log.w(TAG, message)
    }
}
