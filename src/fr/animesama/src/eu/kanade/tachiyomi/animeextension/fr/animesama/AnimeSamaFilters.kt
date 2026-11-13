// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.animesama

/**
 * Static values of the catalogue search filters.
 *
 * Each array pairs the label shown to the user (`first`) with the value sent to the site
 * (`second`). The lists live in the code because the site does not expose them through an API:
 * parsing them would cost one extra request every time the search screen opens.
 */
object AnimeSamaFilters {

    /**
     * Values of the `tri` parameter: catalogue sort order.
     */
    val TRI_OPTIONS = arrayOf(
        "Titre (A → Z)" to "titre",
        "Derniers ajouts sur le site" to "ajout",
        "Sortie : les plus récentes" to "recent",
        "Sortie : les plus anciennes" to "ancien",
        "Nombre d'épisodes" to "episodes",
    )

    /**
     * Values of the `type[]` parameter: kind of content (anime, film, others).
     */
    val TYPE_OPTIONS = listOf(
        "Anime" to "Anime",
        "Film" to "Film",
        "Autres" to "Autres",
    )

    /**
     * Values of the `langue[]` parameter: languages available on the site.
     */
    val LANGUAGE_OPTIONS = listOf(
        "VOSTFR" to "VOSTFR",
        "VF" to "VF",
        "VASTFR" to "VASTFR",
        "VCN" to "VCN",
    )

    /**
     * Values of the `current[]` parameter: broadcast status of the work.
     */
    val STATUT_OPTIONS = listOf(
        "En cours" to "En cours",
        "Terminé" to "Terminé",
    )

    /**
     * Values of the `genre[]` parameter: genres offered by the catalogue.
     *
     * The order follows the site, so the extension stays consistent with the web interface.
     */
    val GENRE_OPTIONS = listOf(
        "Action" to "Action",
        "Adolescence" to "Adolescence",
        "Aliens / Extra-terrestres" to "Aliens / Extra-terrestres",
        "Amitié" to "Amitié",
        "Amour" to "Amour",
        "Apocalypse" to "Apocalypse",
        "Art" to "Art",
        "Arts martiaux" to "Arts martiaux",
        "Assassinat" to "Assassinat",
        "Autre monde" to "Autre monde",
        "Aventure" to "Aventure",
        "Combats" to "Combats",
        "Comédie" to "Comédie",
        "Crime" to "Crime",
        "Cyberpunk" to "Cyberpunk",
        "Danse" to "Danse",
        "Démons" to "Démons",
        "Détective" to "Détective",
        "Donghua" to "Donghua",
        "Dragon" to "Dragon",
        "Drame" to "Drame",
        "Ecchi" to "Ecchi",
        "Ecole" to "Ecole",
        "Elfe" to "Elfe",
        "Enquête" to "Enquête",
        "Famille" to "Famille",
        "Fantastique" to "Fantastique",
        "Fantasy" to "Fantasy",
        "Fantômes" to "Fantômes",
        "Futur" to "Futur",
        "Gastronomie" to "Gastronomie",
        "Ghibli" to "Ghibli",
        "Guerre" to "Guerre",
        "Harcèlement" to "Harcèlement",
        "Harem" to "Harem",
        "Harem inversé" to "Harem inversé",
        "Histoire" to "Histoire",
        "Historique" to "Historique",
        "Homosexualité" to "Homosexualité",
        "Horreur" to "Horreur",
        "Isekai" to "Isekai",
        "Jeunesse" to "Jeunesse",
        "Jeux" to "Jeux",
        "Jeux vidéo" to "Jeux vidéo",
        "Josei" to "Josei",
        "Journalisme" to "Journalisme",
        "Kaï" to "Kaï",
        "LGBT+" to "LGBT+",
        "Mafia" to "Mafia",
        "Magical girl" to "Magical girl",
        "Magie" to "Magie",
        "Maladie" to "Maladie",
        "Mariage" to "Mariage",
        "Mature" to "Mature",
        "Mechas" to "Mechas",
        "Médiéval" to "Médiéval",
        "Militaire" to "Militaire",
        "Monde virtuel" to "Monde virtuel",
        "Monstres" to "Monstres",
        "Musique" to "Musique",
        "Mystère" to "Mystère",
        "Nekketsu" to "Nekketsu",
        "Ninjas" to "Ninjas",
        "Nostalgie" to "Nostalgie",
        "Paranormal" to "Paranormal",
        "Philosophie" to "Philosophie",
        "Pirates" to "Pirates",
        "Police" to "Police",
        "Politique" to "Politique",
        "Post-apocalyptique" to "Post-apocalyptique",
        "Pouvoirs psychiques" to "Pouvoirs psychiques",
        "Préhistoire" to "Préhistoire",
        "Prison" to "Prison",
        "Psychologique" to "Psychologique",
        "Quotidien" to "Quotidien",
        "Religion" to "Religion",
        "Réincarnation / Transmigration" to "Réincarnation / Transmigration",
        "Romance" to "Romance",
        "Samouraïs" to "Samouraïs",
        "School Life" to "School Life",
        "Science-Fantasy" to "Science-Fantasy",
        "Science-fiction" to "Science-fiction",
        "Scientifique" to "Scientifique",
        "Seinen" to "Seinen",
        "Shôjo" to "Shôjo",
        "Shôjo-Ai" to "Shôjo-Ai",
        "Shônen" to "Shônen",
        "Shônen-Ai" to "Shônen-Ai",
        "Slice of Life" to "Slice of Life",
        "Société" to "Société",
        "Sport" to "Sport",
        "Super pouvoirs" to "Super pouvoirs",
        "Super-héros" to "Super-héros",
        "Surnaturel" to "Surnaturel",
        "Survie" to "Survie",
        "Survival game" to "Survival game",
        "Technologies" to "Technologies",
        "Thriller" to "Thriller",
        "Tournois" to "Tournois",
        "Travail" to "Travail",
        "Vampires" to "Vampires",
        "Vengeance" to "Vengeance",
        "Voyage" to "Voyage",
        "Voyage temporel" to "Voyage temporel",
        "Webcomic" to "Webcomic",
        "Yakuza" to "Yakuza",
        "Yaoi" to "Yaoi",
        "Yokai" to "Yokai",
        "Yuri" to "Yuri",
    )
}
