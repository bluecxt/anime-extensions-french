package eu.kanade.tachiyomi.animeextension.fr.southtv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

class SouthTVUrlActivity : Activity() {

    private val tag = "SouthTVUrlActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mainIntent = Intent().apply {
            action = "eu.kanade.tachiyomi.ANIMESEARCH"
            putExtra("filter", packageName)
        }
        try {
            startActivity(mainIntent)
        } catch (e: ActivityNotFoundException) {
            Log.e(tag, e.toString())
        }
        finish()
        exitProcess(0)
    }
}
