# 🔍 Plugin Health Audit — 23 August 2026

Live web investigation + article reading + code review of all 5 CloudStream
extensions in this repo, followed by the required fixes.

## TL;DR — 5/5 Plugins Working ✅

| # | Plugin | Old Domain | New / Current Domain | Status |
|---|--------|-----------|----------------------|--------|
| 1 | **KatMovieHD** (v42→**v43**) | `new.katmoviehd.top`, `*.cymru` ❌ dead | **`katmoviehd.mom`** ✅ | Working (fixed, self-heals via domains.json) |
| 2 | **KMMovies** (v9→**v10**) | `kmmovies.lol` / `.shop` ⚠️ redirect only | **`kmmovies.pics`** ✅ | Working (fixed) |
| 3 | **OlaMovies** (v2→**v3**) | `v3.olamovies.mov` ❌ parked | **`v4.olamovies.mov`** ✅ | Working (fixed, status 3→1) |
| 4 | **TheNextPlanet** (v4→**v5**) | `thenextplanet-official.space` ⚠️ banner-only | **`thenextplanet-official.site`** ✅ | Working (fixed) |
| 5 | **YupFlix** (v2, no change) | `watch.yupflix.org` ✅ still valid | (unchanged) | Working — no change needed |

## Evidence collected (live article/page reads on 2026-08-23)

- **KatMovieHD** — `katmoviehd.onl` domain-history reference lists **katmoviehd.mom** and
  **katmoviehd.watch** as owner-reported current (16-Aug-2026). `katmoviehd.mom` homepage
  shows 22-Aug-2026 uploads (`Bury the Devil 2026`, `Welcome to the Jungle 2026`…), same
  WordPress theme + `/category/dual-audio/`, `/category/dubbed-movie/` structure the
  provider scrapes → selectors still match. Old `.top`/`.cymru` are gone.
- **OlaMovies** — Official Telegram **@olamovies_officialv6**:
  *"Current Domain v4.olamovies.mov / Base Domain olamovies.dad"* (2026 pin).
  `v4.olamovies.mov` homepage had posts "**43 seconds ago**" at audit time — same WP
  theme, `article` + `h2.entry-title a` selectors intact, `/category/movies/hollywood/`,
  `/category/tv-series/…` main-page paths unchanged.
- **TheNextPlanet** — `thenextplanet-official.site` banner self-declares
  *"www.thenextplanet-official.site is our new Domain"*; homepage shows 22-Aug-2026
  uploads (`7 Dogs`, `Bury the Devil`); `/Bollywood/`, `/Hollywood/`, `/south-movies/`,
  `/Webseries/`, `/year/`, `/language/` and `?page=N` pagination all verified live.
  Old `.space` now only carries the redirect banner.
- **KMMovies** — `kmmovies.lol` 301-redirects to **`kmmovies.pics`** (fresh homepage:
  `Awarapan 2`, `Batwara 1947`…). `/category/movies/`, `/category/bollywood/`,
  `/category/hollywood/` verified. `kmmovies.shop` is dead; `.sbs`/`.fun` kept only as
  fallback candidates.
- **YupFlix** — `watch.yupflix.org` home + trending sections render live data
  (posters via TMDB + `img1.streamraiwind.stream` proxy). Site warns the watch-domain
  rotates monthly and points to `yupflix.com` as the bookmark owner page — no code
  change needed today.
- **Sister sites** used by the KatMovieHD provider — all verified alive with fresh posts:
  `moviesbaba.lol` ✅, `www.katdrama.net` ✅, `new.pikahd.co` ✅.

## Code changes applied

1. `domains.json` (root, runtime source fetched by the KatMovieHD plugin every 6h) and
   `target_repo/domains.json`: katmoviehd → `katmoviehd.mom` (+ `.watch` candidate);
   kmmovies → `kmmovies.pics` (+ `.lol/.sbs/.fun/.shop` fallbacks); thenextplanet →
   `thenextplanet-official.site` (+ `.space/.shop/.surf/tnp57.site` fallbacks);
   olamovies → `v4.olamovies.mov` (+ `v3`, `olamovies.dad`, `m.ol-am.top` fallbacks).
2. `KatMovieHDPlugin.kt`: `DEFAULT_MAIN_URL` → `https://katmoviehd.mom`; hardcoded
   `katmoviehd.watch` added as a last-resort candidate. Build bumped to **v43**
   (+ new icon domain, changelog in description).
3. `OlaMoviesProvider.kt` + `OlaMovies/build.gradle.kts`: mainUrl → `v4.olamovies.mov`;
   **v3**, status `3 (Beta)` → **`1 (Ok)`**.
4. `TheNextPlanet.kt` + gradle: mainUrl → `thenextplanet-official.site` (incl. AutoTest
   fixture URL); **v5**.
5. `KMMovies.kt` + gradle: mainUrl → `kmmovies.pics`; `isProviderPage()` host allow-list
   now covers `kmmovies.pics/.shop/.lol` for redirect tolerance; **v10**.
6. `README.md`: headline link updated to the live KatMovieHD domain.

## Notes for maintainers

- The daily **Site Watch** workflow (`scripts/site_monitor.py`) reads the same
  `domains.json` candidates — the new arrays keep it pointing at reality.
- If `watch.yupflix.org` rotates (site promises monthly rotation), update both
  `SITE_URL`/`API_BASE` origin handling in `YupFlix.kt` and `yupflix_candidates`.
- Builds: push to `main` triggers `build.yml`, which publishes the new `.cs3`
  artifacts + `plugins.json` to the `builds` branch. Version bumps above ensure
  in-app update prompts.
