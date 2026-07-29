# ARCHITECTURE DESIGN — OlaMovies Plugin

**Plugin Name:** OlaMovies  
**Package:** `com.olamovies.cloudstream`  
**Version Target:** CloudStream 4+ (pre-release compatible)

---

## 1. Chosen Strategy

**HTML / Jsoup** — justified because:
- Entire site is static WordPress HTML
- No public API or JSON endpoints found
- Google Drive links are embedded directly in post body as anchor tags
- Consistent Gridlove theme selectors across home, search, and detail pages

No need for serialization or complex network flows.

---

## 2. File Tree

```
olamovies-plugin/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── Makefile
├── README.md
├── CHANGELOG.md
├── LICENSE
├── .gitignore
├── .github/
│   └── workflows/
│       └── build.yml
├── docs/
│   ├── RECON.md
│   ├── ARCHITECTURE.md
│   └── DEBUGGING.md
├── src/main/kotlin/com/olamovies/cloudstream/
│   ├── OlaMoviesPlugin.kt
│   ├── OlaMoviesProvider.kt
│   ├── OlaMoviesExtractors.kt
│   ├── OlaMoviesConstants.kt
│   └── OlaMoviesParserUtils.kt
├── src/test/kotlin/com/olamovies/cloudstream/
│   ├── Fixtures.kt
│   ├── SearchParserTest.kt
│   ├── DetailParserTest.kt
│   └── ExtractorTest.kt
└── test-fixtures/
    ├── home.html
    ├── search.html
    ├── movie.html
    └── series.html
```

---

## 3. Class Responsibility Table

| Class                        | Responsibility |
|-----------------------------|---------------|
| `OlaMoviesPlugin`           | Entry point. Registers `OlaMoviesProvider` |
| `OlaMoviesProvider`         | MainAPI impl: getMainPage, search, load, loadLinks |
| `OlaMoviesConstants`        | BASE_URL, headers, CSS selectors, regex patterns |
| `OlaMoviesParserUtils`      | Pure parsing functions (cards, metadata, links) |
| `OlaMoviesExtractors`       | Google Drive link resolver (or thin wrapper) |
| `Fixtures` (test)           | Loads HTML fixtures for unit tests |

---

## 4. Data-Flow Diagram (ASCII)

```
User action
    ↓
getMainPage / search(query)
    ↓
Jsoup GET + parse
    ↓
OlaMoviesParserUtils.parseSearchCards() / parseHomeCards()
    ↓
List<SearchResponse>
    ↓
load(url)
    ↓
Jsoup GET + parse detail
    ↓
ParserUtils.parseMovieLoadResponse / parseSeriesLoadResponse
    ↓
LoadResponse (Movie / TvSeries)
    ↓
loadLinks(data)
    ↓
Parse GDrive links from content
    ↓
OlaMoviesExtractors.resolveGoogleDrive()
    ↓
newExtractorLink(...) → callback()
```

---

## 5. Key Design Decisions

- **TvType support**: `Movie`, `TvSeries`
- **Language**: `hi` (Hindi) + `en` support (multi-audio)
- **hasMainPage = true**
- **hasQuickSearch = true**
- **Episodes**: Since the site delivers **full season packs**, we treat each series page as a single episode (S01E01) or generate a single episode per season pack for simplicity.
- **Streaming**: Direct GDrive links. Will rely on CloudStream's built-in `loadExtractor` or implement simple GDrive support.
- **No subtitles**: Rarely present — skip or pass-through if found.
- **Quality mapping**: Parse labels like `1080p [2.92GB]` → Qualities.P1080

**Gate:** ✅ Architecture document complete. Proceeding.