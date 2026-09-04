// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.extractors

import okhttp3.OkHttpClient

class StreamixExtractor(client: OkHttpClient) : VidaraExtractor(client) {
    override val apiPath = "/ajax/stream"
}
