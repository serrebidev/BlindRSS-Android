# BlindRSS for Android

TalkBack-first Android port of desktop BlindRSS (Python/wxPython v2.3.9).
Kotlin + Jetpack Compose, Room, WorkManager, Media3 ExoPlayer. minSdk 26, targetSdk 35.

See [AUDIT.md](AUDIT.md) for the full feature/provider matrix, accessibility notes, and known gaps.

## Build

Windows (JDK 21, Android SDK at `%LOCALAPPDATA%\Android\Sdk`):

```
gradlew.bat testDebugUnitTest assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (signed with debug key).

## Features

- Local RSS/Atom/RDF/JSON-Feed, OPML import/export, URL feed discovery (incl. YouTube/Reddit/Lemmy shortcuts)
- Hosted sync: Miniflux, Inoreader, The Old Reader, BazQux, Feedbin, Google Reader/FreshRSS, Fever, NewsBlur
- All/Unread/Read/Favorites, categories, Smart Folders, Deleted restore/purge, Feeds-with-errors, filter rules
- Edit feed: rename, move to another category, change a local feed's address (synced to your provider)
- Full-text extraction, search, podcast playback (speed, seek, chapters, sleep timer, offline downloads, resume)
- Background sync with new-article notifications, per-feed mute
- TalkBack: labelled controls, merged article rows with custom actions, headings, live-region sync announcements

## Providers setup

Build: clone with `--recursive` (or run `git submodule update --init`); yt-dlp support comes from the `third_party/youtubedl-kotlin` submodule.

Settings → Add provider → pick one → enter credentials → Sign in. The login is tested before anything is saved; a first sync runs after.
Credentials are stored encrypted (Android Keystore AES-GCM). Inoreader: paste an OAuth access token (App ID/Key fields provided).
