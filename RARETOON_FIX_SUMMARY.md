# Rare Toon India Provider Fix Summary

**Status:** ✅ Fixed, compiled, and pushed to `main`  
**Commits:** `e03d995` (v16), `658a142` (v16.1 follow-up), `83789ec` (v16.2 UA fix)  
**Plugin version:** `16`

---

## What was broken

1. **Search returned irrelevant results**  
   WordPress search matches inside post content, so a query like "doraemon" returned posts that merely linked to Doraemon content (e.g. *Hoppers*) instead of actual Doraemon titles.

2. **Duplicate video links**  
   Episode and movie posts contain both a **QuickWatch** (`/d/<code>`) and a **Download** (`/download/<code>`) link for the same bysekoze code, producing identical duplicate streams.

3. **Stale home-page categories**  
   The original category list was missing newer anime categories and included empty/unused ones.

4. **Weak metadata**  
   Load responses only had title + poster + raw plot. No year, genres/tags, or recommendations.

5. **Blocked-prefix bug**  
   `/doraemon` as a blocked prefix accidentally rejected legitimate posts like `/doraemon-movie-...`.

6. **ByseKozE extractor deprecation / fragility**  
   Used deprecated `SubtitleFile(...)` constructor and had no HTTP status / decryption error handling.

7. **Streams not playing (v16.1)**  
   Extractor links were missing `ExtractorLinkType.M3U8`, so CloudStream did not treat the bysekoze URL as HLS.

8. **Episode numbers missing (v16.1)**  
   The site places `Episode 01` in a separate HTML element from the QuickWatch link, so all episodes were unlabeled/mis-grouped.

9. **Desktop UA requirement (v16.2)**  
   Bysekoze signs stream URLs against the UA class used for the API call. Requesting the API with a **mobile UA** produced tokens that only worked with that exact mobile UA, so CloudStream/VLC/ExoPlayer got 404. Requesting with a **desktop UA** produces tokens that work with the player's default UA, ExoPlayer, VLC, and even no UA.

---

## What was fixed

### `RareToonIndiaProvider.kt`

| Area | Fix |
|------|-----|
| **Search** | Results are now filtered so the decoded title must contain at least one significant query word (fallback only if the query is empty). |
| **Episodes / Movies** | All bysekoze links are normalized to `https://bysekoze.com/d/<code>` and then deduplicated by code, keeping one playable link per actual video. |
| **Classification** | Improved `COLLECTION` detection and added richer `SEASON` heuristics. |
| **Home page** | Added working categories: Demon Slayer, Attack on Titan, My Hero Academia, Solo Leveling, Jujutsu Kaisen, Naruto, Naruto Shippuden; removed empty Shinchan categories. |
| **Metadata** | Extracts `year`, `tags`/`genres`, `recommendations` from in-content links, and a cleaner plot from excerpt/description. |
| **Error handling** | Wrapped network calls in `try/catch`, returns empty lists instead of crashing, and uses `ErrorLoadingException` for load failures. |
| **URL validation** | Fixed blocked-prefix logic (now uses trailing slashes for category pages) and made the host check follow the active `mainUrl`. |
| **Search types** | Uses `newAnimeSearchResponse`, `newTvSeriesSearchResponse`, and `newMovieSearchResponse` based on title/URL heuristics. |
| **Episode numbering** | (v16.1) Fixed because the site puts `Episode 01` in a separate `<strong>`/`<p>` from the QuickWatch link; previous parent-text parsing always returned `null`. Now we keep a rolling "current episode" marker while traversing the document. |
| **Poster** | (v16.1) Prefer smaller WordPress featured-image sizes to reduce image-loader failures. |

### `ByseKozEExtractor.kt`

| Area | Fix |
|------|-----|
| **Subtitles** | Replaced deprecated `SubtitleFile(...)` with suspend `newSubtitleFile(...)`. |
| **HTTP checks** | Verifies the API response is successful before parsing. |
| **Logging** | Tagged all logs with `ByseKozE` and added clearer failure messages. |
| **Decryption** | Added explicit key-size / IV / payload validation and try/catch around AES-GCM decryption. |
| **Quality** | Added 360p detection and passes the original referer through to extractor links. |
| **M3U8 type** | (v16.1) Links are now created with `ExtractorLinkType.M3U8` so CloudStream uses the HLS player. |
| **User-Agent** | (v16.1) Extractor links carry a browser `User-Agent`; the CDN edge returns 404 without one. |
| **Desktop UA** | (v16.2) Bysekoze tokens are UA-class specific. Switched API requests to a desktop Chrome UA so the resulting m3u8 works with CloudStream, ExoPlayer and VLC. |

### `RareToon/build.gradle.kts`

- Bumped `version = 16`.
- Updated plugin description to mention the v16 improvements.

---

## Verification performed

1. **Cloned** the repo using the provided PAT.
2. **Explored** project structure and located RareToon provider files.
3. **Reverse-engineered** `raretoonindia.in` via WordPress REST API and page HTML:
   - Confirmed `/wp-json/wp/v2/posts` and `/wp-json/wp/v2/categories` work.
   - Confirmed post content uses bysekoze QuickWatch/Download links.
   - Confirmed bysekoze `/api/videos/<code>` returns AES-256-GCM encrypted playback.
   - Decrypted a sample payload locally with Python to validate the key-derivation logic.
4. **Built** the project successfully with the modern CloudStream toolchain (AGP 9.1.1, Kotlin 2.3.21, Gradle 9.4.1, JDK 17).
5. **Simulated** parsing logic against real Doraemon Season 5 and All-Doraemon-Movies pages.
6. **Pushed** the commit to `main`.

---

## Files changed

```
RareToon/build.gradle.kts
RareToon/src/main/kotlin/com/arena/raretoon/ByseKozEExtractor.kt
RareToon/src/main/kotlin/com/arena/raretoon/RareToonIndiaProvider.kt
```

Total: `3 files changed, 517 insertions(+), 166 deletions(-)`

---

## Next steps for the user

1. Trigger the GitHub Actions **Build** workflow so CloudStream can fetch the new `.cs3` from the `builds` branch.
2. In CloudStream: remove and re-add the repo, then install/refresh the **Rare Toon India** plugin.
3. Test:
   - Home page sections load.
   - Search for `doraemon`, `attack on titan`, `shinchan`, `hoppers` returns relevant results.
   - A season page shows episodes numbered correctly.
   - A movie page plays a bysekoze stream.
