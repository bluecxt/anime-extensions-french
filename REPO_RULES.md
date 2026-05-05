# 📜 Harmonization Rules for French Extensions

This document defines the mandatory standards for all Aniyomi extensions in the bluecxt francophone repository. These rules ensure a consistent and professional user experience.

## 1. Episode Formatting
- **Standard Terminology:** Use bracketed keywords recognized by AniZen to force grouping: `[S1]` (for Season 1), `[Movie]`, `[OAV]`, `[ONA]`, `[Special]`.
- **Name Structure:** `[S1] Episode Y - [Title]` or simply `Episode Y - [Title]`.
- **Special Content:** Always prefix with `[Movie]`, `[OAV]`, or `[Special]` (e.g., `[Movie] Episode 1 - [Title]`).
- **Single Season:** If the anime has only one season and no OAV/Special, omit the `[S1]` prefix.
- **No Redundancy:** If the title provided by the site already contains the episode number, do not repeat `Episode X` at the beginning.

## 2. Video Source Labels
- **Standard Format:** `(Language) Server - Quality` or `(Language) Team Server - Quality`.
- **Language Tags:** `(VOSTFR)`, `(VF)`, `(VA)`.
- **Cleaning (Regex):**
    - Remove server name repetitions (e.g., `Vidmoly - Vidmoly` -> `Vidmoly`).
    - Remove file weight information (e.g., `- 300MB`).
    - Remove technical suffixes (`:default`, `default`).
    - Include the fansub team name if available (mandatory for Anime-Ultime).

## 3. Sorting and Prioritization
- **Language:** Sort videos based on the language preference defined in the extension settings.
- **Resolution:** For the same language, sort by descending resolution (e.g., `1080p` > `720p` > `480p`).
- **Quality Parsing:** Use a Regex to extract the pixel count (e.g., `(\d+)p`) to ensure reliable numerical sorting.

## 4. Metadata (TMDB)
- **Release Date:** Always use the exact French label `Date de sortie :` (at the beginning of the description) to guarantee consistency across sources.
- **Studios/Creators:** Use the `Artist` field (for studios) and `Author` field (for creators/mangakas) provided by the TMDB engine.
- **Season Overflow:** If a season on the site contains more episodes than listed on TMDB, map the extra episodes to TMDB's Season 0 (Specials/OAV).

## 5. Extension-Specific Mechanics

Some extensions implement advanced logic to handle specific site structures or filtering needs.

### Anime-Sama
- **Smart Title Parsing:** Automatically detects keywords like "Saison X", "OAV", or "FILM" in the title to accurately target the correct TMDB category (TV Season, Season 0, or Movie).
- **Cumulative OAV Offset:** When a season overflow occurs in Season > 1, the extension calculates a cumulative offset by counting "missing" episodes (OAVs) in all previous seasons. This ensures that overflow episodes are mapped to the correct sequential index in TMDB's Season 0.
- **OAV Content Filtering:** For OAV/Special pages, metadata is filtered to only include episodes with a valid summary, skipping technical or bonus content (interviews, clean openings) often found on TMDB.

### Anime-Sama-Fan
- **Special Episode Filtering:** Uses a specialized Regex (`ignoredSpecialRegex`) to automatically exclude non-fiction content such as *special broadcasts, video comments, behind the scenes, recaps, trailers, or PVs*.
- **Hybrid Season Mapping:** Handles cases where seasons are split on the site but merged on TMDB by calculating cumulative episode offsets across multiple site seasons.

### Anime-Ultime
- **Fansub Attribution:** Mandatorily includes the fansub team name in the video labels to respect the community standards of the source site.
- **Legacy Nomenclature:** Specifically handles "ONA" and "Special" tags found in the site's raw numbering to map them to standard bracketed prefixes.

### Les Poroïniens
- **Parallel Scanning:** Uses a parallelized scanning approach (via Kotlin Coroutines `async`) during catalogue parsing to handle high-latency API responses without blocking the UI thread.
- **Smart Episode Normalization:** Automatically detects and adjusts episode numbers for "half-episodes" (e.g., Episode 1.5) by calculating sequential offsets to maintain TMDB alignment.

### SouthTV
- **Hardcoded TMDB Mapping:** For specific high-priority series where automatic title matching fails, the extension uses hardcoded TMDB ID mappings to ensure 100% metadata accuracy.
