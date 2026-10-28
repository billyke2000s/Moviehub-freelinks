# Development

## Requirements

- JDK 17
- Android SDK 35 (platform `android-35`, build-tools `35.0.0` and `36.0.0`)
- Gradle 9.6.0 with Android Gradle plugin 9.4.1 (the project has no Gradle wrapper; CI installs this version)
- Python 3.11 on the build machine (Chaquopy 17.1.0 packages the free film/series providers into the APK)
- Node 22 for the provider engine (`npm ci --ignore-scripts --prefix provider-engine`)
- An Android TV emulator or device for device tests (CI uses API 28, `tv_1080p` profile)

Every build has the same features. The normal build is MovieHub (`local.tv.home`); adding
`-Pexperimental` changes only the identity, to the separate MovieHub Experimental test app
(`local.tv.home.experimental`, own data, no home-screen role, own update feed), so a risky change can be
tried on the TV beside the real app. Those builds are published by `publish-experimental.yml` to the
`android-tv-experimental` pre-release and never touch the normal update feed.

## Build

```sh
gradle -p android-tv-launcher assembleDebug lintDebug
```

The debug APK is written to `android-tv-launcher/app/build/outputs/apk/debug/`. Without the signing
environment variables, release builds are unsigned. See [android-tv-launcher/UPDATES.md](android-tv-launcher/UPDATES.md)
for signed releases.

`versionCode` and `versionName` come from the `APK_VERSION_CODE` and `APK_VERSION_NAME`
environment variables (defaults `2` and `0.2` for local builds). Release workflows set them.

## Code style

Java sources are formatted with [prettier-plugin-java](https://github.com/jhipster/prettier-java)
using `.prettierrc.json` (4-space indent, 110 columns). Format before committing:

```sh
npm install --no-save prettier@3 prettier-plugin-java   # once; node_modules/ is git-ignored
npx prettier --write "android-tv-launcher/**/*.java"    # or --check to verify without changing
```

Formatting only changes whitespace. Do not hand-compress code onto long lines. That is how
`MainActivity` grew to a single 115 KB file before October 2026.

Conventions used in the code:

- Screens are built in Java, not XML layouts. Use the widget helpers in `TvActivity`.
- Background work goes through `activity.async(...)` so results from a screen the user has left
  are ignored.
- New screens go in the feature class for their area (see [ARCHITECTURE.md](ARCHITECTURE.md)). If
  a file grows past roughly 700 lines, split it by screen.
- Members other classes need are package-private. Keep everything else `private`.

## Running checks

See [TESTING.md](TESTING.md). The minimum before opening a pull request:

1. Core checks pass locally.
2. The debug build and lint pass.
3. Open a pull request and read the CI result. Device tests are currently flaky, so compare failures
   against unchanged `main`.

## Branches and releases

- Work on a branch and open a pull request against `main`. CI runs on pull requests.
- Merging to `main` does **not** publish an update to TVs. Publishing is a separate, manual workflow
  (`publish-android-launcher.yml`) described in [android-tv-launcher/UPDATES.md](android-tv-launcher/UPDATES.md).
- `release/moviehub-*` branches each hold a one-off publish workflow for that version. They are
  intentionally never merged.
