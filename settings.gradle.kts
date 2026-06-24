/**
 * Add or remove modules to load as needed for local development here.
 */
// loadAllIndividualExtensions()
loadIndividualExtension("all", "torrentio")
loadIndividualExtension("all", "torrentioanime")
loadIndividualExtension("fr", "animesamafan")
loadIndividualExtension("fr", "waveanime")
loadIndividualExtension("fr", "southtv")
loadIndividualExtension("fr", "lesporoiniens")
loadIndividualExtension("fr", "animesama")
loadIndividualExtension("fr", "franime")
loadIndividualExtension("fr", "animoflix")
loadIndividualExtension("fr", "wiflix")
loadIndividualExtension("fr", "extensiontest")
loadIndividualExtension("fr", "adkami")
loadIndividualExtension("fr", "voiranime")
loadIndividualExtension("fr", "frenchanime")

/**
 * ===================================== COMMON CONFIGURATION ======================================
 */
include(":core")

// Load all modules under /lib
// File(rootDir, "lib").eachDir { include("lib:${it.name}") }

// Load all modules under /lib-multisrc
//File(rootDir, "lib-multisrc").eachDir { include("lib-multisrc:${it.name}") }

/**
 * ======================================== HELPER FUNCTION ========================================
 */
fun loadAllIndividualExtensions() {
    File(rootDir, "src").eachDir { dir ->
        dir.eachDir { subdir ->
            loadIndividualExtension(dir.name, subdir.name)
        }
    }
}
fun loadIndividualExtension(lang: String, name: String) {
    include("src:$lang:$name")
}

fun File.eachDir(block: (File) -> Unit) {
    val files = listFiles() ?: return
    for (file in files) {
        val isIgnored = File(file, ".ignore").exists()

        if (file.isDirectory && !isIgnored && file.name != ".gradle" && file.name != "build") {
            block(file)
        }
    }
}
