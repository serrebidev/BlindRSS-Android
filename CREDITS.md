# Credits

BlindRSS for Android is the TalkBack-first port of desktop [BlindRSS](https://github.com/serrebidev/BlindRSS) (Python/wxPython v2.3.9). Same reader, rebuilt in Kotlin with Jetpack Compose.

Third-party projects it uses:

- yt-dlp support comes from the `third_party/youtubedl-kotlin` submodule: [Darkstar085/youtubedl-kotlin](https://github.com/Darkstar085/youtubedl-kotlin), the maintained fork of [yausername/youtube-dl-android](https://github.com/yausername/youtube-dl-android) (both GPL-3.0). It bundles Python, FFmpeg, and QuickJS, and self-updates yt-dlp at runtime.
- Room, WorkManager, and Media3 ExoPlayer (Jetpack libraries), plus OkHttp and jsoup for provider sync and Readability-style full-text extraction.

Questions and bug reports are welcome in the [SerrebiProjects Telegram group](https://t.me/SerrebiProjects). More serrebi projects: [projects.serrebiradio.com](https://projects.serrebiradio.com).
