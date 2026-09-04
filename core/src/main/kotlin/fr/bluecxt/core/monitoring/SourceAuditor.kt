// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.monitoring

import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object SourceAuditor {

    private fun isValidHttpUrl(baseUrl: String, rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        val full = if (rawUrl.startsWith("http")) rawUrl else "$baseUrl${if (rawUrl.startsWith("/")) "" else "/"}$rawUrl"
        return full.toHttpUrlOrNull() != null
    }

    // ============================== SAnime (Details) ==============================

    fun SAnime.checkAndReportIncompleteness(
        baseUrl: String,
        getAnimeUrl: (SAnime) -> String,
        extensionName: String? = null,
        extensionVersion: String? = null,
    ) {
        val resolvedPath = try {
            getAnimeUrl(this).removePrefix(baseUrl)
        } catch (_: Exception) {
            this.url
        }
        checkAndReportIncompleteness(baseUrl, resolvedPath, extensionName, extensionVersion)
    }

    fun SAnime.checkAndReportIncompleteness(
        baseUrl: String,
        urlPath: String,
        extensionName: String? = null,
        extensionVersion: String? = null,
    ) {
        val issues = mutableListOf<String>()
        if (status == SAnime.UNKNOWN) issues.add("status is UNKNOWN (0)")
        if (description.isNullOrBlank()) issues.add("description is blank")
        if (!isValidHttpUrl(baseUrl, thumbnail_url)) issues.add("thumbnail_url is blank or invalid")
        if (genre.isNullOrBlank()) issues.add("genre is blank")
        if (author.isNullOrBlank()) issues.add("author/studio is blank")
        if (!isValidHttpUrl(baseUrl, urlPath)) issues.add("anime URL is blank or invalid")

        if (issues.isNotEmpty()) {
            val details = buildList {
                add("Anime: $title")
                add("Issues Detected: ${issues.joinToString(", ")}")
                add("Status Code: $status")
                add("Genre: ${genre.orEmpty()}")
                add("Studio: ${author.orEmpty()}")
                add("Cover URL: ${thumbnail_url.orEmpty()}")
                add("Description: ${description.orEmpty().take(120)}...")
            }
            ErrorWebhook.sendWebhook(
                baseUrl = baseUrl,
                url = "$baseUrl$urlPath",
                additionalContext = details,
                extensionName = extensionName,
                extensionVersion = extensionVersion,
            )
        }
    }

    // ============================== Seasons ==============================

    fun List<SAnime>.checkAndReportSeasonIssues(
        baseUrl: String,
        animeTitle: String,
        getAnimeUrl: (SAnime) -> String,
        extensionName: String? = null,
        extensionVersion: String? = null,
    ): List<SAnime> {
        val invalidSeasons = filter { season ->
            val resolvedUrl = try {
                getAnimeUrl(season)
            } catch (_: Exception) {
                season.url
            }
            season.title.isBlank() || !isValidHttpUrl(baseUrl, resolvedUrl)
        }
        if (invalidSeasons.isNotEmpty()) {
            ErrorWebhook.sendWebhook(
                baseUrl = baseUrl,
                url = baseUrl,
                additionalContext = listOf(
                    "Anime: $animeTitle",
                    "Issue: ${invalidSeasons.size} season(s) have blank title or invalid URL",
                ),
                extensionName = extensionName,
                extensionVersion = extensionVersion,
            )
        }
        return this
    }

    fun List<SAnime>.checkAndReportSeasonIssues(
        baseUrl: String,
        urlPath: String,
        animeTitle: String,
        extensionName: String? = null,
        extensionVersion: String? = null,
    ): List<SAnime> {
        val invalidSeasons = filter { it.title.isBlank() || !isValidHttpUrl(baseUrl, it.url) }
        if (invalidSeasons.isNotEmpty()) {
            ErrorWebhook.sendWebhook(
                baseUrl = baseUrl,
                url = "$baseUrl$urlPath",
                additionalContext = listOf(
                    "Anime: $animeTitle",
                    "Issue: ${invalidSeasons.size} season(s) have blank title or invalid URL",
                ),
                extensionName = extensionName,
                extensionVersion = extensionVersion,
            )
        }
        return this
    }

    // ============================== Episodes ==============================

    fun List<SEpisode>.checkAndReportEpisodeIssues(
        baseUrl: String,
        urlPath: String,
        animeTitle: String,
        extensionName: String? = null,
        extensionVersion: String? = null,
    ): List<SEpisode> {
        val invalidEpisodes = filter { it.name.isBlank() || it.url.isBlank() }
        if (invalidEpisodes.isNotEmpty()) {
            ErrorWebhook.sendWebhook(
                baseUrl = baseUrl,
                url = "$baseUrl$urlPath",
                additionalContext = listOf(
                    "Anime: $animeTitle",
                    "Issue: ${invalidEpisodes.size} episode(s) have blank name or URL",
                ),
                extensionName = extensionName,
                extensionVersion = extensionVersion,
            )
        }
        return this
    }

    // ============================== Hosters ==============================

    fun List<Hoster>.checkAndReportHosterIssues(
        baseUrl: String,
        urlPath: String,
        episodeName: String,
        checkInternalData: Boolean = true,
        extensionName: String? = null,
        extensionVersion: String? = null,
    ): List<Hoster> {
        val invalidHosters = filter {
            it.hosterName.isBlank() || (checkInternalData && it.internalData.isBlank())
        }
        if (invalidHosters.isNotEmpty()) {
            ErrorWebhook.sendWebhook(
                baseUrl = baseUrl,
                url = "$baseUrl$urlPath",
                additionalContext = listOf(
                    "Episode: $episodeName",
                    "Issue: ${invalidHosters.size} hoster(s) have blank hosterName or internalData",
                ),
                extensionName = extensionName,
                extensionVersion = extensionVersion,
            )
        }
        return this
    }

    // ============================== Videos ==============================

    fun List<Video>.checkAndReportVideoIssues(
        baseUrl: String,
        urlPath: String,
        hosterName: String,
        extensionName: String? = null,
        extensionVersion: String? = null,
    ): List<Video> {
        val invalidVideos = filter {
            it.videoUrl.isBlank() || it.videoUrl.toHttpUrlOrNull() == null
        }
        if (invalidVideos.isNotEmpty()) {
            val sampleUrl = invalidVideos.firstOrNull()?.videoUrl?.take(60) ?: "blank"
            ErrorWebhook.sendWebhook(
                baseUrl = baseUrl,
                url = "$baseUrl$urlPath",
                additionalContext = listOf(
                    "Hoster: $hosterName",
                    "Issue: ${invalidVideos.size} video(s) have invalid or malformed videoUrl (sample: '$sampleUrl')",
                ),
                extensionName = extensionName,
                extensionVersion = extensionVersion,
            )
        }
        return this
    }
}
