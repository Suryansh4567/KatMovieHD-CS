# Source-name standardisation: before vs after

The follow-up fix (commit `0361ebd`, version 2→3) extends the per-source
label standardisation that was previously only applied to the GDFlix
branches to all sources routed through the unlock page — Mediafire,
Photolinx, Filepress, Fastilinks, plus the Watch-Online (Voe/Vidhide)
path.

## The unlock-page structure that makes this possible

Every depisode-wrapped link on `thenextplanet-official.space` lives inside
a `<details><summary>` block. The summary text is the only place
per-link quality info exists for opaque-URL hosters (Mediafire,
Photolinx, Fastilinks):

```html
<details>
  <summary><b>720p HEVC</b> Download Links</summary>
  <a href="/depisode/?url=https://www.mediafire.com/file/.../Kntra72pHV_...mkv">Mediafire</a>
  <a href="/depisode/?url=https://photolinx.beauty/download/...">Photolinx</a>
  <a href="/depisode/?url=https://gdflix.dev/file/1oYPCvMueBqr2O3">GdFlix</a>
  <a href="/depisode/?url=https://fastilinks.beauty/view/...">Download Links</a>
</details>
```

`loadLinks()` now walks the page group-by-group and passes the parsed
`LinkMeta` from the enclosing summary down into `resolveUrl()` for
each link.

## Comparison: the Kantara unlock page

| Group            | Hoster      | v2 (old)                          | v3 (this fix)                                              |
|------------------|-------------|-----------------------------------|------------------------------------------------------------|
| 720p HEVC        | MediaFire   | `MediaFire`                       | `TheNextPlanet [Mediafire] • 720p • HEVC`                 |
| 720p HEVC        | Photolinx   | `Photolinx`                       | `TheNextPlanet [Photolinx] • 720p • HEVC`                 |
| 720p HEVC        | GDFlix      | `GDFlix [R2 Cloud] • 720p • HEVC` | `GDFlix [R2 Cloud] • 720p • HEVC` (unchanged)              |
| 720p HEVC        | Fastilinks  | `Fastilinks`                      | `TheNextPlanet [Fastilinks] • 720p • HEVC`                |
| 1080p            | MediaFire   | `MediaFire`                       | `TheNextPlanet [Mediafire] • 1080p`                       |
| 1080p            | Photolinx   | `Photolinx`                       | `TheNextPlanet [Photolinx] • 1080p`                       |
| 1080p            | GDFlix      | `GDFlix [R2 Cloud] • 1080p`       | `GDFlix [R2 Cloud] • 1080p` (unchanged)                    |
| 1080p            | Fastilinks  | `Fastilinks`                      | `TheNextPlanet [Fastilinks] • 1080p`                      |
| 720p             | MediaFire   | `MediaFire`                       | `TheNextPlanet [Mediafire] • 720p`                        |
| 720p             | Photolinx   | `Photolinx`                       | `TheNextPlanet [Photolinx] • 720p`                        |
| 720p             | GDFlix      | `GDFlix [R2 Cloud] • 720p`        | `GDFlix [R2 Cloud] • 720p` (unchanged)                     |
| 720p             | Fastilinks  | `Fastilinks`                      | `TheNextPlanet [Fastilinks] • 720p`                       |
| 480p             | MediaFire   | `MediaFire`                       | `TheNextPlanet [Mediafire] • 480p`                        |
| 480p             | Photolinx   | `Photolinx`                       | `TheNextPlanet [Photolinx] • 480p`                        |
| 480p             | GDFlix      | `GDFlix [R2 Cloud] • 480p`        | `GDFlix [R2 Cloud] • 480p` (unchanged)                     |
| 480p             | Fastilinks  | `Fastilinks`                      | `TheNextPlanet [Fastilinks] • 480p`                       |
| (Watch Online)   | Voe (HEVC)  | `Voe`                             | `TheNextPlanet [Watch Online] • HEVC • x265`              |

**Coverage:** 12/17 links (71%) had no quality info before; 17/17 (100%)
have it now.

