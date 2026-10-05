/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.airplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.service.AirPlayService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The host-facing entry point to the receiver.
 *
 * Deliberately thin: the real work lives in the vendored
 * [io.github.jqssun.airplay.service.AirPlayService], which owns the native handle, the renderers,
 * mDNS registration and the media session. This exists so a host never has to reach into the
 * `io.github.jqssun.airplay` namespace directly, and so the awkward parts — is there even a native
 * library for this ABI, does the service need an action, is it already running — have one answer
 * instead of several.
 *
 * Typical host wiring:
 * ```
 * AirPlayHost.surfaceActivity = AirPlayActivity::class.java
 * AirPlayOptions(deviceName = FleetConfig.name(ctx), requirePin = true).applyTo(ctx)
 * AirPlayEngine.start(ctx)
 * ```
 */
object AirPlayEngine {

    private const val TAG = "AirPlayEngine"

    /** The one ABI the native build produces — see `abiFilters` in the module's build script. */
    private const val ABI = "arm64-v8a"

    @Volatile private var supported: Boolean? = null

    /** Counts starts, so an in-flight [stop] can tell that one has overtaken it. */
    private val epoch = AtomicInteger(0)

    /**
     * [isSupported] once that has settled, an ABI test that touches no native code before then.
     *
     * For UI gates: [isSupported] dlopens the native stack (`libairplay_native.so` plus
     * `libc++_shared.so` and `liboboe.so`) and runs its static initialisers on the calling thread,
     * which a launcher's home screen cannot pay on behalf of users who never enable AirPlay. The
     * two can only disagree on an arm64 device whose APK is missing the library it was built with,
     * and [start] still checks the real thing, so that costs a dead settings row, not a crash.
     */
    fun isProbablySupported(): Boolean =
        supported ?: (Build.SUPPORTED_ABIS?.contains(ABI) == true)

    /**
     * Whether the native stack can run here, cached after the first call.
     *
     * The CMake build is filtered to `arm64-v8a` (every Portal is arm64), so on any other ABI there
     * simply is no `libairplay_native.so`. Resolving that lazily — rather than letting
     * `NativeBridge`'s `System.loadLibrary` run in a static initialiser at an arbitrary moment —
     * is what keeps a host's JVM unit tests and non-arm64 devices from dying with
     * `UnsatisfiedLinkError`. Check this before offering AirPlay in a settings UI.
     */
    fun isSupported(): Boolean {
        supported?.let { return it }
        val result = runCatching {
            System.loadLibrary("airplay_native")
            true
        }.getOrElse { e ->
            Log.i(TAG, "AirPlay unavailable on ${Build.SUPPORTED_ABIS.firstOrNull()}: ${e.message}")
            false
        }
        supported = result
        return result
    }

    /**
     * Start the receiver as a foreground service. No-op (returning false) when [isSupported] is
     * false, so a host can call this unconditionally.
     *
     * Configure first via [AirPlayOptions.applyTo] — the service reads its settings on start.
     *
     * The action is not optional. `AirPlayService.onStartCommand` ignores any intent whose action
     * is not [AirPlayService.ACTION_START_SERVER], so an actionless start would leave the receiver
     * down *and* — because this is a `startForegroundService` — let the system kill the process for
     * never calling `startForeground()` in time.
     */
    fun start(context: Context): Boolean {
        if (!isSupported()) return false
        val ctx = context.applicationContext
        val intent = Intent(ctx, AirPlayService::class.java)
            .setAction(AirPlayService.ACTION_START_SERVER)
        // Bumped before the intent, so any stop still waiting on a bind sees that it has been
        // overtaken however quickly this start follows it.
        epoch.incrementAndGet()
        return runCatching {
            ContextCompat.startForegroundService(ctx, intent)
            true
        }.onFailure { Log.w(TAG, "failed to start AirPlay service", it) }.getOrDefault(false)
    }

    /**
     * Stop the receiver and drop its mDNS advertisement. Safe to call when not running.
     *
     * Goes through the service instance rather than relying on `stopService` alone: a host that
     * binds the service (portal-receiver's own UI does) keeps it alive through `stopService`, so
     * without this the server would keep advertising after the user turned it off.
     */
    fun stop(context: Context) {
        val ctx = context.applicationContext
        // First and unconditionally: this half needs no instance, and the bind below can take
        // [BIND_TIMEOUT_MS] to conclude there is nothing to bind to. On a receiver that was already
        // stopped that is the whole call, and nothing should wait five seconds for it.
        runCatching { ctx.stopService(Intent(ctx, AirPlayService::class.java)) }
            .onFailure { Log.w(TAG, "stopService failed", it) }
        val issued = epoch.get()
        withService(ctx) { service, _ ->
            // Reaching the instance is asynchronous, and "off, then on again" is a fast pair of
            // taps: without this the stop's own binder callback would land after the restart and
            // shut down the server the user has just turned back on.
            if (epoch.get() != issued) {
                Log.i(TAG, "stop superseded by a later start")
                return@withService
            }
            runCatching { service?.stopServer() }
                .onFailure { Log.w(TAG, "stopServer failed", it) }
        }
    }

