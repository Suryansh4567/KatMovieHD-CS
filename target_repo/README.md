# 🎬 CloudStream KatMovieHD Extension (v24)

A working CloudStream 3 extension repo for [KatMovieHD](https://new1.katmoviehd.cymru/) — Hindi dubbed & dual-audio movies/TV series.

Built with the current CloudStream extension toolchain — AGP 9.1.1 + Kotlin 2.3.21 + Gradle 9.4.1 + JDK 17.

> ⚠️ **Disclaimer:** Educational use only. This repo does not host any media files — the provider only scrapes publicly available links from third-party sites.

---

## 🔥 v24 — Exhaustive Reverse Engineering Audit

Every bug identified by live-probing the site against the extension code has been fixed:

### Critical fixes
| Fix | What was broken | Root cause |
|-----|----------------|------------|
| `/play` __data.json URL | Watch Online dead on every post | URL was `/play?id=X/__data.json` (HTML), should be `/play/__data.json?id=X` (JSON) |
| `/play` chunk parser | 0 mirrors from /play pages | /play links map is root directly (no `"links"` index); tokens live in a separate chunk — parser now searches across all chunks |
| `gd.kmhd.eu/file/` routing | Links silently dropped | 302 redirector → `gdflix.dev`; wasn't matched by dispatch regex, now resolved and routed to GDFlix |
| `gd.kmhd.eu/pack/` regex | Pack episodes not expanded | `KMHD_PACK_REGEX` only matched `links.kmhd.`, now also matches `gd.kmhd.` |
| `bbupload.to` extractor | New movies had 0 links | `BBServer` pointed at wrong host (`bbserver.in`); new `BBUpload` class uses reverse-engineered `/ajax.php?action=getdownload` API |
| `gdflix.dad` / `gdlink.dev` | Redirect-chain links dead-ended | Added `GDFlixDad` + `GDLinkDev` extractor classes; GDFlix follows redirects automatically |

### Moderate fixes
| Fix | What was broken |
|-----|----------------|
| Dead categories | `amzn-prime-video`→`amazon-prime`, `hotstar`→`disney`, `k-drama`→`korean-drama`; added 6 new working categories |
| Category listing parser | Category/search pages lack `<li id="post-N">` — added `.post-content` to fallback selector |
| `resolveFinalUrl` | HEAD + 2.5s timeout failed on CDN redirectors — switched to GET + 10s |
| Cloudflare fallback | Main provider had no CF bypass — added `safeGetDocument()` with `CloudflareKiller` |
| Dead `vifix.site` | Links collected but host is parked — filtered from both whitelist and salvage |
| README | Documented non-working POST unlock flow (only hardcoded cookie works) |

---

## 🔥 CinemaLux — OlaMovies-like replacement

**Target:** `cinemalux.makeup` — 4K/1080p/2160p movies & series with tpi.li → LinkStore style download chain.

### Current support

| Feature | Status |
|---------|--------|
| Home/Search listing | ✅ Tested live |
| Movie detail metadata | ✅ Tested live |
| Quality buttons (`tpi.li`) | ✅ Tested live |
| `tpi.li` hidden-token decode | ✅ Tested live (`tpi.li` → `drive.linkstore.zip/file/{id}`) |
| LinkStore final host extraction | ⚠️ WebView/security-gated; implemented direct/CF/WebView fallback |
| OlaMovies provider | 🗑️ Removed/replaced by CinemaLux |

### Live test evidence

- Home: `https://cinemalux.makeup/` → 44 cards found.
- Search: `?s=dacoit` → Dacoit result found.
- Detail: Dacoit page → 14 `tpi.li` quality links found.
- `https://tpi.li/XOJY5f1Cl3g` decoded to `https://drive.linkstore.zip/file/25c3bfefe6a082d8`.

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
| Quality file | `https://links.kmhd.{tld}/file/{id}` |
| Watch online | `https://links.kmhd.{tld}/play?id={id}` |

Mirror hosts the extractor knows about (after unlocking): **GDFlix** (`gd.kmhd.eu`, `new*.gdflix.*`), **HubCloud/HubDrive**, **KatDrive**, **StreamTape**, **1fichier**, **Send.cm**, **HGLink**, **FuckingFast**.

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
Update only `domains.json`:

```json
{
  "katmoviehd": "https://new-working-domain.example",
  "katmoviehd_candidates": [
    "https://new-working-domain.example",
    "https://backup-working-domain.example"
  ]
}
```

KatMovieHD v21 re-reads this file every 6 hours, health-checks the candidate list, rewrites old bookmarked KatMovieHD URLs onto the active domain, and does **not** need a rebuild/reinstall for normal domain rotations. Bump `KatMovieHD/build.gradle.kts` only when Kotlin code changes.

### Links not playing
- Check log output for `KatMovieHD`, `KmhdExtractor`, `GDFlix`, and `HubCloud` messages.
- If direct article links use a new GDFlix/HubCloud subdomain, v21 dispatches them directly instead of relying only on CloudStream prefix matching.
- If `links.kmhd.*` moves away from `.eu`, v21 fetches `/__data.json` from the incoming link's own host.
- A particular mirror (e.g. HubCloud/GDFlix) might require Cloudflare clearance — the local extractors now retry with `CloudflareKiller` for most lighter CF pages.

---

## 🙏 Credits

- **Phisher** — [cloudstream-extensions-phisher](https://github.com/phisher98/cloudstream-extensions-phisher) for the build toolchain pattern.
- **SaurabhKaperwan** — [CSX](https://github.com/SaurabhKaperwan/CSX) for the workflow / Megix repo style.
- **recloudstream** team for the gradle plugin & CS3 APIs.

## 📜 License
GPL-3.0 (same as upstream CloudStream).
