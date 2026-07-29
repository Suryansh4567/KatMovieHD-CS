# RECONNAISSANCE REPORT — OlaMovies (v3.olamovies.mov)

**Target:** https://v3.olamovies.mov/  
**Date:** 2026-07-29  
**Analysis Method:** curl + HTML inspection + manual browsing simulation

---

## 2.1 Endpoint Map

| Route Type          | URL Pattern                                      | Method | Response     | Pagination Style     |
|---------------------|--------------------------------------------------|--------|--------------|----------------------|
| Home                | `/`                                              | GET    | HTML         | `/page/N/`           |
| Category            | `/category/movies/`, `/category/tv-series/`, etc | GET    | HTML         | `/page/N/`           |
| Search              | `/?s=query` or `/search/query/`                  | GET    | HTML         | `/page/N/`           |
| Detail (Movie)      | `/slug-YYYY/`                                    | GET    | HTML         | N/A                  |
| Detail (Series)     | `/slug-YYYY/`                                    | GET    | HTML         | N/A                  |
| Episode List        | N/A (inline in series page)                      | —      | —            | —                    |
| Player/Embed        | None (GDrive direct)                             | —      | —            | —                    |
| Stream Manifest     | N/A (Google Drive direct links)                  | —      | —            | —                    |
| Subtitles           | Rarely present (mostly embedded in files)        | —      | —            | —                    |

---

## 2.2 Selector / JSON-Path Matrix

| Field               | Primary Selector                          | Fallback 1                          | Fallback 2                     |
|---------------------|-------------------------------------------|-------------------------------------|--------------------------------|
| title               | `.entry-title h3 a`                       | `h1.entry-title`                    | `title`                        |
| poster              | `img.wp-post-image` (src or data-src)     | `.entry-image img`                  | `meta[property="og:image"]`    |
| backdrop            | `meta[property="og:image"]`               | `img.attachment-gridlove-full`      | —                              |
| synopsis            | `.entry-content p:first-of-type`          | `.wp-block-paragraph:first`         | `meta[name="description"]`     |
| year                | From slug or title                        | `.entry-meta .meta-date`            | —                              |
| rating              | N/A                                       | —                                   | —                              |
| duration            | N/A (not shown)                           | —                                   | —                              |
| genres              | `.entry-category a`                       | `.tag-link`                         | —                              |
| episode number      | Parse from title (`S01`)                  | —                                   | —                              |
| season number       | Parse from title (`S01`)                  | —                                   | —                              |
| episode title       | Full title or episode name in body        | —                                   | —                              |
| episode thumbnail   | Same as poster                            | —                                   | —                              |

---

## 2.3 Request Profile

- **User-Agent**: Standard browser UA required
- **Headers**:
  - `Accept-Language: en-US,en;q=0.9`
  - Referer often not needed for home/search
- **Cookies**: None required (public site)
- **Redirects**: None significant
- **Player bootstrap**: NO in-page player. All links are **Google Drive direct download links** embedded in the post body as `<a>` tags with quality labels (720p, 1080p, 2160p, etc.).
- **External guide**: "How To Download" points to `http://play.ol-am.top`

---

## 2.4 Stream Resolution Pipeline

```
Detail Page (HTML)
    ↓
Parse post body for quality buttons / GDrive links
    ↓
Extract clean Google Drive share URLs (drive.google.com/file/d/...)
    ↓
Use built-in CloudStream GDrive extractor or custom resolver
    ↓
Return ExtractorLink(s) with quality labels
```

**Note**: No HLS/DASH. Pure progressive Google Drive files.

---

## 2.5 Fixture Capture

✅ Saved:

- `test-fixtures/home.html` — Homepage (30+ cards)
- `test-fixtures/search.html` — Search for "iron man"
- `test-fixtures/movie.html` — Iron Man 3 (2013) detail
- `test-fixtures/series.html` — Musafir Cafe (2026) S01 series

**Key Observations**:
- Site is **Google Drive focused** (GDrive direct download links).
- Both Movies and TV Series are hosted as full seasons in a single page.
- Quality is explicitly labeled in the buttons (720p [1.89GB], 1080p, etc.).
- Site uses Gridlove theme (WordPress).

**Gate Status:** ✅ All rows filled. Fixtures captured. Ready for PHASE 3.