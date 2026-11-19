# NOIR 0.3.0 — Android Manga & Manhwa Reader

NOIR is a Kotlin + Jetpack Compose Android reading-room project.

## 0.3.0 updates
- New monochrome silver NOIR launcher icon.
- Short cinematic logo intro with an original, quiet chime sound.
- Intro sound is suppressed when Android is in silent/vibrate mode or media volume is zero.
- Short crossfade between app sections to reduce heavy screen-transition work.
- Stable keys for the Home carousel items.
- Version bumped to 0.3.0.

## Build
Open this `NOIR` directory in Android Studio and build the `app` debug variant, or run:

```bash
gradle --no-daemon :app:assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Performance note
The UI uses lightweight vector/Canvas artwork and a short fade transition. Actual frame pacing depends on the device, Android version, thermal state, and the content shown; test on the target phone before claiming a guaranteed frame rate.

## Current scope
This build continues the existing NOIR UI prototype. Real online manga sources, image downloading/caching, and a full CBZ/CBR reader are not yet implemented.
