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
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Choosing a capture size. Pure — the Camera2 plumbing around it can't be unit-tested on the
 * JVM, but which of the offered sizes we ask for can be, and it's the part with a decision in it.
 */
object CameraSizes {
  /**
   * The largest offered size whose longest edge fits within [maxEdge], or — when everything on
   * offer is bigger — the smallest size available.
   *
   * Bounded on purpose: this runs on Android 9 hardware with no `largeHeap`, often while the
   * photo frame is also holding full-screen bitmaps, and a snapshot for a dashboard tile does
   * not need the sensor's full resolution. Preferring the smallest when nothing fits keeps a
   * device that only offers large sizes working rather than failing outright.
   */
  fun choose(offered: List<Pair<Int, Int>>, maxEdge: Int): Pair<Int, Int>? {
    if (offered.isEmpty()) return null
    val area = { s: Pair<Int, Int> -> s.first.toLong() * s.second.toLong() }
    val longest = { s: Pair<Int, Int> -> maxOf(s.first, s.second) }
    return offered.filter { longest(it) <= maxEdge }.maxByOrNull(area)
        ?: offered.minByOrNull(area)
  }
}

/**
 * A single JPEG from the Portal's camera, on demand.
 *
 * Phase 1 of `docs/design/camera-streaming.md`: stills only, no video, no audio. It reuses the
 * approach [GestureCamera] already proves — standard Camera2, never Meta's signature-gated Smart
 * Camera SDK — but takes one frame and releases the camera immediately rather than holding it.
 *
 * **Consent lives on the device.** [ImmortalSettings.Settings.cameraEnabled] is off by default and
 * can only be turned on from the Portal itself; nothing arriving over MQTT can switch it on. With
 * it off, [snapshot] never opens the camera.
 *
 * Every capture shows a toast on the device. Someone in the room should never be photographed by
 * this without the Portal saying so — and for stills a toast is the honest signal. Continuous
 * streaming (phase 2) needs a persistent indicator instead, not this.
 *
 * [snapshot] blocks; call it off the main thread.
 */
class PortalCameraCapture(private val appContext: Context) {

  /** True when the user has enabled the camera AND the permission is actually granted. */
  fun available(): Boolean =
      ImmortalSettings.load(appContext).cameraEnabled &&
          ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) ==
              PackageManager.PERMISSION_GRANTED

  /**
   * Capture one JPEG, or null if unavailable, unsupported, or the capture didn't complete within
   * [timeoutMs]. Opens and releases the camera per call: the camera is shared on Portal (a call
   * takes it, and the photo frame's gesture detection wants it), so holding it open between
   * snapshots would starve them for no benefit.
   */
  fun snapshot(timeoutMs: Long = TIMEOUT_MS): ByteArray? {
    if (!available()) {
      Log.i(TAG, "camera off or ungranted — no snapshot")
      return null
    }
    var thread: HandlerThread? = null
    var device: CameraDevice? = null
    var session: CameraCaptureSession? = null
    var reader: ImageReader? = null
    return try {
      val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
      val cameraId = pickFrontCamera(manager) ?: return null
      val size = jpegSize(manager, cameraId) ?: return null
      val ht = HandlerThread("immortal-camera").apply { start() }
      thread = ht
      val handler = Handler(ht.looper)

      val latch = CountDownLatch(1)
      // Plain var, not @Volatile (illegal on a local anyway): the latch gives the
      // happens-before between the callback thread's write and our read after await().
      var bytes: ByteArray? = null
      val rdr = ImageReader.newInstance(size.first, size.second, ImageFormat.JPEG, 2)
      reader = rdr
      rdr.setOnImageAvailableListener(
          { r ->
            runCatching {
                  r.acquireLatestImage()?.use { img ->
                    val buf = img.planes[0].buffer
                    bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                  }
                }
                .onFailure { Log.w(TAG, "reading frame failed", it) }
            latch.countDown()
          },
          handler)

      val opened = CountDownLatch(1)
      var cam0: CameraDevice? = null
      openCamera(manager, cameraId, handler) {
        cam0 = it
        opened.countDown()
      }
      if (!opened.await(timeoutMs, TimeUnit.MILLISECONDS)) {
        Log.w(TAG, "camera didn't open within ${timeoutMs}ms")
        return null
      }
      val cam = cam0 ?: return null
      device = cam

      val configured = CountDownLatch(1)
      var sess0: CameraCaptureSession? = null
      @Suppress("DEPRECATION") // matches GestureCamera; the API 28 replacement isn't on minSdk 24
      cam.createCaptureSession(
          listOf(rdr.surface),
          object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
              sess0 = s
              configured.countDown()
            }

            override fun onConfigureFailed(s: CameraCaptureSession) = configured.countDown()
          },
          handler)
      if (!configured.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
      val sess = sess0 ?: return null
      session = sess

      val req =
          cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(rdr.surface)
          }
      sess.capture(req.build(), null, handler)
      if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
        Log.w(TAG, "capture didn't complete within ${timeoutMs}ms")
        return null
      }
      bytes?.also { indicate(it.size) }
    } catch (t: Throwable) {
      // The camera is shared and this runs alongside the always-on dream: a failure here must
      // never take a process down, it just means no snapshot this time.
      Log.w(TAG, "snapshot failed", t)
      null
    } finally {
      runCatching { session?.close() }
      runCatching { device?.close() }
      runCatching { reader?.close() }
      runCatching { thread?.quitSafely() }
    }
  }

  /** Tell the room. A capture the Portal doesn't announce is not one we should be taking. */
  private fun indicate(byteCount: Int) {
    Log.i(TAG, "snapshot captured ($byteCount bytes)")
    Handler(appContext.mainLooper).post {
      runCatching {
        Toast.makeText(appContext, "Immortal · camera snapshot taken", Toast.LENGTH_SHORT).show()
      }
    }
  }

  private fun pickFrontCamera(manager: CameraManager): String? =
      runCatching {
            manager.cameraIdList.firstOrNull {
              manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                  CameraCharacteristics.LENS_FACING_FRONT
            } ?: manager.cameraIdList.firstOrNull()
          }
          .getOrNull()

  private fun jpegSize(manager: CameraManager, cameraId: String): Pair<Int, Int>? =
      runCatching {
            val map =
                manager
                    .getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    as? StreamConfigurationMap
            val offered =
                map?.getOutputSizes(ImageFormat.JPEG)?.map { it.width to it.height }.orEmpty()
            CameraSizes.choose(offered, MAX_EDGE)
          }
          .getOrNull()

  @Suppress("MissingPermission") // checked in available()
  private fun openCamera(
      manager: CameraManager,
      cameraId: String,
      handler: Handler,
      onOpened: (CameraDevice?) -> Unit,
  ) {
    manager.openCamera(
        cameraId,
        object : CameraDevice.StateCallback() {
          override fun onOpened(camera: CameraDevice) = onOpened(camera)

          override fun onDisconnected(camera: CameraDevice) {
            runCatching { camera.close() }
            onOpened(null)
          }

          override fun onError(camera: CameraDevice, error: Int) {
            Log.w(TAG, "camera error $error")
            runCatching { camera.close() }
            onOpened(null)
          }
        },
        handler)
  }

  private companion object {
    const val TAG = "ImmortalCamera"
    /** Longest edge we'll ask for — a dashboard tile, not a photograph. */
    const val MAX_EDGE = 640
    const val TIMEOUT_MS = 5_000L
  }
}
