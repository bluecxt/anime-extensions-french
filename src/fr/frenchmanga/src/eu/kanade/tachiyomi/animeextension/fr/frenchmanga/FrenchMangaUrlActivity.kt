package eu.kanade.tachiyomi.animeextension.fr.frenchmanga

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

class FrenchMangaUrlActivity : Activity() {

    private val tag = javaClass.simpleName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        if (data != null) {
            val query = data.getQueryParameter("newsid")
            if (query != null) {
                val mainIntent = Intent().apply {
                    action = "eu.kanade.tachiyomi.ANIMESEARCH"
                    putExtra("query", "${FrenchManga.PREFIX_SEARCH}$query")
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
