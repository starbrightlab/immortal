# `:airplay` — vendored from android-airplay-server

This module is the portable AirPlay receiver: the native stack (UxPlay + OpenSSL + libplist +
FFmpeg via JNI), the protocol/renderer Kotlin, and the foreground service that drives them. It is
copied **verbatim** into Immortal; `:app` in this repo is only a thin UI shell around it.

Upstream: <https://github.com/jqssun/android-airplay-server> (GPL-3.0)

| | |
|---|---|
| Pinned at | `b9a590fc06dba94c3cc225ac68b2e997cfed00f4` ("feat: sanitize", 2026-07-21) |
| Previous pin | `9600431` ("size", 2026-06-27) — the original fork point |

## The point of this file

The first fork of this project renamed the Kotlin package (`io.github.jqssun.airplay` →
`com.portal.receiver`) across 21 files and patched the JNI symbol prefix in `native_bridge.cpp`.
That made every upstream sync a manual merge. **That rename has been reverted.** The vendored tree
is now byte-identical to upstream apart from the two local patches listed below, so
`sync-upstream.sh` produces a diff you can actually read.

**Do not rename the vendored package, and do not touch the JNI symbol prefix.** The native→Java
callbacks resolve by name off the passed object, so `Java_io_github_jqssun_airplay_bridge_NativeBridge_*`
in `native_bridge.cpp` must keep matching `bridge/NativeBridge.kt`'s package, or the library fails
to load with `UnsatisfiedLinkError`.

## The vendor line

Everything below `src/main/cpp/` and these Kotlin packages is upstream's, unmodified but for the
two `LOCAL PATCH` sites in the section after this one:

| Vendored verbatim | Why |
|---|---|
| `src/main/cpp/**` (incl. `patches/`, `cmake/`, 4 submodules) | all of it — this is where upstream's protocol work lands, bar one `LOCAL PATCH` in `android_raop_callbacks.c` (below) |
| `bridge/` | JNI surface; coupled to `native_bridge.cpp` |
| `renderer/` | MediaCodec video, Oboe audio, HLS video player |
| `audio/` | DACP control, DMAP parsing, track info |
| `discovery/` | mDNS/NSD registration |
| `download/` | video downloader |
| `service/AirPlayService.kt` | the `RaopCallbackHandler` implementation + native lifecycle |
| `Prefs.kt`, `Display.kt` | configuration keys and panel geometry the service reads |

**Derived from upstream, not copied** — `sync-upstream.sh` cannot rsync these and prints a reminder
to check them by hand:

| File | Relationship to upstream |
|---|---|
| `viewmodel/DebugModels.kt` | `AudioDebug` / `DebugInfo` lifted out of upstream's `MainViewModel.kt`, which we do not vendor. `AudioDebug`'s field list **is** the decode layout for the packed buffer `nativeServerAudioDebug` fills and `renderer/AudioRenderer` unpacks — a reordered or added field upstream moves the synced reader but not this record. Diff it on every sync. |
| `res/values/strings.xml` | the seven strings the module resolves through `R`; upstream keeps them in the app's full `strings.xml`. Check the `notification_*` / `download_*` entries and their format args. |

Not vendored — upstream's UI shell, which each host app replaces with its own:
`MainActivity`, `ui/**`, `viewmodel/MainViewModel.kt`, `AirPlayApp`, `BootReceiver`.

Ours, and the only files in this module with host opinions:
`com/immortal/airplay/AirPlayHost.kt` (and the facade alongside it). Not upstream's, so changes
here are not patches and cost nothing at sync time. One is worth knowing about:

- **`AirPlayEngine.reconfigure` restarts via `start()`, not `service.startServer(name)`.** The
  latter calls `startForegroundService` and then `startForeground` *in that order*, so the start
  request it files is answered before it is delivered and nothing answers it afterwards. Following
  `stopServer()` (which has just called `stopForeground` + `stopSelf`) the platform kills the whole
  process five seconds later — `RemoteServiceException: Context.startForegroundService() did not
  then call Service.startForeground()` — and the receiver is left down and unadvertised. Observed
  on a gen-2 Portal, reproducibly, on every settings change. `start()` sends `ACTION_START_SERVER`,
  which `onStartCommand` answers with `promoteToForeground()`. If a future sync changes
  `startServer`'s foreground handling, re-check this.

### Local patches against upstream

Two concerns, all sites marked `LOCAL PATCH`:

- **`service/AirPlayService.kt`, two call sites.** `launchMainActivity()` and
  `_buildMediaNotification()` hard-reference `MainActivity`. They now go through
  `AirPlayHost.surfaceActivity`, so each host supplies its own surface Activity — portal-receiver
  registers `MainActivity`, Immortal registers `AirPlayActivity`. A null value suppresses the
  launch, which is what an audio-only host wants.

