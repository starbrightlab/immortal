/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.io.DataInputStream
import java.io.InputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A dependency-free, server-less LAN audio link for Portal-to-Portal room audio. One device
 * [broadcasts][startBroadcast] (mic -> TCP), while receivers [listen][startListening]
 * (TCP -> speaker). New receivers perform a small delay handshake and receive timestamped PCM
 * frames so rooms start on the same playback timeline. A receiver that sends no hello remains
 * compatible with the previous raw-PCM build.
 */
class LanAudio {
  private val TAG = "ImmortalIntercom"

  // Distinct from FleetConfig.DEFAULT_PORT (8723), which the fleet agent already binds.
  val PORT = IntercomPolicy.PORT
  private val SAMPLE_RATE = 16000
  private val MIC_OWNER = "intercom@${System.identityHashCode(this)}"
  private val FRAME_BYTES = 2048
  private val RECEIVER_QUEUE_FRAMES = 64
  private val frameSequence = AtomicLong()
  private val sessionStartedNanos = System.nanoTime()

  private val lifecycleLock = Any()
  private val receivers = ConcurrentHashMap.newKeySet<Receiver>()

  @Volatile private var running = false
  @Volatile private var serverThread: Thread? = null
  @Volatile private var clientThread: Thread? = null
  @Volatile private var record: AudioRecord? = null
  @Volatile private var captureThread: Thread? = null
  private var serverSocket: ServerSocket? = null
  private var clientSocket: Socket? = null

  val isActive: Boolean
    get() = running

  /**
   * Capture once and fan the same PCM stream out to every accepted receiver. [onState] waits until
   * the port is bound AND the one shared microphone is live, so callers never report a broadcast
   * that has no audio behind it.
   */
  fun startBroadcast(onState: (Boolean) -> Unit = {}) {
    synchronized(lifecycleLock) {
      stopLocked()
      running = true
      serverThread = thread(name = "intercom-broadcast", isDaemon = true) { runBroadcast(onState) }
    }
  }

  /** Connect to [host] and play whatever audio it streams. [onState] reports connect success. */
  fun startListening(host: String, onState: (Boolean) -> Unit) {
    synchronized(lifecycleLock) {
      stopLocked()
      running = true
      clientThread =
          thread(name = "intercom-receive", isDaemon = true) { runReceive(host, onState) }
    }
  }

  /** Stop broadcasting/listening and release every capture/socket resource. Idempotent. */
  fun stop() {
    synchronized(lifecycleLock) { stopLocked() }
  }

  private fun runBroadcast(onState: (Boolean) -> Unit) {
    val server =
        runCatching { ServerSocket(PORT) }
            .onFailure { Log.w(TAG, "broadcast bind failed on :$PORT", it) }
            .getOrNull()
    if (server == null || !openSharedMicrophone()) {
      runCatching { server?.close() }
      running = false
      onState(false)
      return
    }
    synchronized(lifecycleLock) { if (running) serverSocket = server }

    try {
      server.soTimeout = 0
      onState(true)
      while (running && MicOwner.holds(MIC_OWNER)) {
        val socket = runCatching { server.accept() }.getOrNull() ?: continue
        val receiver = runCatching { Receiver(socket, sessionStartedNanos) }.getOrNull()
        if (receiver == null || !receivers.add(receiver)) {
          runCatching { socket.close() }
        } else {
          receiver.start()
        }
      }
    } catch (failure: Throwable) {
      if (running) Log.w(TAG, "broadcast ended", failure)
      running = false
      onState(false)
    } finally {
      closeMicrophone()
      stopReceivers()
      runCatching { serverSocket?.close() }
      synchronized(lifecycleLock) {
        if (serverSocket === server) serverSocket = null
        if (serverThread === Thread.currentThread()) serverThread = null
      }
    }
  }

