/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Portal as a Home Assistant voice satellite, like a Voice PE: a Wyoming server
 * ([WyomingEvent]) that Home Assistant discovers over zeroconf (`_wyoming._tcp`) and connects to.
 *
 * Phase 1 streams the microphone continuously and lets Home Assistant run the whole Assist
 * pipeline, wake word included (openWakeWord), then plays the spoken answer on the speaker.
 *
 * Android 10 Portals are strict about the microphone (measured on a Portal Go):
 *  - only a **foreground service** gets real audio; a plain service reads silence;
 *  - the capture must **start while Immortal is on screen** — so [sync] is called from
 *    [HomeActivity.onResume] — after which it keeps working on Immortal's own screens and dream,
 *    and even with another app in front;
 *  - another app's dream can silence it for good, so the capture watches
 *    `isClientSilenced` and re-arms the next time Immortal comes back to the front.
 *
 * The microphone is shared through [MicOwner] at [MicOwner.PRIORITY_SATELLITE], and nothing is
 * streamed while the system microphone is muted (the same `mic_mute` Immortal publishes to HA).
 */
class VoiceSatelliteService : Service() {
  @Volatile private var running = false
  @Volatile private var streaming = false
  @Volatile private var rearm = false
  // The connection that asked us to run as a satellite; mic audio goes there. Home Assistant
  // also opens short-lived side connections (describe probes), which must not disturb it.
  @Volatile private var client: Connection? = null
  private val connections = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Connection, Boolean>())
  private var server: ServerSocket? = null
  private var nsd: NsdManager? = null
  private var nsdListener: NsdManager.RegistrationListener? = null
  private val playback = Executors.newSingleThreadExecutor()
  private var player: AudioTrack? = null
  private var playedFrames = 0L
  private var overlay: VoiceOverlayWindow? = null
  @Volatile private var retryDelayMs = RETRY_MIN_MS
  // The platform is silencing the capture (status says so until it recovers).
  @Volatile private var paused = false

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    startInForeground()
    running = true
    Thread(::serve, "voice-satellite-server").start()
    Thread(::capture, "voice-satellite-mic").start()
    advertise()
    overlay = VoiceOverlayWindow(this).also { it.start() }
    VoiceStatus.set("Waiting for Home Assistant")
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // Every sync() from a visible Immortal screen re-arms a capture Android has silenced.
    rearm = true
    return START_STICKY
  }

  override fun onDestroy() {
    running = false
    streaming = false
    runCatching { server?.close() }
    connections.forEach { it.close() }
    runCatching { nsdListener?.let { nsd?.unregisterService(it) } }
    playback.shutdownNow()
    runCatching { player?.release() }
    overlay?.stop()
    VoiceHub.idle()
    VoiceStatus.set("Off")
    super.onDestroy()
  }

  // --- Wyoming server ----------------------------------------------------------------------

  private fun serve() {
    try {
      val ss = ServerSocket()
      ss.reuseAddress = true
      ss.bind(InetSocketAddress(VoiceConfig.DEFAULT_PORT))
      server = ss
      Log.i(TAG, "listening on ${VoiceConfig.DEFAULT_PORT}")
      while (running) {
        val conn = Connection(ss.accept())
        connections.add(conn)
        Thread({ handle(conn) }, "voice-satellite-conn").start()
      }
    } catch (e: Exception) {
      if (running) Log.w(TAG, "server stopped: $e")
    }
  }

  private fun handle(conn: Connection) {
    Log.i(TAG, "Home Assistant connected from ${conn.socket.inetAddress?.hostAddress}")
    try {
      val input = BufferedInputStream(conn.socket.getInputStream())
      while (running) {
        val e = WyomingEvent.read(input) ?: break
        onEvent(conn, e)
      }
    } catch (e: Exception) {
      if (running) Log.i(TAG, "connection ended: $e")
    } finally {
      conn.close()
      connections.remove(conn)
      if (client === conn) {
        client = null
        streaming = false
        // Not while shutting down: a closing connection must not overwrite "Off".
        if (running) VoiceStatus.set("Waiting for Home Assistant")
      }
    }
  }

  private fun onEvent(conn: Connection, e: WyomingEvent) {
    if (e.type != "audio-chunk") {
      Log.d(TAG, "<- ${e.type} ${if (conn === client) "(satellite)" else "(side)"} ${e.data}")
    }
    when (e.type) {
      "describe" -> conn.send(WyomingEvent("info", info()))
      "ping" -> conn.send(WyomingEvent("pong", JSONObject().put("text", e.data.optString("text"))))
      "run-satellite" -> {
        retryDelayMs = RETRY_MIN_MS
        requestPipeline(conn)
        conn.send(WyomingEvent("audio-start", WyomingEvent.audioFormat(MIC_RATE)))
        // A reconnecting Home Assistant replaces the previous satellite connection.
        val previous = client
        client = conn
        if (previous != null && previous !== conn) previous.close()
        Log.i(TAG, "running as satellite")
        streaming = true
        VoiceStatus.set("Listening for the wake word")
      }
      "pause-satellite" -> {
        if (client !== conn) return
        streaming = false
        VoiceStatus.set("Paused by Home Assistant")
      }
      "detection" -> {
        Log.i(TAG, "wake word: ${e.data.optString("name")}")
        VoiceStatus.set("Listening…")
        if (VoiceConfig.load(this).wakeSound) wakeTone()
        if (showConversation()) VoiceHub.listening()
      }
      "voice-stopped" -> if (showConversation()) VoiceHub.thinking()
      "transcript" -> {
        Log.i(TAG, "heard: ${e.data.optString("text")}")
        if (showConversation()) VoiceHub.heard(e.data.optString("text"))
      }
      "synthesize" -> {
        Log.i(TAG, "answer: ${e.data.optString("text")}")
        if (showConversation()) VoiceHub.answer(e.data.optString("text"))
      }
      "audio-start" -> {
        Log.i(TAG, "answer audio at ${e.data.optInt("rate")} Hz")
        // Audio with no conversation in progress is an announcement (assist_satellite.announce).
        if (showConversation() && VoiceHub.state.phase == VoiceHub.Phase.IDLE) VoiceHub.announcing()
        playback.execute { startPlayback(e.data) }
      }
      "audio-chunk" -> e.payload?.let { pcm -> playback.execute { writePlayback(pcm) } }
      "audio-stop" -> playback.execute { finishPlayback(conn) }
      "error" -> {
        // After an error Home Assistant stops restarting the pipeline and waits for the
        // satellite to ask again (so a broken config can't spin). Ask again, backing off.
        Log.w(TAG, "pipeline error: ${e.data.optString("code")} ${e.data.optString("text")}")
        if (VoiceHub.state.phase != VoiceHub.Phase.IDLE) VoiceHub.idle()
        val delay = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(RETRY_MAX_MS)
        Thread {
              sleepQuietly(delay)
              if (running && streaming && client === conn) requestPipeline(conn)
            }
            .start()
      }
      // Sent when Home Assistant starts listening for the wake word on our audio.
      "detect" -> {
        retryDelayMs = RETRY_MIN_MS
        VoiceStatus.set("Listening for the wake word")
      }
      else -> Log.d(TAG, "ignored ${e.type}")
    }
  }

  /**
   * Always-on streaming: Home Assistant runs wake word → speech → intent → speech on our audio
   * and restarts on its own after each answer (but not after an error — see "error").
   */
  private fun requestPipeline(conn: Connection) {
    conn.send(
        WyomingEvent(
            "run-pipeline",
            JSONObject()
                .put("start_stage", "wake")
                .put("end_stage", "tts")
                .put("restart_on_end", true)))
  }

  /** The `info` reply: a satellite with a 16 kHz mic and a speaker; no local wake word yet. */
  private fun info(): JSONObject {
    val attribution =
        JSONObject().put("name", "Immortal").put("url", "https://github.com/starbrightlab/immortal")
    fun program(name: String, description: String) =
        JSONObject()
            .put("name", name)
            .put("attribution", attribution)
            .put("installed", true)
            .put("description", description)
            .put("version", appVersion())
    return JSONObject()
        .put("asr", JSONArray())
        .put("tts", JSONArray())
        .put("handle", JSONArray())
        .put("intent", JSONArray())
        .put("wake", JSONArray())
        .put(
            "mic",
            JSONArray().put(
                program("portal-mic", "Portal microphone")
                    .put("mic_format", WyomingEvent.audioFormat(MIC_RATE))))
        .put(
            "snd",
            JSONArray().put(
                program("portal-speaker", "Portal speaker")
                    .put("snd_format", WyomingEvent.audioFormat(SND_RATE))))
        .put(
            "satellite",
            program(satelliteName(), "Immortal voice satellite")
                .put("has_vad", false)
                .put("active_wake_words", JSONArray())
                .put("max_active_wake_words", 0)
                .put("supports_trigger", true))
  }

  // --- Microphone --------------------------------------------------------------------------

  private fun capture() {
    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val chunk = ByteArray(CHUNK_SAMPLES * 2)
    while (running) {
      if (!MicOwner.acquire(MIC_OWNER, MicOwner.PRIORITY_SATELLITE)) {
        VoiceStatus.set("Microphone in use")
        sleepQuietly(2000)
        continue
      }
      val rec = openRecord()
      if (rec == null) {
        MicOwner.release(MIC_OWNER)
        sleepQuietly(5000)
        continue
      }
      rearm = false
      var timestampMs = 0L
      var lastCheck = 0L
      var silencedSince = 0L
      try {
        rec.startRecording()
        while (running && MicOwner.holds(MIC_OWNER)) {
          val n = rec.read(chunk, 0, chunk.size)
          if (n <= 0) break
          val now = System.currentTimeMillis()
          if (now - lastCheck > 5000) {
            lastCheck = now
            if (isSilenced(am, rec)) {
              // Silenced by the platform (e.g. another app's dream). Reading on gets zeros;
              // reopen when Immortal is back in front (sync() asks us to re-arm), and retry now
              // and then anyway, in case it already is.
              if (silencedSince == 0L) silencedSince = now
              paused = true
              VoiceStatus.set("Paused — open Immortal to resume")
              if (rearm || now - silencedSince > SILENCED_RETRY_MS) break
            } else {
              silencedSince = 0L
              if (paused) {
                paused = false
                VoiceStatus.set(
                    if (client != null) "Listening for the wake word" else "Waiting for Home Assistant")
              }
            }
          }
          val conn = client
          if (streaming && conn != null && !am.isMicrophoneMute) {
            conn.send(
                WyomingEvent(
                    "audio-chunk",
                    WyomingEvent.audioFormat(MIC_RATE).put("timestamp", timestampMs),
                    chunk.copyOf(n)))
          }
          timestampMs += n / 2 * 1000L / MIC_RATE
        }
      } catch (e: Exception) {
        Log.w(TAG, "capture: $e")
      } finally {
        runCatching { rec.stop() }
        rec.release()
        if (running && !MicOwner.holds(MIC_OWNER)) VoiceStatus.set("Microphone in use")
        MicOwner.release(MIC_OWNER)
      }
      sleepQuietly(500)
    }
  }

  private fun openRecord(): AudioRecord? {
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      VoiceStatus.set("Microphone permission needed")
      return null
    }
    val min =
        AudioRecord.getMinBufferSize(
            MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    return runCatching {
          AudioRecord(
                  MediaRecorder.AudioSource.VOICE_RECOGNITION,
                  MIC_RATE,
                  AudioFormat.CHANNEL_IN_MONO,
                  AudioFormat.ENCODING_PCM_16BIT,
                  maxOf(min, MIC_RATE))
              .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        }
        .getOrNull()
  }

  private fun isSilenced(am: AudioManager, rec: AudioRecord): Boolean =
      Build.VERSION.SDK_INT >= 29 &&
          am.activeRecordingConfigurations.any {
            it.clientAudioSessionId == rec.audioSessionId && it.isClientSilenced
          }

  // --- Speaker -----------------------------------------------------------------------------

  private fun startPlayback(format: JSONObject) {
    runCatching { player?.release() }
    val rate = format.optInt("rate", SND_RATE)
    val channels =
        if (format.optInt("channels", 1) == 2) AudioFormat.CHANNEL_OUT_STEREO
        else AudioFormat.CHANNEL_OUT_MONO
    val min = AudioTrack.getMinBufferSize(rate, channels, AudioFormat.ENCODING_PCM_16BIT)
    player =
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // The alarm stream, like Immortal's notify sounds: on a Portal it is the one
                    // volume besides calls that the media slider doesn't drive.
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(channels)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build())
            .setBufferSizeInBytes(maxOf(min, rate))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also {
              it.setVolume(VoiceConfig.load(this).voiceVolume / 100f)
              it.play()
            }
    playedFrames = 0
    VoiceStatus.set("Answering")
  }

  private fun writePlayback(pcm: ByteArray) {
    val p = player ?: return
    p.write(pcm, 0, pcm.size)
    playedFrames += pcm.size / (2 * p.channelCount)
  }

  /** Let the speaker drain, then tell Home Assistant the answer has played. */
  private fun finishPlayback(conn: Connection) {
    val p = player
    if (p != null) {
      val deadline = System.currentTimeMillis() + 30_000
      while (p.playbackHeadPosition < playedFrames && System.currentTimeMillis() < deadline) {
        sleepQuietly(50)
      }
      runCatching { p.stop() }
      p.release()
      player = null
    }
    conn.send(WyomingEvent("played"))
    VoiceHub.finished()
    VoiceStatus.set("Listening for the wake word")
  }

  private fun wakeTone() {
    runCatching {
      val tone = ToneGenerator(AudioManager.STREAM_ALARM, (VoiceConfig.load(this).voiceVolume * 0.7f).toInt())
      tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 160)
      Thread {
            sleepQuietly(400)
            tone.release()
          }
          .start()
    }
  }

  private fun showConversation(): Boolean = VoiceConfig.load(this).showTranscript

  // --- Discovery & foreground --------------------------------------------------------------

  private fun advertise() {
    val mgr = getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
    val info =
        NsdServiceInfo().apply {
          serviceName = satelliteName()
          serviceType = "_wyoming._tcp"
          port = VoiceConfig.DEFAULT_PORT
        }
    val listener =
        object : NsdManager.RegistrationListener {
          override fun onServiceRegistered(info: NsdServiceInfo) =
              Log.i(TAG, "advertised as ${info.serviceName}").let {}

          override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) =
              Log.w(TAG, "zeroconf registration failed: $code").let {}

          override fun onServiceUnregistered(info: NsdServiceInfo) {}

          override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
    runCatching { mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
        .onFailure { Log.w(TAG, "zeroconf: $it") }
    nsd = mgr
    nsdListener = listener
  }

  private fun startInForeground() {
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(
        NotificationChannel(CHANNEL, "Voice assistant", NotificationManager.IMPORTANCE_MIN))
    val n =
        Notification.Builder(this, CHANNEL)
            .setContentTitle("Voice assistant")
            .setContentText("Listening for Home Assistant's wake word")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    if (Build.VERSION.SDK_INT >= 30) {
      startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    } else {
      startForeground(NOTIFICATION_ID, n)
    }
  }

  private fun satelliteName(): String {
    val device =
        runCatching { Settings.Global.getString(contentResolver, "device_name") }.getOrNull()
            ?: Build.MODEL
    return "Immortal $device".trim()
  }

  private fun appVersion(): String =
      runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "1"

  /** One Home Assistant connection; writes are serialised (mic audio and replies interleave). */
  private class Connection(val socket: Socket) {
    private val out: OutputStream = BufferedOutputStream(socket.getOutputStream())

    fun send(e: WyomingEvent) {
      runCatching { synchronized(out) { e.write(out) } }.onFailure { close() }
    }

    fun close() {
      runCatching { socket.close() }
    }
  }

  companion object {
    private const val TAG = "ImmortalVoice"
    private const val CHANNEL = "voice_satellite"
    private const val NOTIFICATION_ID = 7342
    private const val MIC_OWNER = "voice-satellite"
    private const val MIC_RATE = 16000
    private const val SND_RATE = 22050
    // 1024 samples (64 ms) per chunk, the size Home Assistant itself uses.
    private const val CHUNK_SAMPLES = 1024
    private const val RETRY_MIN_MS = 3000L
    private const val RETRY_MAX_MS = 60_000L
    // While the platform silences the capture, reopen it this often in case it would now work.
    private const val SILENCED_RETRY_MS = 30_000L

    /**
     * Start (or re-arm) the satellite when it is enabled, stop it otherwise. Call it from a
     * visible Immortal screen: on Android 10 a capture only gets real audio if it started while
     * the app was in front.
     */
    fun sync(context: Context) {
      val intent = Intent(context, VoiceSatelliteService::class.java)
      if (VoiceConfig.load(context).enabled) {
        runCatching {
              if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
              else context.startService(intent)
            }
            .onFailure { Log.w(TAG, "start: $it") }
      } else {
        context.stopService(intent)
      }
    }

    private fun sleepQuietly(ms: Long) {
      try {
        Thread.sleep(ms)
      } catch (_: InterruptedException) {}
    }
  }
}

/**
 * Human-readable satellite state, for the settings screen and Home Assistant (the MQTT
 * "Voice assistant status" sensor). Listeners hear each change, and the current state on add.
 */
object VoiceStatus {
  @Volatile
  var text: String = "Off"
    private set

  private val listeners = java.util.concurrent.CopyOnWriteArraySet<(String) -> Unit>()

  fun set(s: String) {
    if (s == text) return
    text = s
    listeners.forEach { it(s) }
  }

  fun addListener(l: (String) -> Unit) {
    listeners.add(l)
    l(text)
  }

  fun removeListener(l: (String) -> Unit) {
    listeners.remove(l)
  }
}
