# Bundled on-demand provider code

MovieHub includes adapted Python provider code from these projects. Original copyright
headers remain in the source. The app's original code retains its MIT notice; distributing
the combined application containing these GPL components requires complying with GPLv3,
including providing the corresponding source and retaining the notices. The MIT licence
alone does not describe all bundled components.

- Scrubs V2 5.1.42: https://github.com/jewbmx/repo , package
  `zips/plugin.video.scrubsv2/plugin.video.scrubsv2-5.1.42.zip`. The repository supplies GPLv3;
  the add-on's own metadata uses an informal licence string. See Scrubs-GPL-3.0.txt.
- Free99 2.0.1: https://github.com/nebulous42069/Omega , package
  `omega/zips/plugin.video.free99/plugin.video.free99-2.0.1.zip`. Its metadata identifies
  JewBMX and DIGGZ and describes a Scrubs fork, but leaves the licence field empty.
  No new permissive licence is inferred for its modifications; its Scrubs ancestry,
  inherited GPL notices and obligations are recorded here. The combined distribution
  is supplied with GPLv3 terms and corresponding source, retaining original MIT notices.
- ResolveURL 5.1.212: https://github.com/Gujal00/ResolveURL , directory
  `script.module.resolveurl/lib/resolveurl`. GPLv3-or-later, with embedded per-file notices.
  See ResolveURL-LICENSE.txt and the individual component headers.
- Chaquopy 17.1.0: https://github.com/chaquo/chaquopy (MIT); CPython 3.11 (PSF).
  requests 2.32.5 (Apache-2.0), six 1.17.0 (MIT), and their dependencies are packaged
  by the Android build. Their package metadata/licences must accompany distribution.

The fixed provider inventory `app/src/main/python/moviehub_providers.json` includes IDs,
original package membership and original-file SHA-256 hashes. It contains all 59 Scrubs
modules and nine Free99 modules outside `_duds`. Three filenames overlap between packages;
result links are deduplicated at runtime. `_duds` is an upstream archive of broken providers,
not an active provider list. The local Kodi-library module is retained but explicitly reports
`local_library`, since MovieHub does not have Kodi's local database. FilePursuit still needs
its own API key and returns no results without one; no key is shipped or inferred.

MovieHub modifications: package imports, non-executable title-alias parsing, headless API
adaptation, bounded HTTP client, per-search duplicate state, signed-APK-only host resolvers,
free-only/non-popup host selection, media-response probing, incremental result callbacks and
Android integration. Remote resolver code updates and Kodi settings-file writes are disabled.
No Kodi UI, accounts, add-on service or executable website scripts are started.

Availability and playback depend on each remote service. Bundling a provider is not evidence
that its domain is live, that every title matches, or that it can stream on a particular TV.

Additional hardening: website-fed decoders use a bounded literal/arithmetic interpreter;
Python eval/exec calls were removed. Direct urllib host requests use the bounded transport.

Maintained patches (25 October 2026): both Levidia adapters skip yearless search entries and tolerate missing optional session-cookie markup. Free99 also accepts an empty initial cookie jar and preserves equals signs in cookie values. Inventory SHA-256 values identify the original upstream files, not these adapted files. Original attribution remains in each module.

The Scrubs Levidia active path also delegates Wootly links to the bundled host resolver instead of its historical inline resolver and a second resolution.