  private fun openSharedMicrophone(): Boolean {
    // Someone speaking outranks other microphone users. There is deliberately one acquisition for
    // all receivers: per-client acquisition was what let one disconnect mute the rest.
    if (!MicOwner.acquire(MIC_OWNER, MicOwner.PRIORITY_INTERCOM)) {
      Log.w(TAG, "microphone held by ${MicOwner.holder} - not broadcasting")
      return false
    }

    val minBuffer =
        AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    record =
        AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, FRAME_BYTES))
    return runCatching {
          record?.startRecording()
          captureThread =
              thread(name = "intercom-microphone", isDaemon = true) { pumpSharedMicrophone() }
          true
        }
        .onFailure {
          Log.w(TAG, "shared microphone start failed", it)
          closeMicrophone()
        }
        .getOrDefault(false)
  }

  private fun pumpSharedMicrophone() {
    val buffer = ByteArray(FRAME_BYTES)
    while (running && MicOwner.holds(MIC_OWNER)) {
      val currentRecord = record ?: break
      val read = runCatching { currentRecord.read(buffer, 0, buffer.size) }.getOrDefault(-1)
      if (read <= 0) {
        stop()
        break
      }
      val frame = buffer.copyOf(read)
      val sequence = frameSequence.incrementAndGet().toInt()
      val senderElapsed = System.nanoTime() - sessionStartedNanos
      receivers.forEach { receiver -> receiver.send(frame, sequence, senderElapsed) }
    }
    // A higher-priority microphone owner must get the device promptly, even while the broadcast
    // accept loop is waiting for its next receiver.
    if (running && !MicOwner.holds(MIC_OWNER)) stop()
  }

  private fun closeMicrophone() {
    runCatching { record?.stop() }
    runCatching { record?.release() }
    record = null
    captureThread = null
    MicOwner.release(MIC_OWNER)
  }

  private fun runReceive(host: String, onState: (Boolean) -> Unit) {
    val handshake =
        runCatching {
              val socket = Socket()
              socket.connect(InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS)
              clientSocket = socket
              RoomLinkSyncHandshake.start(socket)
            }
            .getOrNull()
    onState(handshake != null)
    if (handshake == null) return
    runCatching { playFrom(clientSocket!!.getInputStream(), handshake) }
        .onFailure { Log.w(TAG, "listen ended", it) }
    if (running) running = false
    runCatching { clientSocket?.close() }
    synchronized(lifecycleLock) {
      clientSocket = null
      if (clientThread === Thread.currentThread()) clientThread = null
    }
  }

  private fun playFrom(input: InputStream, handshake: RoomLinkSyncHandshake) {
    val minBuffer =
        AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val stream = DataInputStream(input.buffered())
    val track =
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
            .setBufferSizeInBytes(maxOf(minBuffer, FRAME_BYTES * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    runCatching {
          track.play()
          playSynchronized(stream, handshake.clock, track)
        }
        .onFailure { Log.w(TAG, "audio playback ended", it) }
    runCatching { track.stop() }
    runCatching { track.release() }
  }

  private fun playSynchronized(
      stream: DataInputStream,
      clock: RoomLinkPlaybackClock,
      track: AudioTrack,
  ) {
    val header = ByteArray(RoomLinkProtocol.HEADER_BYTES)
    val pcm = ByteArray(FRAME_BYTES)

    while (running) {
      stream.readFully(header)
      val frame = RoomLinkProtocol.parse(header)
          ?: throw IOException("receiver received an invalid room-link frame")
      if (frame.kind != RoomLinkFrameKind.FRAME) {
        throw IOException("receiver received an unexpected room-link message")
      }
      stream.readFully(pcm)

      when (clock.schedule(frame.timestampNanos, System.nanoTime())) {
        is RoomLinkPlaybackDecision.Wait -> {
          val dueAt = clock.dueAt(frame.timestampNanos)
          val delayNanos = dueAt - System.nanoTime()
          if (delayNanos > 0) {
            Thread.sleep(delayNanos / 1_000_000, (delayNanos % 1_000_000).toInt())
          }
          track.write(pcm, 0, pcm.size)
        }
        RoomLinkPlaybackDecision.PlayNow -> track.write(pcm, 0, pcm.size)
        RoomLinkPlaybackDecision.Drop -> Unit
      }
    }
  }

  private fun stopReceivers() {
    receivers.forEach { receiver -> receiver.stop() }
    receivers.clear()
  }

  private fun stopLocked() {
    running = false
    runCatching { serverSocket?.close() }
    serverSocket = null
    runCatching { clientSocket?.close() }
    clientSocket = null
    stopReceivers()
    closeMicrophone()
    serverThread = null
    clientThread = null
  }

  /** Queue copies of shared frames so one slow TCP receiver cannot stall every other room. */
  private inner class Receiver(
      private val socket: Socket,
      private val sessionOriginNanos: Long,
  ) {
    private val output = socket.getOutputStream()
    private val frames = ArrayBlockingQueue<ByteArray>(RECEIVER_QUEUE_FRAMES)
    private var writerThread: Thread? = null
    private val isSynchronized: Boolean

    @Volatile private var active = true

    init {
      isSynchronized = negotiate()
    }

    /** Returns true after the receiver agrees to the timestamped playback timeline. */
    private fun negotiate(): Boolean {
      socket.soTimeout = HANDSHAKE_TIMEOUT_MS
      return try {
        val request = ByteArray(RoomLinkProtocol.HEADER_BYTES)
        DataInputStream(socket.getInputStream().buffered()).readFully(request)
        val hello = RoomLinkProtocol.parse(request)
            ?: throw IOException("invalid room-link hello")
        if (hello.kind != RoomLinkFrameKind.HELLO) {
          throw IOException("unexpected first room-link message")
        }

        val response = RoomLinkProtocol.sync(System.nanoTime() - sessionOriginNanos)
        output.write(response)
        output.flush()
        socket.soTimeout = 0
        true
      } catch (timeout: Exception) {
        socket.soTimeout = 0
        false
      }
    }

    fun send(frame: ByteArray, sequence: Int, senderElapsed: Long) {
      if (!active) return
      val wireFrame =
          if (!isSynchronized) {
            frame
          } else {
            // The capture pump supplies both values for every shared frame.
            RoomLinkProtocol.frame(sequence, senderElapsed, frame)
          }
      if (!frames.offer(wireFrame)) {
        Log.i(TAG, "receiver fell behind - closing it")
        stop()
      }
    }

    fun start() {
      if (!active) return
      writerThread = thread(name = "intercom-receiver-writer", isDaemon = true) { writeFrames() }
    }

    private fun writeFrames() {
      while (active && running) {
        val frame =
            runCatching { frames.poll(250, TimeUnit.MILLISECONDS) }.getOrDefault(null) ?: continue
        runCatching {
              output.write(frame)
              output.flush()
            }
            .onFailure {
              if (active) Log.i(TAG, "receiver write ended")
              stop()
            }
      }
      stop()
    }

    fun stop() {
      active = false
      receivers.remove(this)
      runCatching { socket.close() }
      writerThread?.interrupt()
      writerThread = null
    }
  }

  private companion object {
    const val CONNECT_TIMEOUT_MS = 5000
    const val HANDSHAKE_TIMEOUT_MS = 250
  }
}
