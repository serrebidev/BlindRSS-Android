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
- Release: tag `vX.Y.Z` from `versionName`, then `gh release create vX.Y.Z` with
  the asset named `BlindRSS-Android-vX.Y.Z.apk` and title
  `BlindRSS for Android vX.Y.Z`.
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
