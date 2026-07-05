package fr.bluecxt.core.utils

object Log {
    private val isAndroid: Boolean by lazy {
        try {
            Class.forName("android.util.Log")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    fun v(tag: String, msg: String) {
        if (isAndroid) {
            android.util.Log.v(tag, msg)
        } else {
            println("[$tag] VERBOSE: $msg")
        }
    }

    fun d(tag: String, msg: String) {
        if (isAndroid) {
            android.util.Log.d(tag, msg)
        } else {
            println("[$tag] DEBUG: $msg")
        }
    }

    fun i(tag: String, msg: String) {
        if (isAndroid) {
            android.util.Log.i(tag, msg)
        } else {
            println("[$tag] INFO: $msg")
        }
    }

    fun w(tag: String, msg: String) {
        if (isAndroid) {
            android.util.Log.w(tag, msg)
        } else {
            println("[$tag] WARN: $msg")
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