## How the metadata is built

`LinkMeta` carries nullable fields (resolution, print, codec, language,
size). Null means "not available, never fabricate." The new helpers:

| Helper                  | Input                          | Output                           |
|-------------------------|--------------------------------|----------------------------------|
| `parseGroupSummary(s)`  | `<summary>` text               | `LinkMeta` from resolution/print/etc. |
| `bucketToMeta(b)`       | `/get-doods` bucket name       | `LinkMeta` (FHD→1080p, HEVC→x265)   |
| `mergeMeta(primary, fallback)` | two `LinkMeta`s           | primary wins; fallback fills nulls  |
| `buildLabel(source, meta)`    | brand name + `LinkMeta`    | `Brand • res • print • lang • ...`   |
| `shouldSkip(url)`       | URL string                     | null if supported, "reason" if not (v4) |

GDFlix links keep their richer landing-page-derived label
(`GDFlix [R2 Cloud] • 720p • HEVC • x265 • 843MB`) via
`mergeMeta()` because the GDFlix `Name : …` / `Size : …` fields are
still richer than the unlock-page summary.

## Verification

- `./gradlew :TheNextPlanet:testDebugUnitTest` → 32/32 PASS
  (22 original + 10 new `shouldSkip()` tests)
- `./gradlew :TheNextPlanet:make` → builds
  `TheNextPlanet.cs3` (51,840 bytes, clean release, `DEBUG = false`,
  `AUTOTEST_ENABLED = false`)
- On-device AutoTest/DBG reflection re-verification (see
  `on_device_verification_v3/logcat.txt`):
  - 7/7 skip URLs returned `[SKIP-OK]`
  - 4/4 keep URLs returned `[KEEP-OK]`
  - 0 `[SKIP-FAIL]` or `[KEEP-FAIL]`
  - `loadLinks()` on the live Starman page produced 10 real
    `ExtractorLink` callbacks (4 Mediafire + 6 GDFlix), all with
    branded labels matching the v3 spec.
  - 8 Photolinx + 5 Fastilinks URLs from the live page produced
    `W TheNextPlanet: Skipping depisode link:` log lines.
- Dex strings present in the final `.cs3`: `TheNextPlanet [Mediafire]`,
  `TheNextPlanet [Photolinx]`, `TheNextPlanet [Fastilinks]`,
  `TheNextPlanet [Watch Online]`, `shouldSkip`, `fastmkv.sbs`,
  `gdtot.dad`, `photolinx.beauty`, `fastilinks.beauty`, `filepress`.

---

## v4 follow-up — explicit hoster skip list (SOURCE_COVERAGE_AUDIT.md)

After the 22-title audit (see `SOURCE_COVERAGE_AUDIT.md`), we added
a `shouldSkip()` companion function that returns a human-readable
skip reason for URLs from 5 hosters the plugin deliberately does
not support:

| Hoster (live URL pattern) | Skip reason |
|---|---|
| `*.fastmkv.sbs`           | "no working resolution pattern (returns error page)" |
| `*.gdtot.dad`             | "cookie-gated shortener, no resolution pattern" |
| `*.photolinx.beauty`      | "no built-in CS3 extractor, download-only host" |
| `*.fastilinks.beauty`     | "no built-in CS3 extractor, download-only host" |
| `*filepress*`             | "Cloudflare JS-challenge gated" |

The `else` branches in `loadLinks()` step 5a (depisode-wrapped
links) and step 5b (direct host links) call `shouldSkip(finalUrl)`
first and `Log.w` + `continue` if the URL is in the skip list. The
`Log.w` level is intentional — users running logcat can see exactly
which URLs were skipped and why, without needing the verbose DEBUG
flag.

Before this change:
- Photolinx / Fastilinks / Filepress were *silently dropped* by
  `loadExtractor()` (no built-in CS3 extractor matched).
- Fastmkv / Gdtot hit the `else` branch in `resolveUrl()` and built
  an `ExtractorLink` with the *depisode wrapper URL* as the stream
  URL — clicking that Source in the player produced a "no playable
  stream" error.

