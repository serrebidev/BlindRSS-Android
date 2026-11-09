## Build and release

Build on Windows from the repo root:

```powershell
.\gradlew.bat assembleRelease
```

- JDK 21 (Temurin) and the Android SDK from `local.properties` are required. The
  release build type signs with the debug config, so the APK carries the
  `~/.android/debug.keystore` certificate, SHA-256
  `e686d194cd9b6141b605ec8b7e992ed88a112c1113e13725bdeaa5ee88bf10ca`. Check it
  with `apksigner verify --print-certs` before publishing; an APK built with a
  different key cannot update an installed copy.
- Release on Forgejo (`serrebi/blindRSS-Android` on `git.serrebiradio.com`):
  push the branch first, then tag `vX.Y.Z` from `versionName` and push the
  tag (never force-push). Create the release via the API with BOM-less JSON
  (`POST /api/v1/repos/serrebi/blindRSS-Android/releases`, `tag_name`,
  title `BlindRSS for Android vX.Y.Z`), then upload the APK with the
  multipart field name `attachment`
  (`POST .../releases/<id>/assets?name=BlindRSS-Android-vX.Y.Z.apk`).
  The in-app updater reads `.../releases/latest` from that API; Forgejo
  assets carry no digest field, so no digest check is possible.
- From WSL (Muse Code CLI), there is no JDK in the distro. Run the same Windows
  build through interop rather than a Linux Gradle build, which would also
  produce a differently signed APK:

  ```bash
  cmd.exe /c "cd /d C:\Users\admin\git\BlindRSS-Android && gradlew.bat assembleRelease"
  ```

  A Muse session needs `--disable-sandbox` for interop, plus
  `--trust-workspace` so this file is loaded. Without interop the build cannot
  reach the Windows SDK or signing key.

## Agent skills

### Issue tracker

Issues are tracked in this repo's GitHub Issues, via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `GLOSSARY.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
