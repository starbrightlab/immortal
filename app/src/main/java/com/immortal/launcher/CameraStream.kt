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
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat

/** Encoder sizing decisions, kept pure so the numbers are reviewable without a device. */
object StreamProfile {
  const val MIME = "video/avc"

  /**
   * `MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline`, written as a literal
   * because the constant only exists from API 27 and minSdk here is 24. Constrained Baseline is
   * not a preference: browser WebRTC refuses anything higher, so a stream encoded as High plays
   * in VLC and fails in the Home Assistant card — the exact trap the design note records.
   */
  const val PROFILE_CONSTRAINED_BASELINE = 0x10000

  /** Seconds between keyframes. A client can only start decoding at one, so short beats tidy. */
  const val KEYFRAME_INTERVAL_S = 1

  /**
   * Bits per second for a given frame size and rate, at roughly 0.1 bits per pixel per frame —
   * a sane quality/size trade for a fixed indoor scene, and gentle on a fanless Portal that may
   * be encoding for hours. Clamped so a tiny frame still gets a usable floor and a large one
   * can't run away with the radio.
   */
  fun bitrateFor(width: Int, height: Int, fps: Int): Int {
    val raw = (width.toLong() * height.toLong() * fps.toLong() / 10L).toInt()
    return raw.coerceIn(200_000, 4_000_000)
  }
}

/**
 * Camera2 → H.264, the encoder half of live streaming (phase 2 of
 * `docs/design/camera-streaming.md`). Frames go straight from the camera into the encoder's input
 * [Surface] — no `ImageReader`, no copy through the heap, which is what makes a sustained stream
 * viable on this hardware at all.
 *
 * Emits NAL units through [onNals] as they come out of the codec; [RtpH264] turns those into RTP,
 * and the RTSP server puts them on the wire. Kept separate from that so the encoder can be
 * verified on a device before any of the network side exists.
 *
 * The camera is shared: a Portal call takes it, and [GestureCamera] and [PortalCameraCapture]
 * both want it too. Only one of those can hold it at a time, so streaming is mutually exclusive
 * with them by construction — whoever asks second fails, and fails softly.
 */
