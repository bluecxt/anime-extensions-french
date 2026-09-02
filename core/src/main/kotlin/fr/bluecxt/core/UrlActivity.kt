// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

open class UrlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent.data
        if (data != null) {
            val searchIntent = Intent("eu.kanade.tachiyomi.ANIMESEARCH").apply {
                putExtra("query", data.toString())
                putExtra("filter", packageName)
            }
            try {
                startActivity(searchIntent)
            } catch (e: ActivityNotFoundException) {
                Log.e(packageName, "Unable to launch activity", e)
            }
        }
        finish()
        exitProcess(0)
    }
}
