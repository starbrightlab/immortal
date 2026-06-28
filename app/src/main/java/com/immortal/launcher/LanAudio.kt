/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * A dependency-free, server-less LAN audio link for the Portal-to-Portal intercom /
 * baby monitor. One device [broadcasts][startBroadcast] (mic → TCP), another
 * [listens][startListening] (TCP → speaker). Raw 16-bit mono PCM at 16 kHz over a
 * plain TCP socket on the local network — no signalling server, no GMS, no library.
 *
 * Uses the handset mic (RECORD_AUDIO); the far-field array needs a Meta-signed
 * permission Immortal can't hold, but the handset mic is fine for a monitor.
 */
class LanAudio {
  private val TAG = "ImmortalIntercom"
  // Distinct from FleetConfig.DEFAULT_PORT (8723): on provisioned/remote-paired
  // devices FleetAgentService already binds that port at launch, so reusing it
  // here would make ServerSocket(PORT) in startBroadcast() fail to bind.
  val PORT = 8724
  private val SAMPLE_RATE = 16000

  @Volatile private var running = false
  private var serverThread: Thread? = null
  private var clientThread: Thread? = null
  private var serverSocket: ServerSocket? = null
  private var clientSocket: Socket? = null

  val isActive: Boolean get() = running

  /** Capture the mic and stream it to every device that connects to this port.
   * [onState] reports whether the listening socket bound, so the UI doesn't claim
   * it's broadcasting when the port couldn't be opened (e.g. already in use). */
  fun startBroadcast(onState: (Boolean) -> Unit = {}) {
    stop()
    running = true
    serverThread = Thread {
      val server = runCatching { ServerSocket(PORT) }
          .onFailure { Log.w(TAG, "broadcast bind failed on :$PORT", it) }
          .getOrNull()
      if (server == null) {
        running = false
        onState(false)
        return@Thread
      }
      serverSocket = server
      onState(true)
      runCatching {
        server.soTimeout = 0
        while (running) {
          val socket = runCatching { server.accept() }.getOrNull() ?: continue
          // Serve each listener on its own short-lived thread.
          Thread { runCatching { pumpMicTo(socket.getOutputStream()) }.also { socket.close() } }
              .apply { isDaemon = true; start() }
        }
      }.onFailure { Log.w(TAG, "broadcast failed", it) }
    }.also { it.isDaemon = true; it.start() }
  }

  private fun pumpMicTo(out: OutputStream) {
    val minBuf = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val rec = AudioRecord(
        MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 4096))
    runCatching {
      rec.startRecording()
      val buf = ByteArray(2048)
      while (running) {
        val n = rec.read(buf, 0, buf.size)
        if (n > 0) out.write(buf, 0, n) else break
      }
    }.onFailure { Log.w(TAG, "mic pump ended", it) }
    runCatching { rec.stop() }; runCatching { rec.release() }
  }

  /** Connect to [host] and play whatever audio it streams. [onState] reports connect
   * success/failure so the UI can show status. */
  fun startListening(host: String, onState: (Boolean) -> Unit) {
    stop()
    running = true
    clientThread = Thread {
      val ok = runCatching {
        val socket = Socket()
        socket.connect(InetSocketAddress(host, PORT), 5000)
        clientSocket = socket
        true
      }.getOrDefault(false)
      onState(ok)
      if (ok) runCatching { playFrom(clientSocket!!.getInputStream()) }
          .onFailure { Log.w(TAG, "listen ended", it) }
    }.also { it.isDaemon = true; it.start() }
  }

  private fun playFrom(input: InputStream) {
    val minBuf = AudioTrack.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(maxOf(minBuf, 4096))
        .setTransferMode(AudioTrack.MODE_STREAM).build()
    runCatching {
      track.play()
      val buf = ByteArray(2048)
      while (running) {
        val n = input.read(buf)
        if (n > 0) track.write(buf, 0, n) else break
      }
    }
    runCatching { track.stop() }; runCatching { track.release() }
  }

  /** Stop broadcasting/listening and release sockets. Idempotent. */
  fun stop() {
    running = false
    runCatching { serverSocket?.close() }; serverSocket = null
    runCatching { clientSocket?.close() }; clientSocket = null
    serverThread = null
    clientThread = null
  }
}
