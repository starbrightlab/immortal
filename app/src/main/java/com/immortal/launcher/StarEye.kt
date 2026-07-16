/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-demand JPEG snapshot from the Portal's front camera, exposed over the Fleet
 * HTTP API at `GET /eye/snapshot`. Powers the "Star can see you" pipeline: the
 * NUC pulls one frame per Claude call when the transcript implies vision.
 *
 * Deliberately NOT a continuous stream:
 *  - Cheaper (no ongoing camera + encode load on the Portal)
 *  - Smaller privacy surface (frame captured only when explicitly requested)
 *  - Simpler to reason about — the camera is idle 99% of the time
 *
 * Coexistence with [GestureCamera]: both target the front camera. StarEye is
 * called from a fleet HTTP handler thread, so it just opens the camera when it
 * needs it — if GestureCamera is holding it, the open call will fail and we
 * return null. GestureCamera is opt-in; if the user runs both, snapshots may
 * miss. That's an acceptable tradeoff for kick-the-tires; a proper arbiter is
 * a followup.
 *
 * Thread model: the fleet HTTP thread calls [snapshot], which drives a
 * dedicated background HandlerThread for Camera2 callbacks and blocks on a
 * latch until the JPEG lands. Total wall time typically 300-800ms including
 * camera open + first-frame settle. Timeout is [TIMEOUT_MS].
 */
object StarEye {
  private const val TAG = "ImmortalStarEye"
  private const val WIDTH = 1280
  private const val HEIGHT = 720
  private const val TIMEOUT_MS = 5000L

  /** Concurrency guard — only one snapshot at a time. */
  private val busy = AtomicBoolean(false)

  /**
   * Capture a single JPEG from the front camera. Blocking; safe to call from a
   * network request thread. Returns null on any failure (no permission, camera
   * in use, timeout, hardware error) — the caller should surface 503 in that
   * case.
   */
  fun snapshot(context: Context): ByteArray? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
        PackageManager.PERMISSION_GRANTED) {
      Log.i(TAG, "no camera permission")
      return null
    }
    if (!busy.compareAndSet(false, true)) {
      Log.i(TAG, "snapshot already in flight")
      return null
    }
    try {
      return doSnapshot(context)
    } finally {
      busy.set(false)
    }
  }

  @Suppress("MissingPermission")
  private fun doSnapshot(context: Context): ByteArray? {
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val cameraId = pickCamera(manager) ?: run { Log.i(TAG, "no camera available"); return null }

    val thread = HandlerThread("immortal-eye").apply { start() }
    val handler = Handler(thread.looper)
    val latch = CountDownLatch(1)

    var result: ByteArray? = null
    var device: CameraDevice? = null
    var session: CameraCaptureSession? = null
    val reader = pickJpegSize(manager, cameraId).let { size ->
      ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
    }

    reader.setOnImageAvailableListener({ r ->
      val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
      try {
        val buf = image.planes[0].buffer
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        result = bytes
      } catch (t: Throwable) {
        Log.w(TAG, "read image failed", t)
      } finally {
        runCatching { image.close() }
        latch.countDown()
      }
    }, handler)

    val deviceCb = object : CameraDevice.StateCallback() {
      override fun onOpened(camera: CameraDevice) {
        device = camera
        @Suppress("DEPRECATION")
        runCatching {
          camera.createCaptureSession(listOf(reader.surface),
              object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                  session = s
                  runCatching {
                    val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                      addTarget(reader.surface)
                      set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                      set(CaptureRequest.CONTROL_AF_MODE,
                          CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                      set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                      set(CaptureRequest.JPEG_QUALITY, 85.toByte())
                    }
                    s.capture(req.build(), null, handler)
                  }.onFailure {
                    Log.w(TAG, "capture failed", it); latch.countDown()
                  }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                  Log.w(TAG, "session configure failed"); latch.countDown()
                }
              }, handler)
        }.onFailure { Log.w(TAG, "create session failed", it); latch.countDown() }
      }
      override fun onDisconnected(camera: CameraDevice) {
        Log.i(TAG, "camera disconnected"); latch.countDown()
      }
      override fun onError(camera: CameraDevice, error: Int) {
        Log.w(TAG, "camera error $error"); latch.countDown()
      }
    }

    runCatching { manager.openCamera(cameraId, deviceCb, handler) }.onFailure {
      Log.w(TAG, "openCamera failed", it); latch.countDown()
    }

    val landed = try { latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
    if (!landed) Log.w(TAG, "snapshot timed out after ${TIMEOUT_MS}ms")

    runCatching { session?.close() }
    runCatching { device?.close() }
    runCatching { reader.close() }
    runCatching { thread.quitSafely() }
    return result
  }

  private fun pickCamera(manager: CameraManager): String? =
      manager.cameraIdList.firstOrNull {
        manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT
      } ?: manager.cameraIdList.firstOrNull()

  /**
   * Pick a JPEG output size close to [WIDTH]×[HEIGHT] — not every camera
   * supports arbitrary sizes, so fall back to the nearest available.
   */
  private fun pickJpegSize(manager: CameraManager, cameraId: String): Size {
    val cfg = manager.getCameraCharacteristics(cameraId)
        .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) as? StreamConfigurationMap
    val sizes = cfg?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray<Size>()
    if (sizes.isEmpty()) return Size(WIDTH, HEIGHT)
    // Nearest by pixel-count difference from target.
    val target = WIDTH.toLong() * HEIGHT.toLong()
    return sizes.minByOrNull { kotlin.math.abs(it.width.toLong() * it.height.toLong() - target) }!!
  }
}
