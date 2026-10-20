# BlindRSS Android — Audit

Port of desktop BlindRSS (Python/wxPython, v2.3.9) at `/mnt/c/Users/admin/git/BlindRSS` to Kotlin + Jetpack Compose.
Providers audited from `providers/` (`base.py`, `local.py`, `miniflux.py`, `inoreader.py`, `theoldreader.py`, `bazqux.py`, `youtube_account.py`, `hosted_podcast_archive.py`), `core/config.py`, `core/db.py`, `core/discovery.py`, `gui/dialogs.py`.

## Feature matrix

| Original feature | Android status | Notes |
|---|---|---|
| RSS/Atom/RDF/JSON-Feed parsing | Done | `feed/FeedParser.kt` XmlPull via DOM, namespaces, enclosures, MediaRSS fallback |
| OPML import/export | Done | `feed/Opml`, nested folders to ` / ` paths, file picker import, export to app files + share |
| Feed discovery from URL | Done | HTML `<link rel=alternate>`, `FeedDiscovery.normalizeInput` |
| All/Unread/Read/Favorites views | Done | Home + `articles/{mode}`, unread is filter equivalent of desktop Ctrl+1/2/3 |
| Categories/folders | Done | Category string on feed, grouped headings on Home |
| Smart Folders / saved searches | Done | `smart_folders` table, title/content/author contains, live results |
| Deleted articles restore/purge | Done | `deleted_articles` tombstone, Restore + DeleteForever + purge all |
| Feeds-with-errors view | Done | `feeds.lastError`, `observeErrors()`, retry action |
| Filter rules | Done | Arrival pipeline in `Repository.saveArticles`: mark_read, mark_favorite, delete, move (folder override), label (extra category membership), skip_notification; browse via Categories and Labels |
| Full-text extraction | Done | `FullText.extract` Readability-style article/main extraction, stripped scripts/nav |
| Search | Done | Room LIKE over title/content/description |
| Mark read/unread, mark all read | Done | Row actions, custom accessibility actions, provider push-back |
| Article reader HTML | Done | Plain-text rendering of stripped HTML, Fetch full text, share, open in browser |
| Podcast/video playback | Done | Media3 ExoPlayer, `PlaybackService` MediaSession, speed 0.5–2x, seek ±10s, resume via `playback_state` |
| Chapters | Done | `chapters` table, Player chapter list + prev/next; Podcasting 2.0 `<podcast:chapters>` JSON fetched and parsed (`PodcastChapters`, tested) |
| Sleep timer | Done | Player 15/30/60 min |
| Download episodes offline | Done | `Downloader` to external files, playback prefers local file |
| Notifications new articles | Done | WorkManager `SyncWorker` + notification, per-feed mute flag |
| Settings | Adapted | Provider, server, credentials (Keystore AES-GCM), sync interval, notify, playback speed persisted |
| YouTube/Reddit/Lemmy URLs as feeds | Done | `FeedDiscovery.normalizeInput`: channel ID RSS, subreddit `.rss`, Lemmy `/feeds/c|u/*.xml` |
| Discovery search | Done (partial) | Apple Podcasts (iTunes Search) + gPodder search in Add Feed, no API key; Feedly/NewsBlur search need accounts/keys |
| Podcast archive, YouTube account recommendations | Desktop-only | Needs yt-dlp + YouTube OAuth scrape; no Android equivalent shipped |
| Casting | Done (DLNA) | SSDP discovery + UPnP AVTransport SOAP play/stop in Player (`cast/Dlna.kt`); Chromecast/AirPlay need Play Services/proprietary protocols |
| Equalizer, silence skip, SponsorBlock | Done | AndroidFX Equalizer (5-band sliders, persisted), ExoPlayer skipSilence, SponsorBlock API skip for YouTube articles (sponsor/selfpromo/interaction) |
| Tray, hotkeys, updater, cookies/captcha | Desktop-only | Windows concepts; replaced by notifications, TalkBack actions, store updates |

## Provider matrix

| Provider (desktop file) | Android | Auth | Sync |
|---|---|---|---|
| Local (`local.py`) | Done | None | Direct feed fetch to Room |
| Miniflux (`miniflux.py`) | Done | `X-Auth-Token` API key | `/v1/feeds`, `/v1/entries`, bookmark toggle, mark-all, fetch-content client present |
| Inoreader (`inoreader.py`) | Done | OAuth2 browser connect (`blindrss://oauth` callback), code exchange + auto refresh on 401 | Google Reader endpoints via Bearer |
| The Old Reader (`theoldreader.py`) | Done | ClientLogin Email/Passwd → GoogleLogin | Shared `GReaderProvider` |
| BazQux (`bazqux.py`) | Done | ClientLogin → GoogleLogin | Shared `GReaderProvider` |
| Google Reader generic / FreshRSS | Done (new) | ClientLogin → GoogleLogin | Covers FreshRSS `api/greader.php` |
| Fever (FreshRSS Fever API) | Done (new) | `api_key = md5(user:pass)` | feeds/items/mark, all unread+saved IDs in 50-item chunks; no subscribe endpoint in Fever API |
| NewsBlur | Done (new) | `newsblur_sessionid` cookie | `/reader/feeds` with flat_folders mapping, per-feed stories, mark read/save |

All provider clients use OkHttp with real endpoints — no stubs. `testLogin()` verifies credentials from Settings.

## Accessibility (TalkBack-first, per .firecrawl CVS techniques)

One TalkBack focus stop per row: rows have no buttons of their own. Article, feed, deleted, error, smart-folder, filter-rule and directory rows merge descendants into one stop with `customActions` (mark read, favorite, download, share, play, delete; refresh/mute/mark-read/delete for feeds) and `stateDescription`; the same actions open as a menu on double-tap-and-hold (long press, `combinedClickable`); rows announce 'Double tap to open. Double tap and hold for actions.' Remaining buttons are screen-level only: back, refresh-all, menus, and the player transport controls. Feed/section titles use `heading()`; sync results/errors post to a polite live region (`LiveAnnouncer`) and notifications.

## Known gaps

Chromecast/AirPlay casting not implemented (DLNA/UPnP covers local-network casting without Play Services); podcast archive and YouTube-account providers still desktop-only (need yt-dlp/OAuth scraping); Feedly/NewsBlur directory search not included (Apple/gPodder only); live server logins were not re-tested after this pass.
