# SamSU

A KernelSU tracefs injector with Root My Galaxy Payloads (RMG) support, forked from [M3Q Root for Galaxy S26 Ultra](https://github.com/monovibe/s26u-m3q-temp-root) (monovibe) and retargeted to the Galaxy S25 (`SM-S931B`, kernel `6.6.127-android15-8`).

> **Exact-target kernel exploit.** Each payload only matches one firmware build. A failed kernel attempt can panic or reboot the device. One root run is allowed per boot; a reboot clears root and the attempt counter.

## Upstream M3Q Root changes

M3Q Root was a single-device launcher for the Korean Galaxy S26 Ultra (`SM-S948N`, kernel 6.12.30). SamSU keeps the fail-closed security architecture and changes everything around it:

- **Retargeted payload**: the CVE-2026-43499 route was re-derived for the Galaxy S25 (`pa1q-S931BXXUCZZHL`, kernel `6.6.127-android15-8-paa4b906`): new text offsets, self-validating derivations (boot-id `.data` slot, `nfnetlink_log` name check), and a bounded stack-writer retry budget.
- **RMG payload compatibility**: the app matches this device against the [Root My Galaxy Payloads](https://github.com/BuSung-dev/Root-My-Galaxy-Payloads) `targets-v3.json` registry (model + kernel version), downloads the matching exploit binary, and falls back to the bundled payload when nothing matches or the device is offline.
- **KernelSU 3.2.5 gate**: the installed KernelSU Manager version is checked against the bundled `ksud` (3.2.5) and maintenance actions are locked on mismatch.
  
## Payload system and RMG compatibility

SamSU uses the Root My Galaxy Payloads registry format (`support/targets-v3.json`):

1. On startup, refresh, and before a root run, the app fetches the registry once per session.
2. `Build.MODEL` must appear in a profile's `models[]` **and** the running kernel must start with one of its `kernelVersions[]`.
3. On a match, the profile's exploit binary is downloaded once (size-verified against the registry) into app storage and used for every run.
4. With no match — or offline — the bundled `pa1q-S931BXXUCZZHL` payload is used, so the app stays fully offline-capable.

The tracefs versus physical-P0 slide route is selected at runtime per run (Shizuku tracefs fast path first, physical fallback), so one binary per device profile covers both. The active payload id is shown in the status card.

## Supported devices

| Source | Devices |
| --- | --- |
| Bundled payload | Galaxy S25 `SM-S931B` on firmware `BP4A.251205.006` / `S931BXXUCZZHL`, kernel `6.6.127-android15-8-paa4b906` |
| Downloaded (RMG registry) | Whatever [Root My Galaxy Payloads](https://github.com/BuSung-dev/Root-My-Galaxy-Payloads) currently publishes, matched by model + kernel version |

KernelSU Manager **3.2.5** must be installed (newer managers are flagged in the status card).

## Install and use

1. Install the APK and KernelSU Manager **3.2.5** (`me.weishu.kernelsu`).
2. Start Shizuku through wireless ADB and approve this app once — the tracefs fast path makes runs far more reliable.
3. Reboot once before the first run, then wait until kernel uptime reaches 180 seconds.
4. Hold root button. Do not retry an uncertain kernel run in the same boot.
5. If modules or LSPosed are inactive, hold **Hold to reload KernelSU**, then **Hold to soft reboot**.
<p align="center" width="50%">
<video src="https://github.com/user-attachments/assets/131949a5-239e-42be-8542-176fbfcda6a9" width="20%" controls></video>
</p>
## Root process

The app uses a fail-closed, per-boot flow:

1. model, fingerprint, and kernel gate (bundled target) or RMG registry match (downloaded payload);
2. 180-second boot-settle gate and one fresh kernel-write claim per boot;
3. Shizuku tracefs KASLR route when authorized, otherwise the physical-P0 oracle;
4. bounded carrier validation and kernel R/W setup;
5. UID-restricted bootstrap helper;
6. hash-verified KernelSU late-load handoff.

See [Root process](docs/ROOT_PROCESS.md) and [Technical reference](docs/REFERENCE.md) for the implementation boundaries.

## Build

Requirements: JDK 17, Android SDK 37, and Android NDK.

Windows:





```powershell
# payload (from a Root-My-Galaxy-Payloads checkout containing targets/pa1q-S931BXXUCZZHL)
port\build_windows.cmd
# then stage it for the app
copy repo\Root-My-Galaxy-Payloads-main\build\pa1q-S931BXXUCZZHL\cve-2026-43499-app.release.so `
     m3q-app\exploit\build\m3q-BP4A.251205.006\bin\preload.app.so

# app
cd m3q-app\android
.\gradlew.bat --no-daemon :app:assembleRelease
```

APK output: `m3q-app/android/app/build/outputs/apk/release/app-release.apk`.

## Repository layout

```text
android/                         Android app and build scripts
exploit/src/                     shared native components (M3Q base)
exploit/src/targets/m3q-.../     exact AZG3 target (upstream, unused by SamSU payloads)
exploit/vendor/root-my-galaxy/   vendored Root My Galaxy source and provenance
docs/                            runtime and technical documentation
```

The SamSU payload itself is built from the Root My Galaxy Payloads tree (targets/pa1q-S931BXXUCZZHL) via port/build_windows.cmd in that checkout.

Generated APKs, JNI outputs, device logs, screenshots, local paths, and research scratch files are intentionally excluded from Git history.

## Attribution and license

SamSU is a fork of [M3Q Root](https://github.com/monovibe/s26u-m3q-temp-root) by monovibe, which derives from [Root My Galaxy Payloads](https://github.com/BuSung-dev/Root-My-Galaxy-Payloads) by BuSung-dev. The Galaxy S25 target was ported and hardware-validated by mitschud. Shizuku integration uses [Shizuku API](https://github.com/RikkaApps/Shizuku-API).

See [LICENSE](LICENSE) and [NOTICE](android/NOTICE).
