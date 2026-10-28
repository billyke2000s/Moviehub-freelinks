# Rules for AI assistants working on this repository

Read [ARCHITECTURE.md](ARCHITECTURE.md), [TESTING.md](TESTING.md) and [STATUS.md](STATUS.md)
before changing code.

## Keep the code readable

- Format with prettier before committing (see [DEVELOPMENT.md](DEVELOPMENT.md#code-style)).
  Never put several statements on one line or write very long lines to save space. The whole app
  was once a single 115 KB file of 2,000-character lines, and no assistant could edit it reliably.
- Put code in the feature class for its area. If a file passes about 700 lines, split it by screen
  in a separate commit that changes no behaviour.
- Do not add a new pattern (Fragments, Kotlin, a DI framework, XML layouts) without the owner
  agreeing first. Follow the existing screen pattern described in ARCHITECTURE.md.

## Verify, and report honestly

- Run the core checks locally. Open a pull request and read the CI result before saying a change
  works.
- Device tests were made reliable in October 2026 (8/8 parallel runs). If one fails, treat it as a
  real problem until shown otherwise: read its annotation, and compare with unchanged `main` before
  calling it unrelated.
- Never claim something was tested on a real TV or a real provider account unless it was. The
  fixtures do not prove live provider behaviour.
- If you could not run something, say so in the PR description.

## Tests and app members

Device tests call app members directly (no reflection), so the compiler catches a rename. Keep it
that way: if a test needs a private member, make it package-private rather than using reflection.

## Git and releases

- Work on a branch and open a pull request. Do not push directly to `main`.
- Keep a refactor and a behaviour change in separate commits so each can be reviewed.
- Merging does not publish an update. Only run the publish workflow when the owner asks, and never
  change the signing key, the package name `local.tv.home`, or the update feed paths.
- Never commit credentials, keystores, provider URLs or account details.

## Adding sources

New source URLs go in two marked lists, each empty until filled in. Add a line there and change
nothing else:

- Films and series (Stremio-style stream providers): `DefaultSourceProviders.EXTRA` in
  `android-tv-launcher/app/src/main/java/local/tv/home/DefaultSourceProviders.java`. One
  `https://…/manifest.json` per line, separated by commas, with no comma after the last one.
- TV channel lists (M3U playlists): `FreeChannels.EXTRA` in
  `android-tv-launcher/app/src/main/java/local/tv/home/FreeChannels.java`. One
  `{ "id", "Name", "https://…" },` per line. The id is stored with each channel: short, lower case,
  unique, never changed, and not `xtream`, `m3u`, `fta` or `free`.

Only add public URLs that carry no account details (AGENTS.md: never commit credentials or
provider URLs with account details). Account-based providers (Premiumize, Xtream, a personal M3U)
are entered in the app's Settings instead.

## Keep the docs current

When behaviour, structure or test expectations change, update the matching document in the same
pull request: README.md (overview), ARCHITECTURE.md (code map), TESTING.md, SECURITY.md,
STATUS.md (current state and known issues) and CHANGELOG.md (Unreleased section).
