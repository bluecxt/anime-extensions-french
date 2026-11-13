// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.animesama

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

/**
 * Entry point for `https://anime-sama.to/catalogue/<item>` links opened from a browser.
 *
 * The activity does nothing but forward the intent to the main Aniyomi process as a targeted
 * search, then finishes immediately.
 */
class AnimeSamaUrlActivity : Activity() {

    /**
     * Log tag based on the class name, used to report intent problems.
     */
    private val tag = javaClass.simpleName

    /**
     * Parses the received URI and hands the search over to Aniyomi.
     *
     * The second path segment is taken as the work slug. The activity always finishes, including
     * when the URI cannot be read or when Aniyomi is not installed.
     *
     * @param savedInstanceState restored state, unused.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pathSegments = intent?.data?.pathSegments
        if (pathSegments != null && pathSegments.size > 1) {
            val item = pathSegments[1]
            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.ANIMESEARCH"
                putExtra("query", "${AnimeSama.PREFIX_SEARCH}$item")
                putExtra("filter", packageName)
            }

            try {
                startActivity(mainIntent)
            } catch (e: ActivityNotFoundException) {
                Log.e(tag, e.toString())
            }
        } else {
            Log.e(tag, "could not parse uri from intent $intent")
        }

        finish()
        exitProcess(0)
    }
}
