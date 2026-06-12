package fr.bluecxt.core.extractors

import okhttp3.OkHttpClient

class StreamixExtractor(client: OkHttpClient) : VidaraExtractor(client) {
    override val apiPath = "/ajax/stream"
}
