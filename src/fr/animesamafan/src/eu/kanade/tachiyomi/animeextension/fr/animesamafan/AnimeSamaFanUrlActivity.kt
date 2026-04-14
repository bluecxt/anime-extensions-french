package eu.kanade.tachiyomi.animeextension.fr.animesamafan

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log

class AnimeSamaFanUrlActivity : Activity() {

    private val tag = javaClass.simpleName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        val pathSegments = data?.pathSegments

        if (pathSegments != null && pathSegments.size >= 2) {
            val type = pathSegments[0] // "anime" ou "episode"

            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.ANIMESEARCH"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

                val slug = pathSegments[1].replace(Regex("^\\d+-"), "")
                    .replace(".html", "", ignoreCase = true)

                val cleanQuery = if (type.equals("episode", ignoreCase = true)) {
                    slug.replace(Regex("-episode-\\d+.*", RegexOption.IGNORE_CASE), "")
                        .replace("-", " ")
                } else {
                    slug
                }

                putExtra("query", cleanQuery)
                putExtra("filter", packageName)
            }

            try {
                startActivity(mainIntent)
            } catch (e: ActivityNotFoundException) {
                Log.e(tag, e.toString())
            }
        }
        finish()
    }
}
