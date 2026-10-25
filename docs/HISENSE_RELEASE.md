# Hisense TV CN MT9655 / Emby 1.8.1

本改版面向中國大陸版、搭載 MediaTek MT9655 的 Hisense TV；目前僅測試海信 UXS 可用性。其他 MT9655 型號、其他晶片、其他地區版本及韌體未驗證，不能視為所有海信電視均已適配。


Date: 2026-10-25. Tag: `hisense-emby-v1.8.1`. Branch: `hisense-emby`.
Android-specific prerelease. Package `io.freemytv`, versionCode `34`, versionName `1.8.1-uxs.20261025-public`.

## Scope

The first public release of this fork combines the developed Hisense single-Home / optional stock-launcher gate, bidirectional debugging controls, per-user Emby recommendations and resume, optional normal-UI player account switching, native HDMI handling, six settings themes, adaptive app cards, stock indices, synthesized navigation sounds and configurable one-minute home screensaver. See [README](../README.md) and [feature gallery](FEATURES.md).

The published APK is the already verified sanitized binary. Publication updates documentation, attribution and packaging; it does not change the APK. The Public ZIP includes corresponding modified sources, installer tools and licenses; the first-install skill is a separate asset. The private recovery bundle, official Emby APK, private signing / pairing keys and runtime credentials are excluded.

## Completed local checks

Run against the prepared publication source with JDK 17, Android SDK / build-tools 34:

- `tools/check.sh`: Android APK build; translation parity (251 keys); generic LG and Samsung regression packages; ES5 syntax and API compatibility. All passed. The generic web packages are not distributed as Emby editions.
- Debug-switch policy: 118 assertions.
- Stock-launcher transaction policy: 14 assertions.
- Single-Home routing policy: 20 assertions.
- Emby account visible-UI policy: 59 assertions.
- Playback resolution: 59 assertions; account isolation: 17; exact player session / tick precision: 33; local mock HTTP: 8; local mock handoff: 6. All passed, no real media played by these tests.
- APK package / version, established fork signer, source and asset identity, archive CRC, internal / external SHA-256 manifests and installer verification.
- Recursive privacy review of Git source and nested release payloads, plus visual inspection and metadata removal for published illustrations. User explicitly allowed library titles, viewing progress and avatars in documentation; account names, server addresses, keys and pairing information are excluded.

GitHub build/test Actions run with `contents: read`, no private secrets and a throwaway build key. Published APKs use the stable fork signer, not that key. Website publication is disabled.

## Hardware observations and limits

Reference device: mainland Hisense UXS / MT9655 / Android 14. Hardware experience includes factory M-mode development checks and U-mode daily use. On 2026-10-25, the owner confirmed that the TV had been used in U mode throughout the day. Functionally equivalent personal builds were used; this does not establish a clean-TV first installation of the public APK.

Reference-device observations cover original-color avatars, home profile selection, two-way official Emby 3.5.35 account switching through normal UI, and one user-confirmed resume from saved progress. Passwords / PINs remain manual. All accounts, formats, protected streams and combined account-switch-plus-playback paths were not exhaustively tested.

Single Home from apps / stock launcher / HDMI, stock-launcher opt-in gating, debug off/on and its prompt, one-minute screensaver and settings retention have reference-device observations. The Home routing and input-source handoff do not claim a global HDR / CEC setting change.

The six-theme selector, themed settings panels, notification summary, app-card resting / focused states, and Settings / Home navigation have reference-device observations. The gallery contains 1.8.1 screenshots captured through normal UI navigation; screenshots do not constitute additional playback tests.

Other models / firmware, full factory-reset installation, every standby / AC boot scenario and a clean Public installation remain unverified. U-mode evidence is the owner-confirmed daily use on this UXS, not a claim of exhaustive coverage of every feature or playback format. The app is not TV firmware, DRM remediation or a full-device backup. Overwriting an existing installation retains its private data; sanitization describes the distributed files.

## Binary identity

- Public APK SHA-256: `f65617673ca8ee9099b9880717ce483657e1c70056044f6ca90a8f6590a80f24`
- Fork signing certificate SHA-256: `e9271d5ef1ea9a85bcb9b1d25a6029d74d0c68679bf8d3da7a6c5d66a90ea03e`
- Upstream baseline: `4d739516379976e4cc256e4106f3680d6bb94da8`

This signer is not upstream's signer. A self-build needs its own retained key and cannot update a different-signer installation. Verify all download assets with the public-only SHA256SUMS file supplied with this release.
