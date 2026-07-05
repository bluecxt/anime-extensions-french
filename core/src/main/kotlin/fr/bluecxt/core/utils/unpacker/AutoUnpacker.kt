package fr.bluecxt.core.utils.unpacker

import fr.bluecxt.core.utils.Log
import fr.bluecxt.core.utils.unpacker.Unpacker
import fr.bluecxt.core.utils.unpacker.jsunpacker.JsUnpacker

fun autoUnpacker(packedScript: String): String? = runCatching {
    val jsUnpacker = try {
        JsUnpacker.unpackAndCombine(packedScript)
    } catch (e: Exception) {
        Log.w("JsUnpacker", "autoUnpacker: ${e.message}", e)
        null
    }
    jsUnpacker ?: Unpacker.unpack(packedScript).takeIf(String::isNotBlank)
}.getOrNull()
