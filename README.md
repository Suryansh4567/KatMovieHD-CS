# 🎬 CloudStream KatMovieHD + OlaMoviesV2 Extension

A working CloudStream 3 extension for [KatMovieHD](https://new1.katmoviehd.cymru/) (Hindi dubbed & dual-audio Movies / TV Series) and **OlaMoviesV2** (v2.olamovies.mov — 4K UHD / HDR / Dolby Vision / REMUX).

Built with the proven modern toolchain used by [`phisher98/cloudstream-extensions-phisher`](https://github.com/phisher98/cloudstream-extensions-phisher) and [`SaurabhKaperwan/CSX`](https://github.com/SaurabhKaperwan/CSX) — AGP 8.5.2 + Kotlin 1.9.25 + Gradle 8.10 + JDK 17.

> ⚠️ **Disclaimer:** Educational use only. This repo does not host any media files — the provider only scrapes publicly available links from third-party sites.

---

## 🔥 OlaMoviesV2 — v14 SAB FIX (ab sare movie chale!)

**Target:** `v2.olamovies.mov` — WordPress/Gridlove site hosting 4K UHD, HDR, Dolby Vision, and REMUX releases.

### The Link Chain (and how we crack it)

```
Movie page → links.ol-am.top/XXXXX → 301 → links.olamovies.mov/XXXXX (CF Turnstile)
→ "Login to Continue" generator page (JS-heavy) → ad shortener → final host (HubCloud/GDFlix/etc.)
```

### v14 New Features

| Feature | Description |
|---------|-------------|
| **Generator page scraping** | Detects "Login to Continue", "Please wait", generator buttons by text + class + data-* + onclick |
| **17-pattern aggressive scrape** | 15 old + pattern 16 (generator button/text) + pattern 17 (broad raw JS URL scan) |
| **Broader JS patterns** | `var loginUrl`, `continueUrl`, `generateUrl`, `nextPage`, `shortUrl`, `goToUrl` + onclick ANY-http regex |
| **S8: Cookie + referer variations** | Tries different referer/cookie combos for flaky ad shorteners (dulink/ez4short/rocklinks/crazyblog) |
| **S9: Direct host scan** | Scans ad page HTML for known host URLs — ultimate bypass |
| **ULTIMATE ALL-MOVIES** | Extra mass loadExtractor in Provider with 4 generator referer variations |
| **Ultra permissive link collection** | Returns UNION of strict + permissive links from movie page (sab links milenge) |
| **Speed guards** | MAX_RETRIES=2, RETRY_DELAY_MS=800ms, maxDepth=8, maxSteps=6 |

### v14 Status Table

| Plan Item | Status | Notes |
|-----------|--------|-------|
| Phase 2.1: Plain short link entry | ✅ | bypassOlaRedirect handles plain + keyed |
| Phase 2.2: loadExtractor better use | ✅ | Strategy A + NUCLEAR fallback |
| Phase 2.3: Scraping & chain (MAXED) | ✅ | 17 patterns + generator page scraping |
| Phase 2.4: Ad shortener (MULTI-API) | ✅ | S1-S9 (9 strategies!) |
| Phase 2.5: Last Resort / ULTIMATE | ✅ | Phase 2.5 + ULTIMATE ALL-MOVIES block |
| Generator page: "Login to Continue" | ✅ | Text-based + class + data-* + onclick scraping |
| Broad JS vars (loginUrl/continueUrl) | ✅ | 8 new JS patterns added |
| onclick ANY-http regex | ✅ | Broad regex catches all http URLs in handlers |
| Speed guards (2 retries, 800ms) | ✅ | MAX_RETRIES=2, RETRY_DELAY_MS=800L |
| maxDepth=8, maxSteps=6 | ✅ | Prevents infinite chains |
| Version bump to 14 | ✅ | build.gradle + all comments updated |

### Test Instructions

1. Build the v14 plugin: `./gradlew makePluginsJson`
2. Install `.cs3` file in CloudStream
3. Test with **Hoppers (2026)** — should have 12 short links
4. Check logcat for `OlaLinks`, `OlaUtils`, `OlaMoviesV2` tags
5. Report: which strategy worked? Generator button detected? Final hosts resolved?

---

## ✨ What Makes This Special

Most KatMovieHD CloudStream attempts fail because of the `links.kmhd.eu/file/<id>` interstitial — a SvelteKit page that demands a manual "Click to Unlock Links" click before showing the actual download mirrors.

This extension **bypasses that interstitial entirely** with just two HTTP calls:

1. **POST** `/locked?/unlock&redirect=<base64>` → server sets `unlocked=true` cookie.
2. **GET** `/file/<id>/__data.json` (with cookie) → returns SvelteKit's denormalized JSON with all mirrors (GDFlix, HubCloud, KatDrive, StreamTape, etc.).

The extractor then forwards each mirror URL to CloudStream's built-in extractors. **No UI interaction, no WebView, no captcha — pure HTTP.**

---

## 📦 Install In CloudStream

1. Open CloudStream app → **Settings → Extensions → Add repository**
2. Paste this URL (replace with your username after publishing):
   ```
   https://raw.githubusercontent.com/Suryansh4567/KatMovieHD-CS/builds/repo.json
   ```
3. Install **KatMovieHD** plugin from the repo
4. Done! Pick it from the provider menu on the Home screen.

---

## 🚀 First-Time Setup (After Creating GitHub Repo)

### Step 1 — Replace `YOUR_GITHUB_USERNAME` (2 files)
- `repo.json` → line `pluginLists`
- `build.gradle.kts` → fallback `setRepo(...)` URL

If your username is the same as the repo owner, GitHub Actions auto-overrides `setRepo` via `GITHUB_REPOSITORY` — but `repo.json` you must edit manually.

### Step 2 — Push To GitHub
```bash
git init -b main
git add .
git commit -m "Initial commit: KatMovieHD CloudStream extension"
git remote add origin https://github.com/Suryansh4567/KatMovieHD-CS.git
git push -u origin main
```

### Step 3 — ⚠️ Enable Write Permissions (CRITICAL!)
Without this, the workflow builds but can't push the `.cs3` files.

→ Open: `https://github.com/Suryansh4567/KatMovieHD-CS/settings/actions`
→ "Workflow permissions" → select **"Read and write permissions"** → Save

### Step 4 — Trigger The Build
→ Open: `https://github.com/Suryansh4567/KatMovieHD-CS/actions`
→ "Build" workflow → "Run workflow" → wait 5–10 min

### Step 5 — Verify
Both URLs must return content (not 404):
- `https://raw.githubusercontent.com/Suryansh4567/KatMovieHD-CS/builds/plugins.json`
- `https://github.com/Suryansh4567/KatMovieHD-CS/tree/builds`

### Step 6 — Add To CloudStream App
Use the install URL from the top of this README.

---

## 📁 Repository Layout

```
cloudstream-katmoviehd/
├── build.gradle.kts                 ← Root build config (modern toolchain)
├── settings.gradle.kts               ← Auto-includes every plugin folder
├── gradle.properties
├── gradlew, gradlew.bat              ← Gradle wrapper scripts
├── gradle/wrapper/                   ← Gradle 8.10 wrapper
├── repo.json                         ← CloudStream repo manifest
├── .github/workflows/build.yml       ← CI: build + publish to `builds` branch
└── KatMovieHD/                       ← The plugin sub-project
    ├── build.gradle.kts              ← Plugin metadata
    └── src/main/
        ├── AndroidManifest.xml
        └── kotlin/com/arena/
            ├── KatMovieHDPlugin.kt   ← Entry point
            ├── KatMovieHDProvider.kt ← Scrapes the WordPress site
            └── KmhdExtractor.kt      ← Unlocks links.kmhd.eu
```

---

## 🧠 Source-Site Map

| CloudStream call | KatMovieHD URL |
|------------------|----------------|
| Home / Latest | `/page/{n}/` |
| Category | `/category/{slug}/page/{n}/` |
| Search | `/?s={query}` |
| Movie / Series detail | `/{slug}/` |
| Quality file | `https://links.kmhd.eu/file/{id}` |
| Watch online | `https://links.kmhd.eu/play?id={id}` |

Mirror hosts the extractor knows about (after unlocking): **GDFlix** (`gd.kmhd.eu`), **HubCloud** (`hubcloud.foo`), **KatDrive** (`katdrive.eu`), **StreamTape**, **1fichier**, **Send.cm**, **HGLink**, **FuckingFast**.

CloudStream's built-in extractors handle all of these natively — we just hand them the URLs.

---

## 🛠️ Local Development

```bash
# Build just the KatMovieHD plugin
./gradlew KatMovieHD:make

# Build everything + generate plugins.json
./gradlew makePluginsJson
```

Compiled `.cs3` ends up in `KatMovieHD/build/`.

---

## 🐛 Troubleshooting

### Build fails with "Permission denied" / 403
You didn't enable **"Read and write permissions"** in Step 3.

### Build succeeds but `builds` branch empty
Same issue — write permission missing. Workflow runs but `git push` blocks.

### CloudStream shows "repo not found"
Open `https://raw.githubusercontent.com/Suryansh4567/KatMovieHD-CS/builds/plugins.json` in browser.
- If 404: build hasn't completed yet, or `repo.json` URL doesn't match your actual repo path.
- If JSON shows: re-add the repo in CloudStream, refresh.

### KatMovieHD domain has changed
Edit `KatMovieHDProvider.kt`, change `mainUrl = "https://new1.katmoviehd.cymru"` to the new mirror. Bump `version = 1` → `2` in `KatMovieHD/build.gradle.kts`, push. Users get the update automatically.

### Links not playing
- The kmhd.eu unlock flow may have changed (rare). Check log output for "KmhdExtractor" messages.
- A particular mirror (e.g. HubCloud) might require Cloudflare clearance — CloudStream's `CloudflareKiller` handles this for most cases.

---

## 🙏 Credits

- **Phisher** — [cloudstream-extensions-phisher](https://github.com/phisher98/cloudstream-extensions-phisher) for the build toolchain pattern.
- **SaurabhKaperwan** — [CSX](https://github.com/SaurabhKaperwan/CSX) for the workflow / Megix repo style.
- **recloudstream** team for the gradle plugin & CS3 APIs.

## 📜 License
GPL-3.0 (same as upstream CloudStream).
