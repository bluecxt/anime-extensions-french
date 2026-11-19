package fr.bluecxt.core.anilist

internal object AnilistQueries {

    private const val MEDIA_FIELDS_FRAGMENT = """
		fragment MediaFields on Media {
			id
			idMal
			title {
				romaji
				english
				native
			}
			synonyms
			description(asHtml: false)
			format
			status(version: 2)
			episodes
			duration
			season
			seasonYear
			startDate {
				year
				month
				day
			}
			endDate {
				year
				month
				day
			}
			coverImage {
				extraLarge
				large
				medium
				color
			}
			bannerImage
			genres
			tags {
				name
				description
				rank
				isMediaSpoiler
				isGeneralSpoiler
			}
			averageScore
			meanScore
			popularity
			studios {
				nodes {
					id
					name
					isAnimationStudio
				}
			}
			staff(sort: [RELEVANCE, ID], perPage: 8) {
				edges {
					role
					node {
						name {
							full
							native
						}
					}
				}
			}
			trailer {
				id
				site
				thumbnail
			}
			nextAiringEpisode {
				episode
				airingAt
				timeUntilAiring
			}
			siteUrl
		}
	"""

    val MEDIA_BY_ID_QUERY: String = """
		query (${'$'}id: Int, ${'$'}idMal: Int) {
			Media(id: ${'$'}id, idMal: ${'$'}idMal, type: ANIME) {
				...MediaFields
			}
		}
		$MEDIA_FIELDS_FRAGMENT
    """.trimIndent()

    val SEARCH_MEDIA_QUERY: String = """
		query (${'$'}search: String!, ${'$'}perPage: Int = 10, ${'$'}year: Int, ${'$'}format: MediaFormat) {
			Page(page: 1, perPage: ${'$'}perPage) {
				media(
					search: ${'$'}search,
					type: ANIME,
					seasonYear: ${'$'}year,
					format: ${'$'}format,
					sort: [SEARCH_MATCH, POPULARITY_DESC]
				) {
					...MediaFields
				}
			}
		}
		$MEDIA_FIELDS_FRAGMENT
    """.trimIndent()
}
