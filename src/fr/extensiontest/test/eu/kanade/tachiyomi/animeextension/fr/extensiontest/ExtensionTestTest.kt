package eu.kanade.tachiyomi.animeextension.fr.extensiontest

import fr.bluecxt.core.test.BaseExtensionTest
import okhttp3.OkHttpClient

class ExtensionTestTest :
    BaseExtensionTest(
        service = ExtensionTestService(
            client = OkHttpClient(),
            supportsLatest = true,
        ),
        searchQuery = "Test",
    )
