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
| Full-text extraction | Done | `FullText.extract` Readability-style scored extraction (jsoup, link-density penalty, tightest-container pick, boilerplate drop); browser user-agent; Miniflux server fetch-content preferred when available; verified on mapleridgenews.com (WordPress, no <article> tag) |
| Search | Done | Room LIKE over title/content/description |
| Mark read/unread, mark all read | Done | Row actions, custom accessibility actions, provider push-back |
| Article reader HTML | Done | Plain-text rendering, one TalkBack stop per paragraph (headings marked; body never hidden behind a contentDescription), Fetch full text (never blanks: keeps feed text on failure; auto full-text only swaps in longer text), share, open externally. Opening articles is configurable: browser (default), full text in app, feed text in app; YouTube opens in YouTube app / SmartTube / browser / in app (Settings > Reading) |
| Podcast/video playback | Done | Media3 ExoPlayer, `PlaybackService` MediaSession, speed 0.5–2x, seek ±10s, resume via `playback_state` |
| Chapters | Done | `chapters` table, Player chapter list + prev/next; Podcasting 2.0 `<podcast:chapters>` JSON fetched and parsed (`PodcastChapters`, tested) |
| Sleep timer | Done | Player 15/30/60 min |
| Download episodes offline | Done | `Downloader` to external files, playback prefers local file |
| Notifications new articles | Done | WorkManager `SyncWorker` scheduled on every launch (interval or off, Wi-Fi only option), shared `Repository.refreshAll` (local feeds 8 at a time, then provider sync), muted feeds and skip-notification rule excluded from the count, tapping the notification opens the app, retries only when nothing worked |
| Settings | Adapted | Account (Add provider menu, per-provider sign-in screen that tests before saving, edit, sync now, sign out), Reading, Article lists, Sync and notifications (interval or off, Wi-Fi only, refresh on open, new-article alerts with Android 13 permission), Playback (default speed applied on start, skip silence, SponsorBlock), Discovery keys, About (version, update check). Credentials Keystore AES-GCM |
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
| NewsBlur | Done (new) | Username and password via `/api/login` (session cookie stored) | `/reader/feeds?flat=true` folders, unread story hashes + starred, mark read/unread, star/unstar |
| Feedbin | Done (new) | HTTP Basic (email, password) | REST v2: subscriptions + taggings, unread/starred id lists, entries by id, bulk read/star edits, subscribe with tag, unsubscribe |

All provider clients use OkHttp with real endpoints — no stubs. Sign-in runs `testLogin()` before anything is saved.

Sync (`Repository.syncWith`) mirrors the account: feeds added, renamed, moved or removed on the server; read and favorite state of known articles updated each sync; Fever and NewsBlur also mark local copies read when they leave the server's unread list. Provider feeds are never fetched directly (no duplicates). Add feed subscribes on the server (Fever: stays local, no subscribe API); delete feed unsubscribes first; mark feed read and mark all read are pushed. Switching or signing out turns the old provider's feeds into local feeds. Articles the user deleted do not come back. Read and favorite changes go through an outbox (DataStore): sent at once when online, retried at the start of every sync, and server state never overwrites a change still waiting. Miniflux syncs incrementally with `changed_after` (60 s overlap, cursor stored, falls back to windows if the server ignores the filter); manual Refresh first calls `PUT /v1/feeds/refresh`; articles have a "Save to integrations" action (`POST /v1/entries/{id}/save`) that sends them to the read-later services configured in Miniflux.

Refresh uses one shared OkHttp client with a 50 MB HTTP cache and `Cache-Control: no-cache`, so unchanged feeds answer 304 and are not parsed. Feed text is decoded with the HTTP charset, else the XML declaration, else UTF-8. One Room instance per process, shared with the background worker, so worker results show in open lists. Optional settings: save full text for offline reading (up to 50 unread articles from the last 7 days per refresh, stored in content and tagged `__fulltext`), keep newest N articles per feed (favorites kept). Read aloud: text-to-speech renders the article (full text when available) to audio parts played through the media player, then marks it read and continues with the newest unread article. Fetch windows: Google Reader API newest 200 + 200 starred; Miniflux newest 200 + 500 unread + 200 starred; Fever all unread + saved; NewsBlur 500 unread (by hash) + 5 pages starred. FreshRSS URL accepts the `api/greader.php` root.

## Accessibility (TalkBack-first, per .firecrawl CVS techniques)

One TalkBack focus stop per row: rows have no buttons of their own. Article, feed, deleted, error, smart-folder, filter-rule and directory rows merge descendants into one stop with `customActions` (mark read, favorite, download, share, play, delete; refresh/mute/mark-read/delete for feeds) and `stateDescription`; the same actions open as a menu on double-tap-and-hold (long press, `combinedClickable`); rows announce 'Double tap to open. Double tap and hold for actions.' Remaining buttons are screen-level only: back, refresh-all, menus, and the player transport controls. Article rows read in user-controlled order (Settings > Reading > How article lists are read): article name, source, author/date/flags, article text (summary / first 600 chars / full text), then read status at the end; every part can be switched off and the status moved or removed. Feed/section titles use `heading()`; sync results/errors post to a polite live region (`LiveAnnouncer`) and notifications.

## Discovery (v1.3.0)

Home > Discover News Sources: search by publisher name, topic, or website URL; browse by category (General, World, Canada, Local, Politics, Business, Technology, Science, Health, Entertainment, Sports, Gaming); filter by country and language; manual city/region for local news (no GPS). Providers: Feedly public feed search (keyless, verified live; disables gracefully on failure), Plenary awesome-rss-feeds OPML catalogue (disk-cached 7 days, built-in Canadian starter set, tolerant OPML parsing for malformed files), hardened website autodiscovery (link tags + common paths, only parse-verified feeds shown, private/loopback destinations blocked, size and timeout caps), Google News RSS (headlines/topic/search per country+language, labelled as search feeds), Apple Podcasts + gPodder (Add Feed), and optional NewsAPI.org / The News API (Settings keys, encrypted; publishers only appear after website autodiscovery verifies a feed). Results are deduplicated by canonical feed URL, show subscription status, support preview (recent headlines), subscribe/unsubscribe, and batch subscribe. Desktop BlindRSS gained the same catalogue, localized Google News, and optional News API sources in its existing Feed Search dialog.

## Known gaps

Chromecast/AirPlay casting not implemented (DLNA/UPnP covers local-network casting without Play Services); podcast archive and YouTube-account providers still desktop-only (need yt-dlp/OAuth scraping); Feedly/NewsBlur directory search not included (Apple/gPodder only); live server logins were not re-tested after this pass.