    /**
     * Apply new settings to a receiver that may already be running: persists them, then restarts
     * the server in place so they take effect. Suits a settings-registry "on applied" hook.
     *
     * The stop goes through the bound instance, because `startServer` early-returns while the state
     * is still RUNNING — without stopping first, a restart would silently keep the old
     * configuration (and the old advertised name) live.
     *
     * The *start* then goes back through [start], not through `service.startServer(name)`, and that
     * distinction is load-bearing. `startServer(name)` calls `startForegroundService` and then
     * `startForeground` synchronously, in that order — so by the time the system delivers that
     * start request, the `startForeground` answering it has already happened, and nothing answers
     * the request itself. Following `stopServer()` (which has just called `stopForeground` +
     * `stopSelf`) that is fatal: the five-second window elapses and the system kills the whole
     * process with `RemoteServiceException: Context.startForegroundService() did not then call
     * Service.startForeground()`, leaving the receiver down and unadvertised. Observed on a gen-2
     * Portal, reproducibly, on every settings change.
     *
     * [start] sends an `ACTION_START_SERVER` intent instead, and `onStartCommand` answers it by
     * calling `promoteToForeground()` before starting the server — the contract the platform
     * actually wants. The name is read from the prefs `applyTo` just wrote, so it is still the new
     * one.
     *
     * [onApplied] reports whether the restart reached the receiver. False covers nothing to restart
     * (`running` false, or no native stack) and a bind that never reached the instance — there the
     * *old* settings stay live, because `startServer` early-returns while the state is RUNNING and
     * the stop that would have cleared it never landed. Nothing here retries, and the new settings
     * are already in prefs by then, so a host that cares (a rename is a one-shot user action, with
     * no second event to notice the miss by) has to drive that itself. True means the restart was
     * issued, not that the server came back up: only its own state answers that. Runs on the main
     * thread unless the bind fails outright, which settles on the calling thread.
     */
    fun reconfigure(
        context: Context,
        options: AirPlayOptions,
        running: Boolean,
        onApplied: (Boolean) -> Unit = {},
    ) {
        options.applyTo(context)
        if (!running || !isSupported()) {
            onApplied(false)
            return
        }
        val ctx = context.applicationContext
        withService(ctx) { service, unanswered ->
            if (unanswered) {
                // Seconds late and blind: whatever the receiver is doing now, this restart is no
                // longer the answer to it — starting here could re-advertise one the user has since
                // turned off. Report the miss instead.
                Log.w(TAG, "reconfigure: never reached the service")
                onApplied(false)
                return@withService
            }
            runCatching { service?.stopServer() }
                .onFailure { Log.w(TAG, "reconfigure: stop failed", it) }
            // A null service here means nothing was running to reconfigure, so this start brings
            // the receiver up on the settings just written.
            onApplied(start(ctx))
        }
    }

    /**
     * Run [block] against the live service, then unbind.
     *
     * `bindService` never creates the service here (no `BIND_AUTO_CREATE`), so this reaches an
     * already-running receiver and reports null when there is nothing to talk to — which is the
     * distinction both callers above need. [block] runs on the main thread via the binder callback,
     * except on the rare synchronous failure path, where it runs on the caller's thread.
     *
     * `unanswered` means the bind was still outstanding at [BIND_TIMEOUT_MS] — the receiver is not
     * running, or the system was that late with the binder. Those are genuinely indistinguishable
     * from here (a passive bind to a service that is not running succeeds and then simply waits),
     * so what it reports is "we never reached an instance", not "there was none". A caller that
     * would act differently on the two has to get its certainty elsewhere.
     */
    private fun withService(ctx: Context, block: (AirPlayService?, Boolean) -> Unit) =
        OneShotConnection(ctx, block).bind()

    /**
     * How long to wait for a bind that the system may never answer.
     *
     * Not a latency the callers normally pay: a live service is dispatched as a main-looper message
     * from our own process, and the queue is ordered by timestamp, so a busy main thread delays both
     * and the connection still wins. This only bounds the case where nothing is there to answer —
     * where the block does no more than a stopService that no one is waiting on.
     */
    private const val BIND_TIMEOUT_MS = 5_000L

    /**
     * A passive bind that always settles exactly once, either with the connected service or with
     * null, and always unbinds.
     *
     * The timeout is what makes a passive bind usable. Without `BIND_AUTO_CREATE`, binding to a
     * service that is *not running* still succeeds — the system registers the connection and simply
     * holds it until someone else starts the service. So there is no callback to wait for and no
     * "not running" result: left alone the registration outlives the call, and the next [start]
     * hands the binder to it, running a `stopServer()` from an hours-old settings change against the
     * receiver the user has only just turned on. Unbinding on timeout is what discards it.
     */
    private class OneShotConnection(
        private val ctx: Context,
        private val block: (AirPlayService?, Boolean) -> Unit,
    ) : ServiceConnection, Runnable {

        private val settled = AtomicBoolean(false)
        private val handler = Handler(Looper.getMainLooper())

        fun bind() {
            val bound = runCatching {
                ctx.bindService(Intent(ctx, AirPlayService::class.java), this, 0)
            }.getOrDefault(false)
            if (bound) handler.postDelayed(this, BIND_TIMEOUT_MS) else settle(null, unanswered = false)
        }

        /** The timeout body. */
        override fun run() = settle(null, unanswered = true)

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) =
            settle((binder as? AirPlayService.LocalBinder)?.service, unanswered = false)

        override fun onServiceDisconnected(name: ComponentName?) = settle(null, unanswered = false)

        override fun onNullBinding(name: ComponentName?) = settle(null, unanswered = false)

        private fun settle(service: AirPlayService?, unanswered: Boolean) {
            if (!settled.compareAndSet(false, true)) return
            handler.removeCallbacks(this)
            try {
                block(service, unanswered)
            } finally {
                // Throws if the bind never took; that is the one path where there is nothing to release.
                runCatching { ctx.unbindService(this) }
            }
        }
    }
}
