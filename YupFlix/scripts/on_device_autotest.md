# YupFlix — On-Device AutoTest (manual, not shipped in the AAR)

> This file replaces the old in-code `runAutoTest()` harness that was removed in
> TASK 02.1 (D2). It is plain documentation for a human/CI operator running a
> real CloudStream 3 app + emulator/device. None of this code is compiled into
> the `.cs3`.

## Prerequisites
- A real CloudStream 3 APK (`com.lagradost.cloudstream3`) installed in an
  emulator (with KVM) or on a physical device reachable via `adb`.
- `YupFlix.cs3` built from this module (`gradlew :YupFlix:assembleRelease`).
- `adb` on your `PATH`.

## 1. Sideload the plugin
```bash
adb push YupFlix.cs3 /sdcard/Android/data/com.lagradost.cloudstream3/files/plugins/
# then in CS3: Settings ▸ Extensions ▸ Install from storage (or restart the app)
```

## 2. Capture logcat
```bash
adb logcat -s "YupFlix" "Plugin" "CloudStream" | tee yupflix_device.log
```

## 3. Flows to exercise
Open YupFlix in CS3 and confirm:

- **Homepage** — rows render; note the first 3 section titles.
- **Search "stranger"** — ≥ 5 results, both movies and series present.
- **Movie** "The Strangers" (`_id=6a452eda5f5543dc5c4d42bf`) → play.
  Expect `ExtractorLink` callbacks with source `YupFlix` for **both** 480p and
  720p HLS. Verbatim 720p URL from the live API:
  `https://cdn5.streamraiwind.stream/img/142c39b165ec/nasty.m3u8?token=...`
  Let the ExoPlayer buffer and actually start playback (screenshot the frame).
- **Series** "Stranger" (`_id=6a4d547d5f5543dc5c807e3b`) → Season 1, Episode 1
  "I Am Innocent" → play. Confirm a 720p HLS `ExtractorLink` and real playback.

## 4. What success looks like in logcat
The `loadLinks` callback emits one `ExtractorLink` per streaming link. If you
wire a debug logger, each should look like:
```
I YupFlix: [RESULT] movie name='YupFlix' url='https://cdn5.streamraiwind.stream/img/142c39b165ec/nasty.m3u8?token=...'
I YupFlix: [RESULT] movie name='YupFlix' url='https://cdn5.streamraiwind.stream/img/14993572c573/nasty.m3u8?token=...'
```

## 5. Regression — TheNextPlanet must still work
In the same CS3 session, also load **TheNextPlanet** and open the **Starman**
page. Confirm Mediafire / GDFlix / Photolinx still emit their `[RESULT]` lines
exactly as in TheNextPlanet v4. If any of those regress, the YupFlix change is
suspect — do not ship.
