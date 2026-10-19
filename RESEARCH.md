# Sephile — Architecture Research

This document records the findings that determine Sephile's design. It exists
because the first several iterations crashed / were architecturally unable to
work, and the reason was subtle enough to be worth writing down.

## 1. The core constraint: an app process cannot ptrace another app

Every earlier version of Sephile did memory scanning **in-process** with JNI:
the Kotlin `MemoryRepository` called `ptrace(PTRACE_ATTACH, …)` from inside the
app's own process. This can never work, even on a rooted device.

Android enforces *two independent* blocks on cross-app `ptrace`:

1. **Yama LSM** — `kernel.yama.ptrace_scope` is set to `1` on Android, which
   restricts `PTRACE_ATTACH` to a process's own descendants only.
2. **SELinux** — apps run in the `untrusted_app` domain. That domain has **no**
   `ptrace`/`ptraceattach` permission against other app domains. Being "rooted"
   only means a `su` binary exists; **the app process is still uid 10xxx and
   still in `untrusted_app`.** It never becomes uid 0.

Net effect: `PTRACE_ATTACH` from the app returns `EPERM`, always. This is why
`nativeAttach()` silently failed and every scan returned nothing. No amount of
Kotlin/C++ cleanup changes this — it is a kernel/policy rule, not a bug.

## 2. The proven solution: run the engine in a su-spawned daemon

Every working memory scanner on the platform uses the same architecture:

- **GameGuardian** ships a long-lived "daemon" process started via `su`. Its
  forum's most common error is literally *"daemon is not running"* — the daemon
  **is** the product; the app is just a UI in front of it.
- **librootjava** (Chainfire) — "Run Java (and Kotlin) code as root!" — the
  exact `app_process` + `su` technique described below, with stdin/stdout IPC.
- **Shizuku** — identical pattern, started via `adb` (uid 2000) instead of `su`.

When a process is spawned through `su`, it runs with **uid 0** in the
privileged `su` / `magisk` SELinux domain. That domain **does** have `ptrace`
permission. The engine therefore must live in that separate process, and the UI
app talks to it over IPC.

## 3. The invocation: `app_process` + `su` (W^X compliant)

To run Kotlin/Java code as root:

```
su -c 'CLASSPATH=/data/app/…/base.apk app_process /system/bin com.sephile.app.root.Daemon'
```

- `app_process` (a **system** binary at `/system/bin/app_process`, 64-bit by
  default) boots an ART VM, loads classes from the APK listed in `CLASSPATH`,
  and invokes the target class's `static void main(String[])`.
- Under `su` this runs as uid 0 in the privileged domain → ptrace works.
- **Android 10+ W^X compliance:** Q blocks `exec()` of binaries in the app's
  writable data dir, but that targets *execve of a file we wrote*. `app_process`
  is a system binary; we only have the VM *load classes* from our APK — which is
  exactly Google's guidance ("load only binary code embedded within the APK").
  This is the blessed path and is how Shizuku/librootjava work on API 29+.

### Native library loading inside the daemon

In `app_process` context `System.loadLibrary("sephile_jni")` will **not** find
the app's extracted `.so` (that dir is not on `java.library.path`). The client
passes the absolute path and the daemon calls `System.load(path)`. The path is
`context.applicationInfo.nativeLibraryDir + "/libsephile_jni.so"`.

## 4. IPC: stdin/stdout pipes

The client (`su` → `app_process`) gives us the daemon's stdin and stdout as
pipes. librootjava uses `ObjectStream`s; a **newline-delimited text protocol**
is simpler and debuggable for a memory tool. Raw bytes are hex-encoded. The
daemon exits automatically when the app dies (its stdin hits EOF → `readLine()`
returns `null`).

Request/response is synchronous and serialized behind a lock. `LISTPROCS`,
`PEEK`, `CANDIDATE` return a count/hex payload on the same response.

## 5. Process enumeration

The daemon runs as root, so it can read `/proc`. The client no longer relies on
`ActivityManager.runningAppProcesses()` (which only returns visible processes).
The daemon parses `/proc/<pid>/cmdline` and `/proc/<pid>/status` for pid/uid/name
and returns the full list.

## 6. Startup crashes found in earlier versions (fixed)

These were real, fatal, and *not* in the code that kept being rewritten — which
is why the app "still crashed no matter what":

1. `res/xml/file_paths.xml` had root tag `<files-paths>` (invalid). The valid
   tag is `<paths>`. `FileProvider.parsePathStrategy()` throws
   `IllegalArgumentException("Unknown tag …")` during `installContentProviders`,
   i.e. **before the activity launches**.
2. `res/drawable/splash_background.xml` used `<bitmap android:src="@mipmap/ic_launcher">`
   while every `ic_launcher` is a `<shape>` drawable. `<bitmap>` requires a real
   raster; inflating it throws `Resources$NotFoundException: is not a Bitmap`
   when the splash window background is drawn.

## 7. Design consequences (this rewrite)

- App process **never** calls `ptrace`. All engine work happens in the daemon.
- `root/Daemon.kt` — `main()` entrypoint, speaks a text command protocol over
  stdin/stdout.
- `root/RootSession.kt` — client side: spawns the daemon via `su`, speaks the
  protocol, exposes a `StateFlow<SessionState>` (disconnected / starting /
  ready / error) so the UI can show real engine status.
- UI keeps its four-tab Compose structure but sources all data from `RootSession`.

## 8. Why the engine is pure-JVM (not the native `.so`)

An earlier draft had the daemon `System.load()` the native `libsephile_jni.so`.
That failed on-device with *"Native library missing …/lib/arm64/libsephile_jni.so"*,
and two further risks lay behind it:

1. **Extraction** — AGP 8 defaults `useLegacyPackaging=false` (minSdk ≥ 23), so
   `.so` files are *not* extracted to `nativeLibraryDir`; the dir is empty.
   (`android:extractNativeLibs=true` / `useLegacyPackaging=true` fixes this.)
2. **Linker namespace** — even the extracted copy may be unloadable: `app_process`
   runs in the default linker namespace, which can reject `dlopen` of paths under
   `/data/app` or `/data/data` with *"library is not accessible for the namespace"*.
   This is device/config dependent and untestable from here.

Both are avoidable. **Reading `/proc/<pid>/mem` as root needs only that file** —
its `open()` performs a `PTRACE_MODE_ATTACH` check that root passes (uid 0 +
`CAP_SYS_PTRACE` bypass Yama; the su/magisk SELinux domain allows ptrace). No
ptrace call, no native lib, no extraction, no dlopen, no W^X. So the daemon is
now **pure JVM**: it parses `/proc/<pid>/maps` for regions and reads/writes
`/proc/<pid>/mem` with `RandomAccessFile`. The `jni/` sources and C++ engine are
kept but not built or packaged (an optional future accelerator).
