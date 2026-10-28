# App updates

The home screen checks the public `android-tv-latest` release in billyke2000s/moviehub when opened, at most once per six hours during an app process. The Update button beside Settings stays hidden if the release is missing, unreachable, invalid or not newer than the installed version. No account token is needed for this public feed.

Selecting Update asks before downloading, verifies the SHA-256 checksum, package name, increasing version code and matching signing certificate, then opens Android's installer. Android may first require permission to install updates from Home. No root or silent installation is used. A failed check is silent; a failed download/verification produces a retry message. APK downloads are limited to 300 MiB and use HTTPS.

The signed APK and update manifest are published in the `android-tv-latest` release. `.github/workflows/publish-android-launcher.yml` uses the following configured GitHub Actions secrets:

- APK_KEYSTORE_BASE64: base64-encoded permanent private signing keystore
- APK_STORE_PASSWORD
- APK_KEY_ALIAS
- APK_KEY_PASSWORD

Keep and back up that signing key; use it for the first release installation and all future releases. Existing debug APKs are not a verified upgrade path to this release key. The updater refuses APKs signed with a different certificate; an initial move from a debug build may require a separate installation and data migration plan.

Run Publish Android TV launcher update on the intended source branch, with a visible version name and an integer version code greater than every previous release. It builds and lints the release, verifies its signature, and publishes home.apk and update.json. Publishing is not triggered by ordinary debug builds or merges. The permanent signing secrets are configured, and the initial signed release has been published.

The HTML preview hides the button normally. Append `?updateDemo=1` to its URL to see the illustrative update-available state. It does not perform a live release check or install anything.

Offline compilation cannot establish successful installation, unknown-source settings availability, or update behavior on the Tanix box. Those require device testing.

The release workflow now runs core checks and Android database/player tests before signing. It downloads the current updater baseline, verifies its checksum and refuses publication if the new certificate differs. Same-repository PRs also build a signed candidate and check package, increasing version code and signing continuity without publishing it. The package remains `local.tv.home`, and the existing `android-tv-latest/home.apk` and `update.json` paths and manifest fields are unchanged. Since 0.15, `update.json` may also carry an optional `notes` field: the CHANGELOG section headed with the published version name (for example `## 0.15 (date)`), shown as "What's new" in the update prompt. Older app versions ignore it.

## Numbered releases on the repository page

Publishing updates the `android-tv-latest` feed that TVs use; it does not change the "Latest"
release shown on the repository page. To show the current version there, run the
**Make a GitHub release of the published update** workflow (`release-android-launcher.yml`). It
copies the signed `home.apk` and `update.json` from the feed (checking the APK's SHA-256 against
the feed) into a release named `MovieHub <version>` (tag `moviehub-v<version>`), marks it as the
latest release, and renames the feed release "MovieHub <version> — updater feed". It builds and
signs nothing and does not change the feed paths.

## Experimental app

`gradle -Pexperimental …` builds **MovieHub Experimental**: package `local.tv.home.experimental`,
label "MovieHub Experimental", no home-screen role, and its own update feed
(`android-tv-experimental` pre-release). It installs next to MovieHub and keeps its own data
(channels and accounts are set up again inside it). Since 1.0 it has the same features as MovieHub;
only its identity differs, so it is for trying a risky change on the TV before it reaches MovieHub.

Run **Publish MovieHub Experimental** (`publish-experimental.yml`) on the branch to test, with an
increasing version code. It uploads `moviehub-experimental.apk` (to install by hand) and
`home.apk`/`update.json` (for the experimental app's own Update button). It never touches
`android-tv-latest`.
