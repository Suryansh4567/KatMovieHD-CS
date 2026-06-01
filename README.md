# 🎬 CloudStream KatMovieHD Extension

A working CloudStream 3 extension for [KatMovieHD](https://new1.katmoviehd.cymru/) (Hindi dubbed & dual-audio Movies / TV Series).

Built with the proven modern toolchain used by [`phisher98/cloudstream-extensions-phisher`](https://github.com/phisher98/cloudstream-extensions-phisher) and [`SaurabhKaperwan/CSX`](https://github.com/SaurabhKaperwan/CSX) — AGP 8.5.2 + Kotlin 1.9.25 + Gradle 8.10 + JDK 17.

> ⚠️ **Disclaimer:** Educational use only. This repo does not host any media files — the provider only scrapes publicly available links from third-party sites.

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
