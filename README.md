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

## Animation conventions

All animation in the app should target a smooth 60fps floor (the Thor's top
display runs up to 120Hz, so it can go higher, but nothing should drop below
60). Custom animated views drive themselves off `Choreographer.postFrameCallback`
and move by *elapsed real time between frames*, not by a fixed per-frame
step - see `ui/ScrollingTiledBackgroundView.kt` for the reference
implementation. This keeps motion speed correct regardless of which display
(60Hz or 120Hz) the activity is currently on, while still updating every
vsync for the smoothest motion that screen can show.

Imported pixel-art assets are scaled with nearest-neighbor filtering (no
blurring) and placed in `res/drawable-nodpi/` so Android's density system
doesn't rescale them a second time.

## Building

```
./gradlew assembleDebug
```
