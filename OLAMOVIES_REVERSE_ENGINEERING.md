# 🕵️ OlaMovies Link Chain — Reverse Engineering Dossier (2026-08-23)

How OlaMovies' download buttons actually turn into a playable file, and what the
CloudStream provider does with each hop. Built from live probing + third-party
tooling source review (GitHub/Greasyfork/Codeberg/Wayback).

## The chain (all hops confirmed)

```
[v4.olamovies.mov post]                       WordPress catalog
   │  anchors: "[720p DS4K [1.25GB]]", "[episode 02]"
   ▼
https://links.ol-am.top/<slug>                first hop, plain 301
   ▼
https://links.olamovies.mov/<slug>            "OlaMovies Link Generator"
                                              Next.js SPA (Cloudflare protected).
                                              Slugs: numeric (…/106276) & base62 (…/1VyhXqWrF8T6vVnTyp)
   │  SPA calls */api/omd* → JSON { shortener, isFound }
   │  ⛔ gated: CF challenge + "Login to Continue" + press-hold / slide human check
   ▼
temporary ad-shortener URL                    (tpi.li / linksconsole / get2short …families)
   │  ads + timers
   ▼
https://drive.olamovies.download/file/<…>     OlaMovies' own Next.js "drive shell" page
   │  HTML embeds  "_id":"<20-30 hex>"  + <h1> file name
   ▼
https://olam.bypassbot.workers.dev/<_id>?filename=<base64url(name)>
                                              ★ DIRECT PLAYABLE STREAM (mkv/mp4)
```

## Hard evidence

| Item | Source |
|------|--------|
| Generator chain ⇒ ad shortener | live fetch of `links.ol-am.top/LvOOKSLRRKDghCatsk` (today) |
| SPA fetch contract `{shortener,isFound}` | `inject.js` interceptor in github.com/priyanshu3301/olamovies (2026-03) |
| Login + human-check gate | Skip-Wait catalog `olamovies-link-generator-bypass.ts` (2026) |
| drive page ⇒ `_id` + h1 ⇒ worker URL | Greasyfork **566947 "Olam Drive Bypasser UI"** (2026-02-21) — `html.match(\"_id\":\"(.*?)\")`, `https://olam.bypassbot.workers.dev/${_id}?filename=${base64url(title)}` |
| Worker is alive | `https://olam.bypassbot.workers.dev/` → `Not Found` (needs `/<id>`) |
| Generator = Next.js turbopack | Wayback `_next/static/chunks/*` (Apr/Jun/Aug 2026 builds) — page chunk not archived, framework chunks confirmed |
| Old AnyLinks flow dead | `aryx.xyz` parked (for sale), `anylinks.site` NX, `anylinks.in/<slug>` 500 |

## What the provider does with this (v5)

1. **`collectDownloadSources()`** — harvests every `links.ol-am.top`/`links.olamovies.mov`
   anchor from catalog posts; grafts release-name headers onto short labels.
2. **Fast path** — GET the generator page, sniff for any embedded final-host URL
   (covers future layout changes where the SPA pre-bakes the drive URL).
3. **WebView chain** — `WebViewResolver` intercepts the ad-chain at any
   `FINAL_HOST_REGEX` hit (now incl. `drive.olamovies.download` + `*.workers.dev`).
4. **Direct mint** (`emitFinal` + `mintDriveDirectUrl`) — on a
   `drive.olamovies.download/file/*` URL: fetch the page, regex `"_id":"(hex)"`
   (tolerates RSC-escaped `\"`), read `<h1>`, base64url-encode the filename, emit
   `https://olam.bypassbot.workers.dev/<id>?filename=<b64>` as the playable link.
5. Fallback stack intact: `loadExtractor()` for known hosts → direct-file emit.

## Honest limits (verified, not guesswork)

- A fully *silent* extraction from this sandbox is not possible today:
  `links.olamovies.mov` sits behind Cloudflare Turnstile and the `/api/omd`
  generate call is login + human-check gated (press-hold/slide). No public API
  currently answers anonymously — every anonymous probe returned 404/403/500.
- On a real device the WebView has real cookies + can finish the checks, so the
  on-app path (WebView chain → drive page → minted worker URL) is the working route.

## 20-second manual recipe (in any browser)

1. Open `https://v4.olamovies.mov/`, open any post, click a quality button.
2. Complete the generator's wait/human check → land on `drive.olamovies.download/file/…`.
3. View source, search `_id` — copy the hex id and the `<h1>` file name.
4. Direct link = `https://olam.bypassbot.workers.dev/<id>?filename=<base64url(name)>`
   (base64url = base64 with `+`→`-`, `/`→`_`, no `=` padding).
   The provider now automates steps 3-4.
