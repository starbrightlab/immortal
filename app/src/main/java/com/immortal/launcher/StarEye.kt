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
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
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
  // VGA — the Portal+ HAL threw fatal device errors (error 3) on a 1280x720
  // YUV stream; 640x480 matches the class of stream GestureCamera runs reliably.
  private const val WIDTH = 640
  private const val HEIGHT = 480
  private const val TIMEOUT_MS = 5000L
  private const val SKIP_FRAMES = 2

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
      // Legacy Camera1 first — this HAL is LEGACY-level (hwLevel 0) and the
      // Camera2 shim dies with fatal error 3 mid-capture. Each attempt gets its
      // own foreground kick: the camera service refuses background opens and a
      // single kick expires before a second attempt starts.
      kickForeground(context)
      snapshotCamera1()?.let { return it }
      kickForeground(context)
      return doSnapshot(context)
    } finally {
      busy.set(false)
    }
  }

  /**
   * Bring the app briefly to TOP via an invisible activity so the camera
   * service will grant the open (see [EyePermissionActivity]). Blocks ~900ms
   * to let the activity reach resumed state before the capture starts.
   */
  private fun kickForeground(context: Context) {
    val i =
        android.content.Intent(context, EyePermissionActivity::class.java)
            .putExtra(EyePermissionActivity.EXTRA_KICK, true)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(i) }
        .onFailure { Log.w(TAG, "foreground kick failed", it) }
    runCatching { Thread.sleep(900) }
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
    var framesSeen = 0
    // YUV stream + software JPEG encode. The Portal+ HAL (aloha, API 28) never
    // delivers JPEG-format frames — both one-shot still captures and repeating
    // JPEG requests fail with reason=0. YUV_420_888 preview is the path
    // GestureCamera already uses successfully on this hardware.
    val reader = pickYuvSize(manager, cameraId).let { size ->
      ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
    }

    reader.setOnImageAvailableListener({ r ->
      val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
      try {
        // Skip the first frames so auto-exposure settles — frame 0 is often black.
        if (framesSeen++ >= SKIP_FRAMES && result == null) {
          result = yuvToJpeg(image, 85)
          latch.countDown()
        }
      } catch (t: Throwable) {
        Log.w(TAG, "read image failed", t)
        latch.countDown()
      } finally {
        runCatching { image.close() }
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
                    // Deliberately identical to GestureCamera's request — the one
                    // shape known to run on this HAL.
                    val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                      addTarget(reader.surface)
                      set(CaptureRequest.CONTROL_AF_MODE,
                          CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    }
                    s.setRepeatingRequest(req.build(),
                        object : CameraCaptureSession.CaptureCallback() {
                          override fun onCaptureFailed(
                              sess: CameraCaptureSession,
                              request: CaptureRequest,
                              failure: android.hardware.camera2.CaptureFailure
                          ) {
                            Log.w(TAG, "capture failed: reason=${failure.reason}")
                          }
                        }, handler)
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
   * Pick a YUV output size close to [WIDTH]×[HEIGHT] — not every camera
   * supports arbitrary sizes, so fall back to the nearest available.
   */
  private fun pickYuvSize(manager: CameraManager, cameraId: String): Size {
    val cfg = manager.getCameraCharacteristics(cameraId)
        .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) as? StreamConfigurationMap
    val sizes = cfg?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray<Size>()
    if (sizes.isEmpty()) return Size(WIDTH, HEIGHT)
    // Nearest by pixel-count difference from target.
    val target = WIDTH.toLong() * HEIGHT.toLong()
    return sizes.minByOrNull { kotlin.math.abs(it.width.toLong() * it.height.toLong() - target) }!!
  }

  /** Camera characteristics dump for `GET /eye/info` — debugging aid. */
  fun info(context: Context): org.json.JSONObject {
    val out = org.json.JSONObject()
    runCatching {
      val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
      val cams = org.json.JSONArray()
      for (id in manager.cameraIdList) {
        val ch = manager.getCameraCharacteristics(id)
        val cfg = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) as? StreamConfigurationMap
        cams.put(
            org.json.JSONObject()
                .put("id", id)
                .put("facing", ch.get(CameraCharacteristics.LENS_FACING))
                .put("hwLevel", ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))
                .put("yuvSizes",
                    (cfg?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray())
                        .take(6).joinToString(",")))
      }
      out.put("camera2", cams)
    }.onFailure { out.put("camera2Error", it.toString()) }
    @Suppress("DEPRECATION")
    runCatching { out.put("camera1Count", android.hardware.Camera.getNumberOfCameras()) }
        .onFailure { out.put("camera1Error", it.toString()) }
    return out
  }

  /** Legacy android.hardware.Camera capture — fallback for broken Camera2 HALs. */
  @Suppress("DEPRECATION")
  private fun snapshotCamera1(): ByteArray? {
    var camId = 0
    val info = android.hardware.Camera.CameraInfo()
    for (i in 0 until android.hardware.Camera.getNumberOfCameras()) {
      android.hardware.Camera.getCameraInfo(i, info)
      if (info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT) {
        camId = i
        break
      }
    }
    val cam = runCatching { android.hardware.Camera.open(camId) }
        .onFailure { Log.w(TAG, "camera1 open failed", it) }
        .getOrNull() ?: return null
    try {
      val params = cam.parameters
      val target = WIDTH * HEIGHT
      val size = params.supportedPreviewSizes
          .minByOrNull { kotlin.math.abs(it.width * it.height - target) } ?: return null
      params.setPreviewSize(size.width, size.height)
      params.previewFormat = ImageFormat.NV21
      cam.parameters = params

      val latch = CountDownLatch(1)
      var jpeg: ByteArray? = null
      var frames = 0
      cam.setPreviewTexture(android.graphics.SurfaceTexture(0))
      cam.setPreviewCallback { data, c ->
        if (data != null && frames++ >= SKIP_FRAMES && jpeg == null) {
          runCatching {
            val s = c.parameters.previewSize
            val out = java.io.ByteArrayOutputStream()
            YuvImage(data, ImageFormat.NV21, s.width, s.height, null)
                .compressToJpeg(Rect(0, 0, s.width, s.height), 85, out)
            jpeg = out.toByteArray()
          }.onFailure { Log.w(TAG, "camera1 encode failed", it) }
          latch.countDown()
        }
      }
      cam.startPreview()
      val landed = runCatching { latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
      if (!landed) Log.w(TAG, "camera1 snapshot timed out after ${TIMEOUT_MS}ms")
      cam.setPreviewCallback(null)
      runCatching { cam.stopPreview() }
      return jpeg
    } catch (t: Throwable) {
      Log.w(TAG, "camera1 snapshot failed", t)
      return null
    } finally {
      runCatching { cam.release() }
    }
  }

  /** YUV_420_888 → NV21 → JPEG via [YuvImage]. Handles row/pixel strides. */
  private fun yuvToJpeg(image: Image, quality: Int): ByteArray {
    val w = image.width
    val h = image.height
    val nv21 = ByteArray(w * h * 3 / 2)

    // Y plane, row by row (rowStride may exceed width).
    val y = image.planes[0]
    var out = 0
    val yBuf = y.buffer
    for (row in 0 until h) {
      yBuf.position(row * y.rowStride)
      yBuf.get(nv21, out, w)
      out += w
    }

    // Interleave VU (NV21 order) from the U and V planes, honouring pixelStride.
    val u = image.planes[1]
    val v = image.planes[2]
    val uBuf = u.buffer
    val vBuf = v.buffer
    val cw = w / 2
    val ch = h / 2
    for (row in 0 until ch) {
      for (col in 0 until cw) {
        nv21[out++] = vBuf.get(row * v.rowStride + col * v.pixelStride)
        nv21[out++] = uBuf.get(row * u.rowStride + col * u.pixelStride)
      }
    }

    val jpeg = java.io.ByteArrayOutputStream()
    YuvImage(nv21, ImageFormat.NV21, w, h, null)
        .compressToJpeg(Rect(0, 0, w, h), quality, jpeg)
    return jpeg.toByteArray()
  }
}