- **`cpp/android_raop_callbacks.c`, `_get_env` and the block above it.** Upstream attaches RAOP's
  internal pthreads to the JVM and never detaches them, so every thread the library retires — one
  per sender disconnect and per server restart — exits attached, leaking its ART Thread, its
  `java.lang.Thread` peer (a live GC root) and its local-ref table, and aborting the process on
  runtimes that enforce the JNI contract. A `pthread` TLS destructor now detaches on thread exit,
  clearing any pending exception first (detach dispatches one to the uncaught handler, which kills
  the process). This is the one place `cpp/` is not upstream's; `sync-upstream.sh` rsyncs that tree
  with `--delete`, so re-apply it after every sync.

Everything else that differs from a plain upstream checkout is *packaging*, not source:

- Kotlin split across two Gradle modules (upstream is single-module).
- `res/values/strings.xml` here holds only the seven strings the module's own sources resolve
  through `R`; the rest stayed with the UI shell.
- Hilt/KSP dropped (upstream uses it for one `AndroidViewModel`; a stock `by viewModels()` replaces
  it). This keeps an annotation processor out of Immortal.
- Toolchain is the workspace standard (AGP 9.2.1 / Gradle 9.4.1 / Java 11), not upstream's
  AGP 8.9.2 / Kotlin 2.1.0 / Java 17 — the module has to compile inside Immortal.
- `OPENSSL_USE_STATIC_LIBS=ON` and the CMake build filtered to `arm64-v8a`. Both are cache flips in
  `build.gradle.kts`, not edits inside `third_party/`.

## Submodules

Upstream tracks its native dependencies as shallow submodules and applies its UxPlay fixes as
patch files at configure time (`applyUxplayPatches` in `build.gradle.kts`). **That task shells out
to `git -C third_party/UxPlay`, so UxPlay has to be a real checkout** — the earlier fork's
plain-vendored copy could not work with it. All four are therefore submodules here too, pinned to
upstream's exact commits:

| Submodule | Pin | Licence |
|---|---|---|
| `UxPlay` | `21eef8d` | GPL-3.0 overall; `lib/` mostly LGPL-2.1+, `lib/playfair/` and `lib/crypto.c` GPL-3.0, `lib/llhttp` and `lib/srp.c` MIT |
| `libplist` | `f41b1ea` | LGPL-2.1 |
| `openssl-cmake` | `4edd36a` | builds OpenSSL 3.4.4, Apache-2.0 |
| `ffmpeg` | `38b8833` | LGPL-2.1+; built minimal and static (`--disable-all --enable-decoder=alac`) |

## Deliberately not taken from upstream

Upstream's app-shell features — Immortal and portal-receiver each implement their own UI, so these
are skipped on purpose rather than missed: `theme`, `feat: player controls`, `feat: run in
background`, `feat: auto restart`, `keep screen on`, `advertise`, `music`, `download` UI,
`feat: sanitize` (a build variant).

Note the earlier plan to skip `feat: ffmpeg alac decoder` was **reversed**. Upstream's FFmpeg is a
minimal static build (ALAC decoder only, no network, no asm), and commit `f4fa5c5` moved the whole
audio path into native C++ and deleted the Apple ALAC decoder along with `nativeAlacInit/Decode/
Destroy`. Software ALAC at HEAD is FFmpeg-only, and Portal has no hardware `audio/alac` decoder, so
that is the live path. Skipping it would have meant forking the highest-churn native files.

## Syncing

```bash
airplay/sync-upstream.sh              # sync to upstream main
airplay/sync-upstream.sh <ref>        # or a specific tag/commit
```

It fetches, copies the tracked paths over the vendored tree, and prints the diff. Review it, re-apply
the two `LOCAL PATCH` sites if they were clobbered, update the pins above, then build and re-verify
on hardware — **both** a gen-1 and a gen-2 Portal.

Both have now been exercised at this pin (through the standalone `portal-receiver` shell): gen-2
Portal 10" (`omni`, Android 10 / API 29) and gen-1 Portal+ (`aloha`, Android 9 / API 28). Audio
(Spotify), YouTube mirroring, and the mirroring → AirPlay-video handover all work on both; gen-1 was
the better of the two, holding the video session across a YouTube ad where gen-2 needed a reconnect.

If that diff ever comes back large and noisy, the vendor line above is drawn in the wrong place.
Fix the line rather than living with the merge.

## Open upstream work worth revisiting

Not taken yet, listed so a future sync knows what to look for:

- **PR #22** — robust video session-end detection (watchdog timers for senders that abandon a
  session without signalling). Directly useful: it is how a host knows to dismiss its surface.
- **PR #21** — return to the previous app when casting ends.
- **PR #15** — AirPlay video live.
- **Issue #36** — control the initial volume. Matters on a speaker-first device like Portal, where
  a session currently starts at maximum.
- **PR #20** (Android TV D-pad focus) and **Issue #26** (manual dark-mode toggle) are app-shell
  only and do not apply to either host.
