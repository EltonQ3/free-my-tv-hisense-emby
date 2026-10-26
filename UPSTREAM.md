# Upstream provenance and publication scope

本改版面向中國大陸版、搭載 MediaTek MT9655 的 Hisense TV；目前僅測試海信 UXS 可用性。其他 MT9655 型號、其他晶片、其他地區版本及韌體未驗證，不能視為所有海信電視均已適配。


Upstream: https://github.com/simbasjungles/free-my-tv
Modification baseline: `4d739516379976e4cc256e4106f3680d6bb94da8`.

This is a genuine GitHub fork, with upstream commit ancestry retained. `main` remains the upstream snapshot; `hisense-emby` contains this unofficial Android / Hisense UXS / Emby edition. The original README is preserved at [docs/UPSTREAM_README.md](docs/UPSTREAM_README.md).

## Requirements reviewed on 2026-10-26

The upstream AGENTS.md / CLAUDE.md contributor guidance was reviewed for the first release and remains in this fork. The current README, LICENSE, root tree and release/build guidance were rechecked on 2026-10-26 (observed upstream main: `90908dc5bce178feb52e9868947cc039c64730fc`). No separate CONTRIBUTING or fork-distribution policy appeared. The README explicitly allows forks, changes and distribution under MIT. This checklist distinguishes upstream requirements from this fork's release choices.

- **MIT requirement:** retain the full original copyright and permission notice in source and distributions. [LICENSE](LICENSE) is unchanged.
- **Upstream contributor workflow:** run `tools/check.sh` before pushing, covering Android, translation catalogues, generic webOS / Tizen packaging and ES5 compatibility. We retain that script and add Hisense/Emby policy and playback tests.
- **Implementation guidance:** Java 8, minSdk 26 / target 33, SDK-only build; keep Original mode, crash watchdog, protected system services and translated text. Never commit `config.properties` or signing / pairing keys.
- **Credits:** preserve original contributor and third-party notices. Character art / trademarks remain under their own rights, not blanket MIT.
- **Fork choices:** distinct repository description, default branch and `hisense-emby-*` prerelease tags; explicit unofficial / Android-only labeling and device-test limits. No unsolicited upstream PR is part of publishing this fork.

## Android-specific release

Current upstream release instructions build generic Android APK, signed Samsung WGT, generic LG IPK and its own webOS feed. Those describe upstream's combined multi-platform releases and use its owner's private release keys. This fork cannot use those keys and does not claim a combined release.

Our prerelease distributes the sanitized Android APK with this fork's stable signer, complete corresponding modified Android source plus retained web sources, install tools, first-install skill, notices and SHA-256 checksums. The public defaults have no API credentials or personal presets. There is no `--generic` in the pinned baseline's Android build script; the isolated public config explicitly sets blank keys/city, 0/0 coordinates, HK region and runtime-only Emby setup, with weather disabled by default.

We do **not** publish unsupported WGT/IPK Emby builds, modify `freemytv.io`, or change upstream's Homebrew feed. Retained web builds are regression checks only. Website deployment is disabled for this fork and additionally guarded by repository identity; build/test Actions have read-only content permissions and no personal secrets.

The released APK is not rebuilt by CI and does not use CI's throwaway key. Anyone rebuilding with their own signer must retain that key for updates and cannot overwrite a different-signer installation.

## Public release checklist

1. Use the allowlisted sanitized snapshot, not the live configured working directory.
2. Run upstream checks and additional policies; state actual hardware testing limits.
3. Scan Git contents, APK, nested ZIPs, tools, skill and docs for personal data / secrets. Review every published image and strip metadata.
4. Verify package, versionCode, APK signature, archive integrity and all SHA-256 manifests.
5. Upload only the Public ZIP, standalone first-install skill, public APK, notices, LICENSE and public-only checksums; exclude private recovery archives.
6. Publish the fork branch and an Android-specific prerelease. Keep Original / watchdog and narrow stock-launcher recovery documented.

Device-specific changes must stay scoped: never uninstall/clear system packages, change global picture/HDR/CEC/calibration/network settings, bypass player authentication or read other apps' private credentials.
