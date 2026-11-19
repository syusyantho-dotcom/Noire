# NOIR 0.5.0 — Source-driven Android manga reader

NOIR is an Android manga/manhwa reader built with Kotlin and Jetpack Compose.

## 0.5.0 major foundation
- Added a modular `MangaSource` provider contract and source registry.
- Added a live MangaDex provider for safe-rated catalogue discovery and title search.
- Loads real series metadata and cover artwork.
- Loads English chapter lists and chapter page manifests.
- Added a vertical scrolling page reader.
- Added a source catalogue screen and a library for saved titles.
- Added Coil image loading and OkHttp networking.
- Restored the cinematic NOIR intro and guarded intro chime.
- Kept the existing Android application ID and advanced version to 0.5.0.

## Source support status
The provider architecture is designed so more catalogue providers can be added independently. MangaDex is the only active online provider in this first 0.5.0 build; the source screen reports registered providers rather than pretending unsupported sources are available. This build requests safe-rated MangaDex titles only.

## Build
From this directory:

```bash
gradle --no-daemon :app:assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Notes
- An internet connection is required for online catalogue, chapter and page requests.
- Chapter availability depends on the selected source and its own content/language availability.
- Actual performance depends on device, Android version, network and image sizes; test on the target phone.
