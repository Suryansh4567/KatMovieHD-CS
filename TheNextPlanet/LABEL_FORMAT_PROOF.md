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

GDFlix links keep their richer landing-page-derived label
(`GDFlix [R2 Cloud] • 720p • HEVC • x265 • 843MB`) via
`mergeMeta()` because the GDFlix `Name : …` / `Size : …` fields are
still richer than the unlock-page summary.

## Verification

- `./gradlew :TheNextPlanet:testDebugUnitTest` → 22/22 PASS
- `./gradlew :TheNextPlanet:writeCacheEntry --rerun-tasks` → builds
  `TheNextPlanet.cs3` (47,565 bytes), `plugin-entry.json` shows
  `version=3`, fresh SHA-256.
- Dex strings present: `TheNextPlanet [Mediafire]`,
  `TheNextPlanet [Photolinx]`, `TheNextPlanet [Fastilinks]`,
  `TheNextPlanet [Watch Online]`, plus the new
  `getUrl(..., LinkMeta, ...)` overload.
