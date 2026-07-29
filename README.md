# OlaMovies — CloudStream Plugin

**Highest Quality UHD Movies and Series Google Drive**

A CloudStream 3/4 extension for [OlaMovies Official](https://v3.olamovies.mov/).

## Features

- ✅ Homepage with recent releases and category sections
- ✅ Full search support
- ✅ Movies + TV Series
- ✅ Direct Google Drive quality links (720p → 2160p)
- ✅ Hindi + multi-audio titles
- ✅ Matlog-ready structured logging

## Requirements

- CloudStream 4.0+
- Android 5.0+

## Build Instructions

```bash
# Clone this repo
git clone https://github.com/Suryansh4567/KatMovieHD-CS.git
cd KatMovieHD-CS

# Build
./gradlew clean build
```

## Install in CloudStream

1. Open **CloudStream**
2. Go to **Extensions** → **Add Repository**
3. Paste:  
   `https://raw.githubusercontent.com/Suryansh4567/KatMovieHD-CS/main/repo.json`  
   *(or use the builds branch after CI runs)*
4. Install **OlaMovies**

## Matlog Debugging

Filter: `OlaMovies`

See full guide: [docs/DEBUGGING.md](docs/DEBUGGING.md)

## Architecture

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

## Troubleshooting

| Symptom                    | Cause                     | Fix                              |
|----------------------------|---------------------------|----------------------------------|
| No homepage content        | Site layout change        | Update selectors in ParserUtils  |
| GDrive links not playing   | Extractor limitation      | Use "Download" in CloudStream    |
| Crashes on load            | Parse error               | Check Matlog for stack trace     |

## Known Limitations

- No subtitles (rarely provided)
- Series delivered as full season packs (treated as single episode)
- Google Drive links may require login / quota in some regions

## Changelog

See [CHANGELOG.md](CHANGELOG.md)

---

**Built autonomously with Kimi K3 Engineer v3.0**
