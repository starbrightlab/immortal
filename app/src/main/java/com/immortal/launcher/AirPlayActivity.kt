/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.roundToInt
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.immortal.airplay.AIRPLAY_HANDOVER_GRACE_MS
import com.immortal.airplay.sessionFlow
import io.github.jqssun.airplay.service.AirPlayService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The screen an AirPlay session renders on: a full-screen [SurfaceView] and nothing else. Raised by
 * [AirPlayControl] only once mirroring or AirPlay video is live — audio-only stays headless.
 *
 * A session moves between two renderers inside the service: the MediaCodec mirror
 * (`setVideoSurface`) and the ExoPlayer HLS player (`setVideoPlaybackSurface`). YouTube starts as
 * mirroring and hands over to AirPlay video mid-stream. Each renderer gets its own [SurfaceView],
 * swapped at the handover ([showSurfaceFor]); the Activity survives the transition rather than
 * closing on the transient audio-only state it passes through ([observe]).
 */
class AirPlayActivity : ComponentActivity() {

  private var service: AirPlayService? = null
  private lateinit var root: FrameLayout
  /** The live surface view, owned by exactly one consumer. Replaced, never re-pointed. */
  private var videoView: SurfaceView? = null
  private var shownConsumer: Consumer? = null
  /** Pending "close because the session went audio-only", cancellable by any sign of video. */
  private var closeJob: Job? = null

  private enum class Consumer {
    MIRROR,
    VIDEO,
  }

  private val connection =
      object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
          service = (binder as? AirPlayService.LocalBinder)?.service ?: return
          observe()
          wantedConsumer()?.let { showSurfaceFor(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
          service = null
          shownConsumer = null
          finish()
        }
      }

  /**
   * A surface callback bound to one consumer for the life of its [SurfaceView]. The consumer is
   * captured here, not read from current state, so a late `surfaceDestroyed` for an already-replaced
   * view clears the consumer it belonged to rather than the live one.
   */
  private inner class Callbacks(private val consumer: Consumer) : SurfaceHolder.Callback {
    override fun surfaceCreated(holder: SurfaceHolder) = attach(consumer, holder.surface)

    // Re-hand on every geometry change: the mirror renderer caches its viewport from the surface at
    // creation and only refreshes it when handed a surface.
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) =
        attach(consumer, holder.surface)

    override fun surfaceDestroyed(holder: SurfaceHolder) = detach(consumer, holder.surface)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // A cast is a "look at me" moment: hold the screen for the whole session, and keep the
    // screensaver from relaunching its holding frame over the top when the dream stops.
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    DreamPolicy.suppressFrame = true
    hideSystemBars()

