/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downscales a local video to a screen-sized (default 1200x800) muted H.264 clip, using the
 * Portal's hardware encoder via Media3 [Transformer]. This is the on-device half of the video
 * wall's storage story: [MediaCache] hands a fetched source here once, and every later loop
 * replays the small derivative from disk instead of re-streaming the near-original from the
 * server.
 *
 * [transcode] is **blocking** — it runs the export on a private [HandlerThread] (Transformer
 * requires a Looper on the thread it's created on and posts its callbacks there) and waits for
 * completion, so callers drive it from a background executor. Best-effort: any export error
 * returns false and the caller keeps streaming the original, so a codec quirk on one clip never
 * breaks playback.
 */
@OptIn(UnstableApi::class)
class VideoTranscoder(
    private val context: Context,
    private val maxWidth: Int = 1200,
    private val maxHeight: Int = 800,
    // 1.2 Mbps at <1MP is a good quality/size point and keeps more of a large album resident on a
    // storage-tight Portal (16 GB units). Bump it if fidelity matters more than coverage.
    private val bitrate: Int = 1_200_000,
) {
  /** Transcode [src] into [dst] (overwritten). Returns true only on a complete, non-empty export. */
  fun transcode(src: File, dst: File): Boolean {
    if (!src.exists() || src.length() == 0L) return false
    val thread = HandlerThread("wall-transcode")
    thread.start()
    val handler = Handler(thread.looper)
    val latch = CountDownLatch(1)
    val ok = AtomicBoolean(false)
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
            // Aspect-preserving fit inside the box (portrait clips end up <=maxWidth wide,
            // landscape <=maxHeight tall) — the screensaver's fit/fill handles the letterbox.
            val edited =
                EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(src)))
                    .setRemoveAudio(true) // a screensaver plays muted; drop the whole track
                    .setEffects(
                        Effects(
                            /* audioProcessors= */ emptyList(),
                            listOf(
                                Presentation.createForWidthAndHeight(
                                    maxWidth, maxHeight, Presentation.LAYOUT_SCALE_TO_FIT))))
                    .build()
            transformer.start(edited, dst.absolutePath)
          }
          .onFailure {
            Log.w(TAG, "transcode setup failed for ${src.name}", it)
            latch.countDown()
          }
    }
    latch.await()
    runCatching { thread.quitSafely() }
    return ok.get() && dst.exists() && dst.length() > 0L
  }

  private companion object {
    const val TAG = "ImmortalTranscode"
  }
}
