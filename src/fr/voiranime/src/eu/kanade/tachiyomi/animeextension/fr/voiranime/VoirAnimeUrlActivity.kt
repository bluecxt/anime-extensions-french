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
        Log.d(tag, "URL reçue : $data")
        val pathSegments = data?.pathSegments
        if (pathSegments != null && pathSegments.isNotEmpty()) {
            val item = pathSegments.last { it.isNotEmpty() }

            // On vérifie si c'est un épisode (contient des chiffres suivis de -vostfr ou -vf)
            val isEpisode = item.contains(Regex("-\\d+-(?:vostfr|vf)", RegexOption.IGNORE_CASE))

            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.ANIMESEARCH"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (isEpisode) {
                    // Si c'est un épisode, on nettoie le nom pour faire une recherche textuelle
                    val cleanQuery = item.replace(Regex("-\\d+-(?:vostfr|vf).*", RegexOption.IGNORE_CASE), "")
                        .replace("-", " ")
                    putExtra("query", cleanQuery)
                } else {
                    // Si c'est une série, on utilise le préfixe ID pour l'ouvrir directement
                    putExtra("query", "${VoirAnime.PREFIX_SEARCH}$item")
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