    root =
        FrameLayout(this).apply {
          setBackgroundColor(AndroidColor.BLACK)
          // The container's size isn't known in onCreate, and it changes if the panel rotates.
          addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) applyAspect()
          }
        }
    setContentView(root)

    bindService(Intent(this, AirPlayService::class.java), connection, BIND_AUTO_CREATE)
  }

  /**
   * Follow the session: re-route the surface between mirroring and AirPlay video, and close when the
   * sender has gone.
   *
   * The mirror→video handover passes briefly through audio-only (mirroring stops before the video
   * URL arrives), so audio-only only *arms* a close on a delay; any sign of video — which is what
   * [com.immortal.airplay.AirPlaySession.video] folds together — disarms it. Losing the connection
   * closes at once.
   */
  private fun observe() {
    val svc = service ?: return
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        svc.sessionFlow().collect { s ->
          when {
            // The sender is gone. Nothing to wait for.
            !s.connected -> closeNow()
            s.video -> {
              disarmClose()
              wantedConsumer()?.let { showSurfaceFor(it) }
              // Same consumer, new source geometry (a phone rotating mid-mirror).
              applyAspect()
            }
            s.audioOnly -> armClose()
            // Between the two: no video flag, no audio flag. Hold the last frame rather than
            // blanking — this gap is part of the handover.
            else -> Unit
          }
        }
      }
    }
    // Second collector: the aspect flows would overflow combine's five-flow limit above.
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        combine(svc.videoAspect, svc.videoPlaybackAspect) { _, _ -> Unit }.collect { applyAspect() }
      }
    }
  }

  /** Arm a delayed close, re-checked against live state when it fires so a late handover still wins. */
  private fun armClose() {
    if (closeJob != null) return
    closeJob =
        lifecycleScope.launch {
          delay(AIRPLAY_HANDOVER_GRACE_MS)
          // Through the same fold the collector uses, so "the handover is over" cannot mean one
          // thing here and another there. A sender that left during the grace counts too: the
          // collector that would have closed on it is suspended while we are not started.
          val s = service?.sessionFlow()?.first()
          closeJob = null
          if (s == null || !s.connected || (s.audioOnly && !s.video)) closeNow()
        }
  }

  private fun disarmClose() {
    closeJob?.cancel()
    closeJob = null
  }

  private fun closeNow() {
    disarmClose()
    if (!isFinishing) finish()
  }

  /**
   * Letterbox the surface to the source's aspect ratio. The renderer stretches the source to fill
   * whatever surface it is given, so the view is *scaled* (not resized) by the fit factor — the
   * black container then shows through as the bars. Both the container size and the ratio are
   * measured, never assumed, so it holds for any panel and any mirrored source shape.
   *
   * Scaling rather than resizing is deliberate: resizing changes the Surface geometry, which forces
   * the renderer's decoder to rebuild mid-stream and tears the picture. A view transform does not
   * touch the Surface.
   */
  private fun applyAspect() {
    val svc = service ?: return
    if (!this::root.isInitialized) return
    val view = videoView ?: return
    val aspect =
        when (shownConsumer) {
          Consumer.VIDEO -> svc.videoPlaybackAspect.value
          Consumer.MIRROR -> svc.videoAspect.value
          null -> return
        }
    val cw = root.width
    val ch = root.height
    if (cw <= 0 || ch <= 0 || aspect <= 0f || !aspect.isFinite()) return
    var w = cw
    var h = (cw / aspect).roundToInt()
    if (h > ch) {
      h = ch
      w = (ch * aspect).roundToInt()
    }
    if (w <= 0 || h <= 0) return
    val sx = w.toFloat() / cw
    val sy = h.toFloat() / ch
    if (view.scaleX == sx && view.scaleY == sy) return
    view.pivotX = cw / 2f
    view.pivotY = ch / 2f
    view.scaleX = sx
    view.scaleY = sy
  }

  /**
   * Give the live consumer its own fresh [SurfaceView], replacing the previous one. The two
   * consumers are different producers (MediaCodec+GL vs ExoPlayer); sharing one Surface between them
   * left the outgoing producer's frames composited under the incoming one after a handover, so each
   * gets its own Surface and BufferQueue with explicit teardown — as upstream's UI does.
   */
  private fun showSurfaceFor(want: Consumer) {
    if (want == shownConsumer) return
    // Removing the old view synchronously fires its surfaceDestroyed → detach for its own consumer.
    videoView?.let { root.removeView(it) }
    shownConsumer = want
    videoView =
        SurfaceView(this).also { v ->
          v.holder.addCallback(Callbacks(want))
          root.addView(
              v,
              FrameLayout.LayoutParams(
                  FrameLayout.LayoutParams.MATCH_PARENT,
                  FrameLayout.LayoutParams.MATCH_PARENT,
                  Gravity.CENTER))
        }
    applyAspect()
  }

  /** Pick the consumer the session currently calls for, or null while it is between the two. */
  private fun wantedConsumer(): Consumer? {
    val svc = service ?: return null
    return when {
      // AirPlay video wins when both look set: it is where a handover ends up, and mirroring has
      // stopped feeding frames by then.
      svc.videoPlaybackActive.value || svc.videoSessionPending() -> Consumer.VIDEO
      svc.mirroringActive.value -> Consumer.MIRROR
      else -> null
    }
  }

  private fun attach(consumer: Consumer, s: Surface) {
    val svc = service ?: return
    if (!s.isValid) return
    runCatching {
          when (consumer) {
            Consumer.MIRROR -> svc.setVideoSurface(s)
            Consumer.VIDEO -> svc.setVideoPlaybackSurface(s)
          }
        }
        .onFailure { Log.w(TAG, "failed to attach the surface to $consumer", it) }
  }

  /** Both clears are identity-guarded in the module, so a late one for a replaced view is a no-op. */
  private fun detach(consumer: Consumer, s: Surface) {
    val svc = service ?: return
    runCatching {
          when (consumer) {
            Consumer.MIRROR -> svc.clearVideoSurface(s)
            Consumer.VIDEO -> svc.clearVideoPlaybackSurface(s)
          }
        }
        .onFailure { Log.w(TAG, "failed to detach the surface from $consumer", it) }
  }

  /** Immersive full-screen; a cast should not sit under the status bar. */
  private fun hideSystemBars() {
    @Suppress("DEPRECATION")
    window.decorView.systemUiVisibility =
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
  }

  override fun onDestroy() {
    DreamPolicy.suppressFrame = false
    // Drops the view, which fires surfaceDestroyed -> detach for whichever consumer owns it.
    videoView?.let { root.removeView(it) }
    videoView = null
    shownConsumer = null
    runCatching { unbindService(connection) }
    service = null
    super.onDestroy()
  }

  private companion object {
    const val TAG = "ImmortalAirPlay"
  }
}
