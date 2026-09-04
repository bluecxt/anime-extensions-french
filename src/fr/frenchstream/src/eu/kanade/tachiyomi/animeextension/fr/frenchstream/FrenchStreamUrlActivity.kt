// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.frenchstream

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

class FrenchStreamUrlActivity : Activity() {

    private val tag = javaClass.simpleName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val data = intent?.data

        if (data != null) {
            val id = data.getQueryParameter("newsid")?.takeIf { it.all(Char::isDigit) }
                ?: data.pathSegments.lastOrNull()?.substringBefore("-")?.takeIf { it.all(Char::isDigit) }
            if (id != null) {
                val mainIntent = Intent().apply {
                    action = "eu.kanade.tachiyomi.ANIMESEARCH"
                    putExtra("query", "${FrenchStream.PREFIX_SEARCH}$id")
                    putExtra("filter", packageName)
                }
                try {
                    startActivity(mainIntent)
                } catch (e: ActivityNotFoundException) {
                    Log.e(tag, e.toString())
                }
            }
        }
        finish()
        exitProcess(0)
    }
}
