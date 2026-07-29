# DEBUGGING GUIDE — OlaMovies CloudStream Plugin

## Matlog (Recommended for Android)

1. Install **Matlog** app from F-Droid or Play Store.
2. Open Matlog → Filter → **Add filter**
   - Tag: `OlaMovies`
   - Level: Verbose
   - Save as **OlaMovies**
3. Reproduce the issue in CloudStream.
4. Export logs → Share.

## ADB (Desktop)

```bash
adb logcat -s "OlaMovies:V" CloudStream:V *:S
```

## Logging Points (in code)

- Provider initialization
- `search()` → query + count
- `getMainPage()` → section counts
- `load()` → title + type
- `loadLinks()` → each stream quality + link
- Exceptions are logged with context

## Common Issues

| Symptom                          | Cause                          | Fix                              |
|----------------------------------|--------------------------------|----------------------------------|
| No results on homepage           | Selector changed               | Update `OlaMoviesParserUtils`    |
| Empty links                      | GDrive extractors blocked      | Try `loadExtractor` + fallback   |
| Series episodes missing          | Season pack treated as movie   | Check `isSeries` detection logic |
| Crashes on load                  | Jsoup parse failure            | Add more runCatching             |

## Quick Validation

```bash
# In terminal (after build)
adb push build/outputs/apk/debug/*.apk /sdcard/
# Or use the plugin repo install flow
```