package fr.bluecxt.core.utils

object Log {
    private val isAndroid: Boolean by lazy {
        System.getProperty("java.vendor")?.contains("Android", ignoreCase = true) == true
    }

    fun v(tag: String, msg: String, tr: Throwable? = null) {
        if (isAndroid) {
            android.util.Log.v(tag, msg, tr)
        } else {
            println("[$tag] VERBOSE: $msg")
            tr?.printStackTrace()
        }
    }

    fun d(tag: String, msg: String, tr: Throwable? = null) {
        if (isAndroid) {
            android.util.Log.d(tag, msg, tr)
        } else {
            println("[$tag] DEBUG: $msg")
            tr?.printStackTrace()
        }
    }

    fun i(tag: String, msg: String, tr: Throwable? = null) {
        if (isAndroid) {
            android.util.Log.i(tag, msg, tr)
        } else {
            println("[$tag] INFO: $msg")
            tr?.printStackTrace()
        }
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (isAndroid) {
            android.util.Log.w(tag, msg, tr)
        } else {
            println("[$tag] WARN: $msg")
            tr?.printStackTrace()
        }
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (isAndroid) {
            android.util.Log.e(tag, msg, tr)
        } else {
            System.err.println("[$tag] ERROR: $msg")
            tr?.printStackTrace()
        }
    }
}
