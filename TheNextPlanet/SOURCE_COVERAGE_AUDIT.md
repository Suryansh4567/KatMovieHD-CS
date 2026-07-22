# TheNextPlanet — Source-Coverage Audit (v4 follow-up)

**Scope:** cross-reference every hoster domain observed on the live
`thenextplanet-official.space` site (detail + unlock pages) against what
the current `loadLinks()` / `resolveUrl()` code actually handles.

**Crawl sample:** 22 unique titles (Hollywood + Bollywood + South + Web
series), including *Starman*, *Kantara*, *Kantara Chapter 1*,
*House of the Dragon S01*, *From S01/S02*, *Rambo III*, *Ladder 49*,
*Blue Lagoon: The Awakening*, *300: Rise of an Empire*, *Harry Potter
and the Chamber of Secrets*, *Nameless Gangster*, *Maari*, *Bugsy*,
*Khushkhabri*, *Radio*, *Legends of the Fall*, *Sleepless in Seattle*,
*Patch Adams*, *Love 911*, *Fast Five*, *Journey to the Center of the
Earth*. Source files: `/tmp/audit/master_hoster_inventory.json`,
`/tmp/audit/crawl_results_v3.json`, `/tmp/audit/unlock_*.html`.

**Date:** 2026-07-22 (post relabel-fix verification).

---

## 1. Master hoster inventory (from live crawl)

| # | Hoster domain seen on site | Occurrences | # movies | Type(s) | Labels seen on site |
|---|---|---:|---:|---|---|
| 1 | `photolinx.beauty`     | 85 | 20 | depisode  | "Episode 01..10", "Photolinx", "Photon" |
| 2 | `fastilinks.beauty`    | 84 | 22 | depisode  | "Download Links", "Fastilinks" |
| 3 | `gdflix.dev`           | 83 | 22 | depisode  | "GdFlix", "Gdflix" |
| 4 | `mediafire.com`        | 76 | 19 | depisode  | "Direct Link", "Mediafire" |
| 5 | **`fastmkv.sbs`**      | **46** | **2** | depisode  | "Direct Link", "Episode 01..10" |
| 6 | **`new6.gdtot.dad`**   | **6**  | **2** | depisode  | "Share Drive" |
| 7 | `voe.sx`               |  4 |  4 | dood (Watch-Online /get-doods HEVC bucket) | "WatchOnline:HEVC" |
| 8 | `vidhide.com`          |  4 |  4 | dood (Watch-Online /get-doods HEVC bucket) | "WatchOnline:HEVC" |
| 9 | `new4.filepress.cloud` |  2 |  1 | depisode  | "Filepress" |
| 10 | `new1.filepress.cloud` |  2 |  1 | depisode  | "Filepress" |
| 11 | `new1.filepress.wiki`  |  1 |  1 | depisode  | "Filepress" |

(Total link occurrences across 22 movies: 393. `filepress` and
`fastmkv.sbs` / `new6.gdtot.dad` account for 57 of those — 14.5%.)

**Additional note from the Cloudflare challenge page on
`new1.filepress.wiki`:** the CF challenge response includes
`cZone: "new2.filepress.baby"`, indicating a third filepress subdomain
(`new2.filepress.baby`) is also live in rotation. The site hasn't been
observed serving it yet, but it will likely appear in future uploads.

---

## 2. Cross-reference against current `loadLinks()` / `resolveUrl()`

