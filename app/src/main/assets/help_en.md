# BlindRSS User Guide

BlindRSS for Android is a TalkBack-first RSS and podcast reader. It reads RSS,
Atom, RDF and JSON feeds on this device, or against a hosted account:
Miniflux, Inoreader, The Old Reader, BazQux, Feedbin, FreshRSS (Google Reader
API), Fever, or NewsBlur. This guide is stored inside the app and works with
no internet connection.

## Getting around

Every row is one TalkBack stop. Double tap opens it. Double tap and hold, or
swipe up or down with TalkBack, shows its actions: feeds can be refreshed,
edited, muted, marked read, or deleted; articles can be marked read, made a
favorite, played, downloaded, shared, read aloud, or deleted. Screen titles
are headings, so heading navigation jumps between sections.

## Home

Home lists your views first: All Articles, Unread, Read, Favorites,
Categories and Labels, Discover News Sources, Deleted Articles, Feeds with
Errors, Smart Folders, Filter Rules, Podcast Episodes, and the Player. Below
them, your feeds are grouped by category; categories start collapsed and can
be expanded, played, or downloaded from their actions. The top bar refreshes
everything and opens a menu with Add feed, Search, Mark all read, Settings,
and this guide.

## Adding feeds

Add Feed takes a feed address, a website address, or a page from a service
the app knows how to convert: a YouTube channel or playlist, a Reddit
community, a Lemmy or PieFed community, a Mastodon profile or tag, a Bluesky
profile, or a GitHub repository (which follows its releases). Discovery
finds the feed behind a website automatically. You can also import an OPML
file, export your subscriptions as OPML, or search podcast and people
directories: Apple Podcasts, gPodder, Feedsearch, Mastodon, Bluesky, and
PieFed.

Discover News Sources searches publishers by name, topic, or website, and
browses by category, country, and language, including local news for a city
and region you type in. Results can be previewed, subscribed one at a time,
or selected and subscribed together.

## Reading

Articles open in the browser by default. Settings, Reading, can open them in
BlindRSS instead, as feed text or as full text. Full text recovers the whole
article when a feed only ships a summary. YouTube videos read as articles:
the description, chapters, transcript, and top comments. Reddit and Lemmy
discussions read as one whole thread, the post followed by its comments.

Read aloud speaks an article through the player and then continues with the
newest unread article. Deleted articles are kept in Deleted Articles, where
they can be restored or deleted forever. Filter Rules act on articles as
they arrive: mark read, favorite, delete, move, label, or skip the
notification. Smart Folders are saved searches over titles, content, and
authors.

## Playing podcasts and videos

Episodes play in the built-in player with background audio, a mini player on
every screen, speed from 0.75 to 2.5 times, chapters, a sleep timer, an Up
Next queue, and resume where you stopped. Episodes can be downloaded for
offline listening. YouTube and other pages yt-dlp supports play and download
as audio on Android 11 or newer. Playback settings include skip silence,
SponsorBlock for YouTube, and a five-band equalizer in the player. The player
can cast to DLNA and UPnP renderers on your network.

## Sync, accounts, and notifications

Settings, Account, adds a hosted provider. The login is tested before
anything is saved, and read and favorite changes are sent back, even after
being offline. Background sync can run every 15 minutes to every 12 hours,
on Wi-Fi only if you choose, and new articles raise a notification unless
the feed is muted. Settings, Sync and notifications, also has the refresh
complete sound, how many articles to keep per feed, and Fetching: the
browser identity feeds are fetched with, plus clearance cookies for sites
behind bot protection.

## Feeds with errors, editing feeds

Feeds with Errors lists feeds that stopped updating, with retry and edit
actions. Edit feed changes a feed's title, moves it to another category, or
changes a local feed's address; the new address is checked first, and your
articles keep their read, favorite, download, and resume state. With a
provider signed in, renames and moves happen on the server too.

## About this app

BlindRSS is free and open source under the MIT license. Settings, About,
checks for updates and updates the built-in yt-dlp. Questions and release
news are in the SerrebiProjects Telegram group.
