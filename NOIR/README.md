# NOIR 1.2.0 — Premium Android manga reader

NOIR is an Android manga/manhwa reader built with Kotlin and Jetpack Compose. Its visual direction is AMOLED black, silver typography, restrained violet accents, cinematic artwork and spring-driven glass navigation.

## Implemented
- Live MangaDex catalogue discovery and title search for safe-rated entries.
- Kotatsu-Redo community parser integration: source registry, source search, details, chapters and page loading through a source-neutral NOIR adapter.
- Source library with parser metadata, language/category search, enable/disable controls and persistent pinning.
- Paginated catalogue browsing and search, loading additional MangaDex result pages on demand.
- Manga details, synopsis, chapter list and chapter page loading.
- Chapter feed pagination, including English and Indonesian translations.
- Reader modes: vertical scroll, horizontal paged, right-to-left paged, and webtoon scroll.
- Persistent reader-mode preference and saved reading position per chapter.
- Persistent saved library and recently opened titles.
- Provider-aware title identity so matching IDs from different sources do not collide.
- Source library with search, Active/Pinned filters and persistent pin preferences.
- Animated bottom navigation with spring motion and a draggable glass indicator.
- Cinematic intro animation, optional intro chime and launch-animation settings.

## Online source support
**MangaDex is enabled by default.** The app now bundles the Kotatsu-Redo parser registry (1,386 source entries in the upstream summary) and lets readers enable other parsers individually. Parsers that need special WebView interception, account login, proxy configuration, or site-specific fixes may still fail; the source list is not a guarantee that every website is currently reachable. NOIR filters adult-rated manga results and excludes the duplicate Kotatsu MangaDex entry in favour of its native MangaDex adapter.

NOIR does not claim unsupported sources as working integrations. Source availability, chapter translations and image delivery depend on MangaDex and the network connection.

## Not yet implemented
- Validation and compatibility fixes for community provider adapters; source uptime and anti-bot changes remain outside NOIR's control.
- Offline chapter downloads and download management.
- Custom library categories, chapter bookmarks and reading history controls.
- New chapter update checks, tracking integrations, sync and backup/restore.
- A signed production/release APK and Play Store packaging.
- Broader device-level testing of parser sites, WebView-dependent sources and pagination.
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

## Licensing

The Kotatsu parser integration is licensed under GPL-3.0. NOIR 1.2.0 is distributed as a combined GPL-3.0 application. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for license text and attribution.
