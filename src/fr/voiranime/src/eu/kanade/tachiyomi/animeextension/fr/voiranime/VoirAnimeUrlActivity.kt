package eu.kanade.tachiyomi.animeextension.fr.voiranime

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Springboard that accepts https://voiranime.io/anime/<item> intents
 * and redirects them to the main Aniyomi process.
 */
class VoirAnimeUrlActivity : Activity() {

    private val tag = javaClass.simpleName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        Log.d(tag, "URL received: $data")
        val pathSegments = data?.pathSegments
        if (!pathSegments.isNullOrEmpty()) {
            val item = pathSegments.last { it.isNotEmpty() }

            // Broad detection: if URL contains -vostfr or -vf, it's likely an episode
            val isEpisode = item.contains("-vostfr", ignoreCase = true) || item.contains("-vf", ignoreCase = true)

            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.ANIMESEARCH"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (isEpisode) {
                    // Clean slug by removing episode number and language
                    val cleanQuery = item.replace(Regex("-\\d+-(?:vostfr|vf).*", RegexOption.IGNORE_CASE), "")
                        .replace(Regex("-(?:vostfr|vf).*", RegexOption.IGNORE_CASE), "")
                        // Separate letters from numbers (e.g., zero4th -> zero 4th)
                        .replace(Regex("([a-zA-Z])(\\d)"), "$1 $2")
                        .replace(Regex("(\\d)([a-zA-Z])"), "$1 $2")
                        .replace("-", " ")
                    putExtra("query", cleanQuery)
                } else {
                    putExtra("query", item.replace("-", " "))
                }
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
    }
}