| Hoster | Currently handled? | How | CS3 4.8.0-PRE built-in extractor? | Notes |
|---|---|---|---|---|
| `mediafire.com`           | ✅ YES | `loadExtractor()` → `Mediafire` built-in → `relabel()` | ✅ `Lcom/lagradost/cloudstream3/extractors/Mediafire;` | Verified on-device: top-bar reads `TheNextPlanet [Mediafire] • 720p • HEVC` (screenshot `08_player_with_fixed_label.png`). |
| `gdflix.dev`              | ✅ YES | Custom `GDFlix` class with 5-arg overload accepting unlock-page `LinkMeta` as fallback | n/a (our own class) | Resolves R2 Cloud / Direct DL / Instant DL / PixelDrain / GoFile / DriveBot / CF backup. |
| `photolinx.beauty`        | ⚠️ ROUTED but DEAD | `loadExtractor()` → **no extractor matches** → silently dropped | ❌ NOT in `Lcom/lagradost/cloudstream3/extractors/*` (verified via `dexdump`) | The "Photolinx" brand label never reaches the Sources UI. |
| `fastilinks.beauty`       | ⚠️ ROUTED but DEAD | `loadExtractor()` → **no extractor matches** → silently dropped | ❌ NOT in built-in extractors | Same as above. |
| `voe.sx`                  | ✅ YES | `loadExtractor()` → `Voe`/`Voe1`/`Voe2` built-in → `relabel()` | ✅ `Lcom/lagradost/cloudstream3/extractors/Voe;` (+ Voe1, Voe2) | Watch-Online HEVC bucket path, brand = `TheNextPlanet [Watch Online]`. |
| `vidhide.com`             | ✅ YES | `loadExtractor()` → `VidhideExtractor`/`VidHidePro*` built-in → `relabel()` | ✅ `Lcom/lagradost/cloudstream3/extractors/VidhideExtractor;` + 7× VidHidePro* | Same Watch-Online HEVC bucket path. |
| **`fastmkv.sbs`**         | ❌ **NO** | Hits `else ->` branch in `resolveUrl()` which builds a `newExtractorLink` with the `fastmkv.sbs` URL as-is (not a real stream URL) | ❌ NOT in built-in extractors | HTTP probe: returns "Error. Page cannot be displayed." — file IDs are opaque, no obvious resolution pattern. 46 occurrences, 2 movies. |
| **`new6.gdtot.dad`**      | ❌ **NO** | Same `else ->` branch — `newExtractorLink` with the wrapper URL | ❌ NOT in built-in extractors | HTTP probe: returns literal `"OK"` body, cookie-gated shortener. 6 occurrences, 2 movies. |
| **`new4.filepress.cloud`** | ❌ **NO** | `loadExtractor()` → no match → silently dropped | ❌ NOT in built-in extractors | Cloudflare anti-bot challenge (`__cf_chl_*`) — even with browser UA, requires JS challenge. 2 occurrences, 1 movie. |
| **`new1.filepress.cloud`** | ❌ **NO** | Same as above | ❌ NOT in built-in extractors | Cloudflare challenge. 2 occurrences, 1 movie. |
| **`new1.filepress.wiki`**  | ❌ **NO** | Same as above | ❌ NOT in built-in extractors | Cloudflare challenge. 1 occurrence, 1 movie. (Likely future: `new2.filepress.baby` per the CF `cZone` field in the `.wiki` challenge response.) |

**The summary count:**
- **4 hosters fully working** with branded labels: Mediafire, GDFlix, Voe, Vidhide.
- **2 hosters silently dropped** but routed: Photolinx, Fastilinks (the brand name and the link both vanish before the Sources UI sees them).
- **5 hosters have no working extractor** at all: Fastmkv, Gdtot, Filepress(.cloud/.wiki).

**Effective coverage today:** 246/393 links resolve to a working source
(Mediafire 76 + GDFlix 83 + Voe 4 + Vidhide 4 = 167 from fully-working,
plus the 85 Photolinx + 84 Fastilinks + 6 Gdtot + 2 + 2 + 1 Filepress
+ 46 Fastmkv that are dead/partial). The clean, real-stream-producing
coverage is **42.5% of observed links**.

---

## 3. Three distinct categories of gap

### Gap A — Brand label missing because `loadExtractor()` has no match
(`photolinx.beauty`, `fastilinks.beauty`)
- `loadExtractor()` walks CS3's `extractorApis` list of `ExtractorApi`
  subclasses; for these URLs no entry has a `mainUrl` that `startsWith`s
  the URL, so it returns without firing the callback.
