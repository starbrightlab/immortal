/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Downscales a local video to a screen-sized muted H.264 clip, using the Portal's hardware
 * encoder via Media3 [Transformer]. This is the on-device half of the video wall's storage
 * story: [MediaCache] hands a fetched source here once, and every later loop replays the small
 * derivative from disk instead of re-streaming the near-original from the server.
 *
 * The output is sized to the source's own aspect ratio, fitted inside [maxWidth]x[maxHeight]
 * ([fitWithin]) — **never** to the box itself. `Presentation.createForWidthAndHeight` always
 * emits exactly the requested frame and pads any aspect mismatch with black bars; sizing the
 * request to the source's aspect means there is nothing to pad. Baking bars in would be wrong
 * twice over: a portrait clip's derivative would carry pillarbox bars the screensaver's fill
 * mode could no longer crop away (the derivative's aspect *is* the screen's), and every padded
 * pixel wastes bitrate. Small sources are never upscaled. If the source can't be probed, the
 * aspect-preserving `createForHeight` keeps the fallback bar-free too.
 *
 * [transcode] is **blocking** — it runs the export on a private [HandlerThread] (Transformer
 * requires a Looper thread and posts its callbacks there) and waits for completion, bounded by
 * [timeoutMinutes]: a hung codec is cancelled and reported as failure rather than stalling the
 * caller's worker forever. Best-effort: any error returns false and the caller keeps streaming
 * the original, so a codec quirk on one clip never breaks playback.
 */
@OptIn(UnstableApi::class)
class VideoTranscoder(
    private val context: Context,
    private val maxWidth: Int = 1200,
    private val maxHeight: Int = 800,
    // 1.2 Mbps at <1MP is a good quality/size point and keeps more of a large album resident on a
    // storage-tight Portal (16 GB units). Bump it if fidelity matters more than coverage.
    private val bitrate: Int = 1_200_000,
    private val timeoutMinutes: Long = 10,
) {
  /** Transcode [src] into [dst] (overwritten). Returns true only on a complete, non-empty export. */
  fun transcode(src: File, dst: File): Boolean {
    if (!src.exists() || src.length() == 0L) return false
    val presentation = presentationFor(src)
    val thread = HandlerThread("wall-transcode")
    thread.start()
    val handler = Handler(thread.looper)
    val latch = CountDownLatch(1)
    val ok = AtomicBoolean(false)
    val transformerRef = AtomicReference<Transformer?>()
    handler.post {
      runCatching {
            val transformer =
                Transformer.Builder(context)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(
                                VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                            .build())
                    .addListener(
                        object : Transformer.Listener {
                          override fun onCompleted(composition: Composition, result: ExportResult) {
                            ok.set(true)
                            latch.countDown()
                          }

                          override fun onError(
                              composition: Composition,
                              result: ExportResult,
                              exception: ExportException,
                          ) {
                            Log.w(TAG, "transcode failed for ${src.name}", exception)
                            ok.set(false)
                            latch.countDown()
                          }
                        })
                    .build()
            transformerRef.set(transformer)
            val edited =
                EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(src)))
                    .setRemoveAudio(true) // a screensaver plays muted; drop the whole track
                    .setEffects(Effects(/* audioProcessors= */ emptyList(), listOf(presentation)))
                    .build()
            transformer.start(edited, dst.absolutePath)
          }
          .onFailure {
            Log.w(TAG, "transcode setup failed for ${src.name}", it)
            latch.countDown()
          }
    }
    val finished = runCatching { latch.await(timeoutMinutes, TimeUnit.MINUTES) }.getOrDefault(false)
    if (!finished) {
      // Hung or over-long export: cancel on the transformer's own thread so the codec is
      // released, and report failure. Without this one bad clip would stall the prefetch
      // worker (and leak this thread) forever.
      Log.w(TAG, "transcode timed out after ${timeoutMinutes}m for ${src.name}; cancelling")
      handler.post { runCatching { transformerRef.get()?.cancel() } }
      ok.set(false)
    }
    runCatching { thread.quitSafely() }
    return ok.get() && dst.exists() && dst.length() > 0L
  }

  /**
   * The aspect-exact presentation for [src]: its displayed (rotation-applied) dimensions fitted
   * inside the target box. Probe failure falls back to `createForHeight`, which also preserves
   * the source aspect — never to a fixed box that would bake in bars.
   */
  private fun presentationFor(src: File): Presentation {
    val size = probeDisplaySize(src)?.let { (w, h) -> fitWithin(w, h, maxWidth, maxHeight) }
    return if (size != null)
        Presentation.createForWidthAndHeight(size.first, size.second, Presentation.LAYOUT_SCALE_TO_FIT)
    else Presentation.createForHeight(maxHeight)
  }

  /** The source's displayed (rotation-applied) width x height, or null if unprobeable. */
  private fun probeDisplaySize(src: File): Pair<Int, Int>? {
    val r = MediaMetadataRetriever()
    try {
      r.setDataSource(src.path)
      val w =
          r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
              ?: return null
      val h =
          r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
              ?: return null
      val rot =
          r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
      // A 90/270 rotation flag means the stored frame displays sideways: the Transformer
      // pipeline auto-rotates it upright, so the *displayed* dimensions are the swapped ones.
      return if (rot % 180 != 0) Pair(h, w) else Pair(w, h)
    } catch (e: Exception) {
      Log.w(TAG, "could not probe ${src.name}; falling back to height-fit", e)
      return null
    } finally {
      runCatching { r.release() }
    }
  }

  internal companion object {
    const val TAG = "ImmortalTranscode"

    /**
     * Fit [srcW]x[srcH] inside [maxW]x[maxH]: aspect-preserving, never upscaling, floored to
     * even dimensions (hardware H.264 encoders reject odd sizes). Null when any input is
     * non-positive or the result would collapse below 2px. Pure.
     */
    internal fun fitWithin(srcW: Int, srcH: Int, maxW: Int, maxH: Int): Pair<Int, Int>? {
      if (srcW <= 0 || srcH <= 0 || maxW <= 0 || maxH <= 0) return null
      val scale = minOf(maxW.toDouble() / srcW, maxH.toDouble() / srcH, 1.0)
      val w = ((srcW * scale).toInt() / 2) * 2
      val h = ((srcH * scale).toInt() / 2) * 2
      return if (w >= 2 && h >= 2) Pair(w, h) else null
    }
  }
}
