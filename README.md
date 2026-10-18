# Sephile

**Sephile** is a modern, beginner-friendly yet powerful Android runtime analysis and memory editing tool — think Game Guardian, but with a clean new UI, easier onboarding, and powerful runtime tools.

> ⚠️ **Educational / research tool.** Sephile requires **root** (`CAP_SYS_PTRACE`) to read and write the memory of other processes. Only build and run it on devices you own and have authorized. The authors are not responsible for misuse.

## What it does

- **Attach** to any running process by PID (root via `ptrace`)
- **Scan memory** for exact values: byte, short, 4-byte, 8-byte, float, double
- **Unknown initial value** and **fuzzy/changed** searches
- **Refine ("next")** scans to narrow down candidates
- **Edit** values live in a running process
- **Freeze** values so they stay constant
- **Process list** browser with refresh
- Modern **Jetpack Compose** UI (Material 3, dark by default)
- **GitHub Actions** CI builds debug/release APKs automatically

## Architecture

| Layer | Tech |
|-------|------|
| UI | Kotlin + Jetpack Compose (Material 3), single Activity |
| State | ViewModel + StateFlow |
| Logic | Clean-ish: Repository → Native bridge |
| Native | C++20 NDK (CMake), `ptrace` + `/proc/<pid>/maps` |
| Root | `su` shell wrapper, root detection |
| CI | GitHub Actions (JDK 17, Android SDK + NDK r27) |

```
app/src/main/java/com/sephile/app/
├── root/          # RootManager, SuShell, NativeBridge, ScanForegroundService
├── repository/    # ProcessRepository, MemoryRepository
├── viewmodel/     # ProcessListViewModel, ScannerViewModel
├── di/            # Module (DI)
├── model/         # ValueType, SearchType, ScanCandidate, ProcessInfo
└── ui/            # Compose screens + theme
app/src/main/jni/
├── ProcessMemory.{h,cpp}   # ptrace attach/read/write
├── MemoryScanner.{h,cpp}   # first/next scan, edit, freeze
└── native_bridge.cpp       # JNI entry points
```

## Build

### Prerequisites
- Android Studio / Gradle 8.6+
- Android SDK (API 34)
- NDK r27
- JDK 17

### Debug build
```bash
git clone https://github.com/vastobb/sephile.git
cd sephile
./gradlew :app:assembleDebug
```
The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### CI
Pushes and PRs run `.github/workflows/android-ci.yml` which builds the debug APK, runs lint and unit tests. Releases build a signed APK and attach it to the GitHub Release (requires `ANDROID_KEYSTORE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` secrets).

## Root setup

Sephile needs root on the device/emulator:
- Magisk / Zygisk / KernelSU / APatch
- Or an emulator with root (e.g. AVD with Google Play + root image)

On first launch, Sephile checks for `su` and `uid=0`. If root isn't available, attaching to other processes will fail.

## Disclaimer

Memory editing of other apps may violate their Terms of Service and can break games/apps. Online games with server-side validation or anti-cheat will detect and may ban accounts. Use only on offline, single-player apps you own, for learning and research.

## License

MIT — see `LICENSE`.
