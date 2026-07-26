# PokéGear: Heart & Soul

An Android app for the [AYN Thor](https://www.ayntec.com/) handheld, built for its dual-AMOLED layout:

| | Top display | Bottom display |
|---|---|---|
| Size | 6.0" | 3.92" |
| Resolution | 1920×1080 | 1240×1080 |
| Aspect ratio | 16:9 | 31:27 (near-square) |
| Refresh rate | 120 Hz | 60 Hz |

By default the app launches on the **bottom (3:4-ish) display**. This can be
flipped to the top display from in-app Settings.

## Status

Early scaffold — the app currently just opens to a home screen that says
"Hello Johto!" plus a Settings screen with the display-preference toggle.

## Package

`com.logie.pgearhs`

## How display routing works

Android has no native concept of "top" vs "bottom" screen on the Thor. On
launch, `DisplayRouter` (`app/src/main/java/com/logie/pgearhs/DisplayRouter.kt`)
enumerates the device's displays via `DisplayManager`, classifies them by
aspect ratio (the near-square one is the bottom display), and — if the
activity isn't already running on the preferred display — relaunches itself
there via `ActivityOptions.setLaunchDisplayId()`.

The preference is stored in `SharedPreferences` and defaults to the bottom
display; toggle "Launch on top display" in Settings to change it.

## Building

```
./gradlew assembleDebug
```
