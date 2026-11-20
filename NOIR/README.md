# NOIR 1.0.0 — Premium Android manga reader

NOIR is an Android manga/manhwa reader built with Kotlin and Jetpack Compose. Its visual direction is AMOLED black, silver typography, restrained violet accents, cinematic artwork and spring-driven glass navigation.

## Implemented
- Live MangaDex catalogue discovery and title search for safe-rated entries.
- Manga details, synopsis, chapter list and chapter page loading.
- Chapter feed pagination, including English and Indonesian translations.
- Vertical page reader with saved reading position per chapter.
- Persistent saved library and recently opened titles.
- Provider-aware title identity so matching IDs from different sources do not collide.
- Source library with search, Active/Pinned filters and persistent pin preferences.
- Animated bottom navigation with spring motion and a draggable glass indicator.
- Cinematic intro animation, optional intro chime and launch-animation settings.

## Online source support
**MangaDex is currently the only active online provider.** Other names shown in the source library are clearly marked as not installed/planned; they do not currently provide search, chapters or reader pages. The MangaSource interface and SourceRegistry are the extension points for future original provider adapters.

NOIR does not claim unsupported sources as working integrations. Source availability, chapter translations and image delivery depend on MangaDex and the network connection.

## Not yet implemented
- Additional community provider adapters.
- Offline chapter downloads and download management.
- A signed production/release APK and Play Store packaging.
- Automated UI/instrumentation tests and a full device compatibility test pass.

## Build
From this directory:

```bash
gradle --no-daemon :app:assembleDebug
```

The debug APK is generated at app/build/outputs/apk/debug/app-debug.apk. GitHub Actions builds and uploads the debug APK on relevant pushes.

## Requirements and notes
- Android 8.0 (API 26) or newer.
- Internet access for online catalogue, chapter and page requests.
- Debug builds are for testing; a production release needs a protected release-signing key.
- Compilation and APK packaging do not replace testing on physical devices.
