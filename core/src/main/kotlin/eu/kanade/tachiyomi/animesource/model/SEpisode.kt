@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.animesource.model

interface SEpisode {
    var url: String
    var name: String
    var date_upload: Long
    var episode_number: Float
    var scanlator: String?

    // Add metadata fields used in AnimeSamaFan
    var preview_url: String?
    var summary: String?

    companion object {
        fun create(): SEpisode = SEpisodeImpl()
    }
}

class SEpisodeImpl : SEpisode {
    override var url: String = ""
    override var name: String = ""
    override var date_upload: Long = 0
    override var episode_number: Float = 0f
    override var scanlator: String? = null
    override var preview_url: String? = null
    override var summary: String? = null
}