- **Cost:** low — the unlock-page step already wraps them in
  `depisode/?lockey=...&url=...` and the `<summary>` text on the
  unlock page ("Download Links", "Photolinx", "Photon") is the only
  quality hint. They're *download* hosters, not streaming hosters.
- **Possible fix:** write a small custom `ExtractorApi` subclass per
  domain that issues a single GET to the depisode-wrapper URL, parses
  the redirect chain, and emits an `ExtractorLink`. Or: in
  `resolveUrl()`, when `loadExtractor()` returns without calling the
  callback, fall back to constructing the `ExtractorLink` from the URL
  as-is with the branded label (the same `else ->` branch that today
  silently mishandles Fastmkv/Gdtot).
- **Important caveat:** Photolinx and Fastilinks are typically
  intermediate redirect chains that eventually 302 to a third-party
  download page (a third-party CDN, not a stream). They will not
  produce a playable `m3u8` / `mp4` in ExoPlayer. So even after adding
  a branded label, the source will be unplayable in CS3's player.

### Gap B — Hoster has working response but no extractor + no obvious pattern
(`fastmkv.sbs`, `new6.gdtot.dad`)
- `fastmkv.sbs/file/<id>` returns "Error. Page cannot be displayed."
  (file IDs are 14-char alphanumeric, no `?` params, no obvious
  meta-refresh). Could be: dead hoster, geo-restricted, or just
  anti-bot. Cannot determine resolution pattern without a working ID.
- `new6.gdtot.dad/file/<id>` returns literal `"OK"` body — it's a
  cookie-gated shortener landing page. Historically `gdtot.*` was a
  GDRIVE-resolver (paste the `gdtot.link/folder?id=...` URL and get
  the GDRIVE direct link). New `.dad` TLD is unrecognised; could be
  a clone or a new payload-distribution scheme.
- **Cost:** both are currently in the `else ->` branch and produce a
  Source entry with the wrapper URL — clicking them in CS3 will
  produce a player error (no playable stream URL).
- **Possible fix:** *none that doesn't risk a fake claim.* Until we
  have a working sample ID + a real redirect chain, the honest answer
  is to *not* emit a Source for them and log a clear DBG message.
  This is a net improvement: today the player tries to play them and
  silently shows an error; tomorrow the Sources UI simply won't list
  them.

### Gap C — Hoster is Cloudflare-challenge-gated
(`new4.filepress.cloud`, `new1.filepress.cloud`, `new1.filepress.wiki`,
likely future `new2.filepress.baby`)
- All filepress subdomains sit behind `cf-nel` Cloudflare anti-bot JS
  challenges. Even sending a real browser `User-Agent` and disabling
  cert verification (`curl -kL`) returns the standard "Just a
  moment..." `__cf_chl_*` page, not a real download link.
- This is a **structural blocker** for any plugin: the only reliable
  way past a CF interactive challenge is a real browser context
  (chromedriver / flaresolverr). CS3 plugins don't ship with a
  WebView and don't proxy through such a backend.
- **Cost:** zero today (the links were never playing) — they're
  being silently dropped by `loadExtractor()` because no extractor
  matches. The current behaviour is benign.
- **Possible fix:** None at the plugin level. The only realistic
  options are (a) skip these hosters gracefully (i.e. don't emit a
  Source), or (b) have the user manually open the URL in a browser
  and paste the resolved link — which is outside CS3's plugin
  architecture.

---

## 4. Recommendation — proposed scope for next commit

The user asked for the audit table first. Based on the table above, the
**realistic, verifiable scope** of a follow-up commit is:

### 4a. Add skip-with-warning for Gap B (Fastmkv, Gdtot)
Today both fall into the `else ->` branch of `resolveUrl()` and create
a Source entry that the player can't play (the wrapper URL is not a
stream). The improvement is: skip them entirely and log `Log.w(TAG,
"skipping unresolvable hoster: $url")`. Net effect: 52 fewer broken
Sources in the UI (46 fastmkv + 6 gdtot). This is a *removal* of
broken behaviour, not added functionality. Cost: ~5 lines. Risk: low.