After this change:
- All 5 are skipped with a `Log.w` line. **No broken Source in the
  Sources UI.** Identical end-user behaviour for the silent drops,
  but the Fastmkv/Gdtot broken-Source removal is a real improvement.

The 4 hosters we DO support (Mediafire, GDFlix, Voe, Vidhide) are
unaffected — the negative-case JUnit tests
(`shouldSkip_mediafire_passes`, `shouldSkip_gdflix_passes`,
`shouldSkip_voe_passes`, `shouldSkip_vidhide_passes`) and the
on-device AutoTest confirm they are not skipped.

---

## v5 follow-up — Photolinx Extractor (SOURCE_COVERAGE_AUDIT_PHASE5.md)

After the Phase 5 probe plan, the user approved implementing only the
**Photolinx** hoster (the only feasible one of the 5 audited gap-A/B/C
hosters). The other 4 remain explicitly skipped with clear reasons:

| Hoster (still skipped in v5) | Reason in `shouldSkip()` |
|---|---|
| `*.fastmkv.sbs` | "no working resolution pattern (HTTP 520 from CF at probe time; see SOURCE_COVERAGE_AUDIT_PHASE5.md — re-probe if a working sample becomes available)" |
| `*.gdtot.dad`   | "Anubis PoW browser-challenge gated" |
| `*.fastilinks.beauty` | "Google reCAPTCHA-gated, no CS3 extractor" |
| `*filepress*`   | "Cloudflare Turnstile interactive challenge gated" |

### New file: `TheNextPlanet/src/main/java/com/lagradost/Photolinx.kt`

Custom `ExtractorApi` class implementing the Photolinx flow:

1. `GET https://photolinx.beauty/download/<uid>` with a real browser
   User-Agent → 200 OK + HTML containing `<section id="generate_url">`
   with `data-token` (server-encrypted base64) and `data-uid`.
2. `POST https://photolinx.beauty/action` with
   `{"type":"DOWNLOAD_GENERATE","payload":{"uid":...,"access_token":...}}`
   and `Content-Type: application/json` + `X-Requested-With: XMLHttpRequest`
   + matching UA + same session cookie + `Referer`.
3. Parse `data.download_url` from the JSON response.
4. Emit a single `ExtractorLink` with the `download_url` as the final
   stream URL.
5. **Any failure (network error, invalid token, missing token, empty
   `download_url`, anything) → `Log.w` + return without calling the
   callback. Never emit a broken Source.**

### Critical constraint: the access_token is server-encrypted

The Photolinx server encrypts the `access_token` using a (session cookie,
User-Agent, IP) tuple. If the GET and POST use different UAs, the
server returns `{"error":"Invalid access token"}`. In the in-app
HTTP client the UAs may differ from the on-page HTML's, so the
extractor's `Log.w` + skip-fallback is critical.

### Verification

- **JUnit tests: 37/37 pass** (was 32/32 in v4; +5 new Photolinx parse tests using real probe data)
- **On-device AutoTest verified on 2026-07-23:**
  - 6/6 SKIP-OK, 5/5 KEEP-OK (incl. the new `photolinx` keep entry)
  - Live Mediafire + GDFlix Sources still emit correctly-branded labels
  - **Live Photolinx skip-fallback verified:** the AutoTest drove a real
    Photolinx URL through the extractor, the server returned `Invalid
    access token`, the extractor logged `W TheNextPlanet:Photolinx:
    Skipping: /action returned no download_url` and returned without
    emitting a Source. No broken Source in the UI.
  - `PluginManager: Loaded plugin TheNextPlanet.cs3 successfully`
  - `PluginInstance: Adding Photolinx (https://photolinx.beauty) ExtractorApi` confirms the new class registers.

- **Dex strings present in the final `.cs3`:** `Photolinx`,
  `DOWNLOAD_GENERATE`, `photolinx.beauty/action`, `data-token`,
  `data-uid`, `download_url`, `Invalid access token`.