class CameraStream(
    private val appContext: Context,
    private val onNals: (nals: List<ByteArray>, presentationTimeUs: Long, keyframe: Boolean) -> Unit,
) {
  @Volatile private var running = false
  private var thread: HandlerThread? = null
  private var device: CameraDevice? = null
  private var session: CameraCaptureSession? = null
  private var codec: MediaCodec? = null
  private var inputSurface: Surface? = null
  private var drain: Thread? = null

  /** SPS and PPS from the codec's config buffer, needed for the SDP. Null until encoding starts. */
  @Volatile var sps: ByteArray? = null
    private set

  @Volatile var pps: ByteArray? = null
    private set

  /** Frame size actually in use, once started — the SDP and any UI want to report it. */
  @Volatile var width = 0
    private set

  @Volatile var height = 0
    private set

  fun isRunning(): Boolean = running

  /**
   * Open the camera and start encoding. Returns false when it can't — no permission, no camera,
   * no encoder, or the camera is already held by something else. Never throws.
   */
  fun start(fps: Int = FPS): Boolean {
    if (running) return true
    if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) !=
        PackageManager.PERMISSION_GRANTED) {
      Log.i(TAG, "no camera permission — not streaming")
      return false
    }
    return runCatching {
          val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
          val cameraId = pickCamera(manager) ?: return false
          val size = streamSize(manager, cameraId) ?: return false
          width = size.first
          height = size.second

          val ht = HandlerThread("immortal-stream").apply { start() }
          thread = ht
          val handler = Handler(ht.looper)

          val enc = MediaCodec.createEncoderByType(StreamProfile.MIME)
          codec = enc
          val format =
              MediaFormat.createVideoFormat(StreamProfile.MIME, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(
                    MediaFormat.KEY_BIT_RATE, StreamProfile.bitrateFor(width, height, fps))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(
                    MediaFormat.KEY_I_FRAME_INTERVAL, StreamProfile.KEYFRAME_INTERVAL_S)
                // Ask for Constrained Baseline. Not every encoder honours the request; the SDP
                // reports what the SPS actually says, so a device that ignores this is visible
                // rather than mysterious.
                setInteger(MediaFormat.KEY_PROFILE, StreamProfile.PROFILE_CONSTRAINED_BASELINE)
              }
          enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
          val surface = enc.createInputSurface()
          inputSurface = surface
          enc.start()

          running = true
          drain = Thread(::drainLoop, "immortal-stream-drain").apply { isDaemon = true; start() }
          openCamera(manager, cameraId, surface, handler)
          Log.i(TAG, "streaming ${width}x$height @${fps}fps")
          true
        }
        .onFailure {
          Log.w(TAG, "stream start failed", it)
          stop()
        }
        .getOrDefault(false)
  }

  /** Stop and release everything. Idempotent, and safe to call from any thread. */
  fun stop() {
    running = false
    runCatching { session?.close() }
    runCatching { device?.close() }
    runCatching { codec?.stop() }
    runCatching { codec?.release() }
    runCatching { inputSurface?.release() }
    runCatching { thread?.quitSafely() }
    drain?.interrupt()
    session = null
    device = null
    codec = null
    inputSurface = null
    thread = null
    drain = null
    Log.i(TAG, "streaming stopped")
  }

  /**
   * Pull encoded buffers out of the codec and hand on their NALs. The first buffer is flagged
   * codec-config and carries SPS+PPS glued together — kept for the SDP rather than sent, since
   * RTP announces parameter sets out of band.
   */
  private fun drainLoop() {
    val info = MediaCodec.BufferInfo()
    while (running) {
      val enc = codec ?: break
      val index = runCatching { enc.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US) }.getOrElse { break }
      if (index < 0) continue
      runCatching {
            val buf = enc.getOutputBuffer(index)
            if (buf != null && info.size > 0) {
              buf.position(info.offset)
              buf.limit(info.offset + info.size)
              val bytes = ByteArray(info.size).also { buf.get(it) }
              val nals = RtpH264.splitAnnexB(bytes)
              if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                nals.forEach {
                  when (RtpH264.nalType(it)) {
                    NAL_SPS -> sps = it
                    NAL_PPS -> pps = it
                  }
                }
                Log.i(TAG, "codec config: sps=${sps?.size} pps=${pps?.size}")
              } else if (nals.isNotEmpty()) {
                val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                onNals(nals, info.presentationTimeUs, keyframe)
              }
            }
          }
          .onFailure { Log.w(TAG, "drain failed", it) }
      runCatching { enc.releaseOutputBuffer(index, false) }
    }
  }

  private fun pickCamera(manager: CameraManager): String? =
      runCatching {
            manager.cameraIdList.firstOrNull {
              manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                  CameraCharacteristics.LENS_FACING_FRONT
            } ?: manager.cameraIdList.firstOrNull()
          }
          .getOrNull()

  /**
   * Sizes the camera can feed an encoder surface. Uses the same bounded choice as stills — the
   * per-model field-of-view quirks in the design note apply here too, and a bigger frame costs
   * bitrate and heat for a scene that doesn't need it.
   */
  private fun streamSize(manager: CameraManager, cameraId: String): Pair<Int, Int>? =
      runCatching {
            val map =
                manager
                    .getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    as? StreamConfigurationMap
            val offered =
                map?.getOutputSizes(MediaCodec::class.java)?.map { it.width to it.height }.orEmpty()
            CameraSizes.choose(offered, MAX_EDGE)
          }
          .getOrNull()

  @Suppress("MissingPermission") // checked in start()
  private fun openCamera(
      manager: CameraManager,
      cameraId: String,
      surface: Surface,
      handler: Handler,
  ) {
    manager.openCamera(
        cameraId,
        object : CameraDevice.StateCallback() {
          override fun onOpened(camera: CameraDevice) {
            device = camera
            runCatching { startSession(camera, surface, handler) }
                .onFailure {
                  Log.w(TAG, "session failed", it)
                  stop()
                }
          }

          override fun onDisconnected(camera: CameraDevice) = stop()

          override fun onError(camera: CameraDevice, error: Int) {
            Log.w(TAG, "camera error $error")
            stop()
          }
        },
        handler)
  }

  @Suppress("DEPRECATION") // matches GestureCamera; the API 28 replacement isn't on minSdk 24
  private fun startSession(camera: CameraDevice, surface: Surface, handler: Handler) {
    camera.createCaptureSession(
        listOf(surface),
        object : CameraCaptureSession.StateCallback() {
          override fun onConfigured(s: CameraCaptureSession) {
            session = s
            runCatching {
                  val req =
                      camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(surface)
                      }
                  s.setRepeatingRequest(req.build(), null, handler)
                }
                .onFailure {
                  Log.w(TAG, "repeating request failed", it)
                  stop()
                }
          }

          override fun onConfigureFailed(s: CameraCaptureSession) {
            Log.w(TAG, "capture session config failed")
            stop()
          }
        },
        handler)
  }

  private companion object {
    const val TAG = "ImmortalStream"
    const val FPS = 15
    /** Same bound as stills: a room view, not a photograph, and kind to a fanless device. */
    const val MAX_EDGE = 640
    const val DRAIN_TIMEOUT_US = 10_000L
    const val NAL_SPS = 7
    const val NAL_PPS = 8
  }
}