### 4b. Add `loadExtractor()` mismatch fallback for Gap A (Photolinx, Fastilinks)
Currently the callback never fires, so no Source is emitted. The
improvement is: when `loadExtractor()` returns without calling the
callback, still emit a branded `ExtractorLink` (with `type = M3U8`
heuristic, empty `referer`, and the brand label we already have). This
**will not produce a playable stream** for these download-redirect
chains, but the brand label will at least appear in the Sources UI so
the user understands what's there. Cost: ~15 lines. Risk: medium —
it will produce a Source that fails to play. Need to document this
clearly in the report so the user isn't surprised.

**Alternative for 4b:** write minimal `ExtractorApi` subclasses
(`Photolinx`, `Fastilinks`) that follow the depisode-wrapper redirect
chain and try to extract a final URL. *Only* if a sample URL can be
verified to redirect to a real streamable URL. The previous session's
HTTP probes for these domains were inconclusive — would need a fresh
investigation. Not recommended for this PR.

### 4c. Skip Gap C (Filepress subdomains) explicitly
Today they're silently dropped by `loadExtractor()` because no extractor
matches. Adding an explicit `Log.w(TAG, "skipping CF-challenge-gated
hoster: $url")` makes the skip visible. Cost: ~3 lines. Risk: zero.

### 4d. **NOT recommended** for this PR
- Custom extractors for Fastmkv, Gdtot, Filepress. Each requires a
  working sample URL + a verified resolution pattern. The current
  evidence is that 3/3 of these domains either return error pages or
  sit behind CF challenges — none of them are resolvable from the
  plugin sandbox today. Writing a custom extractor that *fabricates* a
  stream URL would be exactly the anti-fabrication violation the
  session brief warns against.
- Filepress support via a flaresolverr-style backend. Architectural
  change, out of scope.

### 4e. Summary of scope
- **Real changes:** ~23 LOC (4a + 4b + 4c combined).
- **JUnit tests:** 3 new tests asserting the skip paths (one per
  Gap-A/B/C hoster).
- **Build:** rebuild `.cs3` (clean release, `DEBUG = false`).
- **On-device verification:** re-enable the AutoTest/DBG reflection
  companion object for one more build, confirm via logcat + screenshot
  that the new skip messages fire and that the 4 still-working hosters
  continue to work.
- **Document:** append to `LABEL_FORMAT_PROOF.md` a new section
  covering the gap analysis and the new "skip" behaviour.

**Estimated new code coverage (after this PR):** Mediafire 76 +
GDFlix 83 + Voe 4 + Vidhide 4 = **167 working source entries** out
of 393 observed. Same numerator (no new streamable hosters added),
but with cleaner skip behaviour and explicit DBG logging for the
other 226.

---

## 5. Open question for the user

Before implementing 4a/4b/4c, please confirm:

1. **Gap A (Photolinx, Fastilinks):** should I emit a branded
   non-playable Source (4b), or just skip them like 4c?
   *Pro 4b:* the brand name appears in the Sources UI so the user
   knows what was there. *Con 4b:* clicking the Source produces a
   player error.

2. **Gap B (Fastmkv, Gdtot):** the current behaviour already creates
   a broken Source. Confirm the *removal* (4a) is the right call?
   (Alternative: leave as-is and document the limitation in the
   `LABEL_FORMAT_PROOF.md`.)

3. **Gap C (Filepress):** confirm skip is acceptable? (Architecturally
   there's no path forward without browser automation.)

4. **Test coverage:** is it acceptable to add 3 JUnit tests for the
   skip paths (one per Gap A/B/C), keeping the count at 25/25, or do
   you want a real on-device smoke test for each hoster?

No extractor code will be written until these are answered.
