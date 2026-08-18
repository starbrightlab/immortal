/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A small RTSP server: enough of RFC 2326 for go2rtc, VLC and ffmpeg to fetch the description and
 * play the stream, and no more.
 *
 * **RTP is interleaved over the same TCP connection** (RFC 2326 §10.12) rather than sent on
 * separate UDP ports. That's a deliberate simplification: one socket to manage, no port
 * negotiation, no NAT or firewall questions on a home LAN, and no packet loss to conceal — at the
 * cost of TCP's head-of-line blocking, which for a fixed indoor scene at 15fps is a fair trade.
 *
 * Frames arrive through [broadcast] and are fanned out to every playing client. A client that has
 * fallen behind or gone away is dropped rather than allowed to block the encoder.
 *
 * Phase 2 of `docs/design/camera-streaming.md`.
 */
class RtspServer(
    private val port: Int = DEFAULT_PORT,
    /** The SDP to answer DESCRIBE with, or null while the encoder hasn't produced one yet. */
    private val sdp: () -> String?,
    /** Called when the first client starts playing, and when the last one stops. */
    private val onActiveChanged: (active: Boolean) -> Unit = {},
) {
  private var server: ServerSocket? = null
  private var acceptThread: Thread? = null
  @Volatile private var running = false
  private val clients = CopyOnWriteArrayList<Client>()
  @Volatile private var sequence = 0
  @Volatile private var audioSequence = 0
  private val ssrc = (System.nanoTime() and 0x7FFFFFFF).toInt()
  // A distinct SSRC: two streams sharing one would be treated as a single confused source.
  private val audioSsrc = ssrc xor 0x5A5A5A5A

  /** One connected client. [playing] gates RTP: a client gets frames only after it says PLAY. */
  private class Client(val socket: Socket, val out: OutputStream) {
    @Volatile var playing = false
    /** True once the client has SETUP the audio track; it only gets sound if it asked for it. */
    @Volatile var wantsAudio = false
    val writeLock = Any()
  }

  fun isRunning(): Boolean = running

  fun clientCount(): Int = clients.count { it.playing }

  /** Bind and start accepting. Throws on bind failure so the caller can report it. */
  fun start() {
    if (running) return
    val s = ServerSocket(port)
    s.reuseAddress = true
    server = s
    running = true
    acceptThread =
        Thread({ acceptLoop(s) }, "immortal-rtsp").apply {
          isDaemon = true
          start()
        }
    Log.i(TAG, "RTSP listening on :$port")
  }

  fun stop() {
    running = false
    clients.forEach { runCatching { it.socket.close() } }
    clients.clear()
    runCatching { server?.close() }
    server = null
    acceptThread?.interrupt()
    acceptThread = null
    Log.i(TAG, "RTSP stopped")
  }

  private fun acceptLoop(s: ServerSocket) {
    while (running) {
      val socket = runCatching { s.accept() }.getOrElse { return }
      Thread({ serve(socket) }, "immortal-rtsp-client").apply {
        isDaemon = true
        start()
      }
    }
  }

  /**
   * Speak RTSP to one client until it goes away. Every failure closes just this connection — a
   * browser opening a speculative socket, or a client vanishing mid-stream, must never disturb
   * the encoder or the other viewers.
   */
  private fun serve(socket: Socket) {
    val client = Client(socket, socket.getOutputStream())
    clients.add(client)
    runCatching {
          socket.tcpNoDelay = true
          val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
          while (running && !socket.isClosed) {
            val request = readRequest(reader) ?: break
            handle(client, request)
          }
        }
        .onFailure { if (running) Log.i(TAG, "client ended: ${it.message}") }
    clients.remove(client)
    runCatching { socket.close() }
    if (client.playing) notifyActive()
  }

  /** Read one request: the request line, then headers, to the blank line. Null at end of stream. */
  private fun readRequest(reader: BufferedReader): Request? {
    val line = reader.readLine() ?: return null
    if (line.isBlank()) return readRequest(reader)
    val parts = line.split(' ')
    if (parts.size < 2) return null
    val headers = HashMap<String, String>()
    while (true) {
      val h = reader.readLine() ?: break
      if (h.isBlank()) break
      val idx = h.indexOf(':')
      if (idx > 0) headers[h.substring(0, idx).trim().lowercase()] = h.substring(idx + 1).trim()
    }
    return Request(parts[0].uppercase(), parts[1], headers)
  }

  private class Request(val method: String, val uri: String, val headers: Map<String, String>)

  private fun handle(client: Client, req: Request) {
    val cseq = req.headers["cseq"] ?: "0"
    when (req.method) {
      "OPTIONS" ->
          respond(client, cseq, extra = "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n")
      "DESCRIBE" -> {
        val body = sdp()
        if (body == null) {
          // The encoder hasn't produced its parameter sets yet; a client that retries will get
          // them. Better than describing a stream we can't yet describe correctly.
          respond(client, cseq, status = "454 Session Not Found")
        } else {
          respond(
              client,
              cseq,
              extra =
                  "Content-Base: ${req.uri}\r\nContent-Type: application/sdp\r\n" +
                      "Content-Length: ${body.toByteArray(Charsets.US_ASCII).size}\r\n",
              body = body)
        }
      }
      "SETUP" -> {
        // Each track gets its own interleaved channel pair on the one connection: video on 0/1,
        // audio on 2/3. A client that never SETUPs track 1 simply never receives sound.
        val audioTrack = req.uri.contains("trackID=$AUDIO_TRACK")
        if (audioTrack) client.wantsAudio = true
        val channels = if (audioTrack) "$AUDIO_CHANNEL-${AUDIO_CHANNEL + 1}" else "$RTP_CHANNEL-${RTP_CHANNEL + 1}"
        respond(
            client,
            cseq,
            extra = "Transport: RTP/AVP/TCP;unicast;interleaved=$channels\r\nSession: $SESSION_ID\r\n")
      }
      "PLAY" -> {
        respond(client, cseq, extra = "Session: $SESSION_ID\r\nRange: npt=0.000-\r\n")
        client.playing = true
        notifyActive()
        Log.i(TAG, "client playing (${clientCount()} total)")
      }
      "TEARDOWN" -> {
        respond(client, cseq, extra = "Session: $SESSION_ID\r\n")
        client.playing = false
        runCatching { client.socket.close() }
        notifyActive()
      }
      else -> respond(client, cseq, status = "501 Not Implemented")
    }
  }

  private fun respond(
      client: Client,
      cseq: String,
      status: String = "200 OK",
      extra: String = "",
      body: String = "",
  ) {
    val text = "RTSP/1.0 $status\r\nCSeq: $cseq\r\n$extra\r\n$body"
    synchronized(client.writeLock) {
      client.out.write(text.toByteArray(Charsets.US_ASCII))
      client.out.flush()
    }
  }

  /**
   * Packetise one access unit and send it to every playing client.
   *
   * The marker bit goes on the final packet of the frame — that's what tells a decoder the frame
   * is complete, and a stream that never sets it stalls or plays late.
   */
  fun broadcast(nals: List<ByteArray>, presentationTimeUs: Long, keyframe: Boolean) {
    if (!running) return
    val playing = clients.filter { it.playing }
    if (playing.isEmpty()) return
    val timestamp = RtpH264.timestamp(presentationTimeUs)
    // Parameter sets are announced in the SDP, so they're not repeated in the stream; everything
    // else in this access unit goes out in order.
    val payloadNals = nals.filter { RtpH264.nalType(it) !in setOf(NAL_SPS, NAL_PPS) }
    if (payloadNals.isEmpty()) return
    payloadNals.forEachIndexed { nalIndex, nal ->
      val packets = RtpH264.packetize(nal)
      packets.forEachIndexed { pktIndex, payload ->
        val lastOfFrame = nalIndex == payloadNals.lastIndex && pktIndex == packets.lastIndex
        sequence = (sequence + 1) and 0xFFFF
        val packet = RtpH264.header(sequence, timestamp, ssrc, marker = lastOfFrame) + payload
        playing.forEach { send(it, packet) }
      }
    }
    if (keyframe) Log.v(TAG, "keyframe sent to ${playing.size} client(s)")
  }

  /**
   * Send one AAC frame to every client that asked for the audio track.
   *
   * Audio keeps its own sequence numbers and its own clock — RTP timestamps are per-stream, and
   * running audio on the video's 90 kHz clock is a classic way to get sound that drifts.
   */
  fun broadcastAudio(frame: ByteArray, presentationTimeUs: Long, sampleRate: Int) {
    if (!running) return
    val listeners = clients.filter { it.playing && it.wantsAudio }
    if (listeners.isEmpty()) return
    audioSequence = (audioSequence + 1) and 0xFFFF
    val packet =
        RtpH264.header(
            audioSequence,
            AacRtp.timestamp(presentationTimeUs, sampleRate),
            audioSsrc,
            // Every AAC frame is a complete access unit, so the marker is always set.
            marker = true,
            payloadType = AacRtp.PAYLOAD_TYPE,
        ) + AacRtp.packetize(frame)
    listeners.forEach { send(it, packet, AUDIO_CHANNEL) }
  }

  /** Interleaved framing: '$', channel, 2-byte length, then the RTP packet (RFC 2326 §10.12). */
  private fun send(client: Client, packet: ByteArray, channel: Byte = RTP_CHANNEL) {
    runCatching {
          synchronized(client.writeLock) {
            client.out.write(
                byteArrayOf(
                    INTERLEAVE_MAGIC,
                    channel,
                    (packet.size ushr 8).toByte(),
                    packet.size.toByte()))
            client.out.write(packet)
            client.out.flush()
          }
        }
        .onFailure {
          // A client that has gone away or fallen behind is dropped; it must not stall the encoder.
          Log.i(TAG, "dropping client: ${it.message}")
          client.playing = false
          runCatching { client.socket.close() }
          clients.remove(client)
          notifyActive()
        }
  }

  private fun notifyActive() = runCatching { onActiveChanged(clientCount() > 0) }

  companion object {
    private const val TAG = "ImmortalRtsp"
    const val DEFAULT_PORT = 8554
    private const val SESSION_ID = "immortal"
    private const val INTERLEAVE_MAGIC: Byte = 0x24 // '$'
    private const val RTP_CHANNEL: Byte = 0
    private const val AUDIO_CHANNEL: Byte = 2
    const val VIDEO_TRACK = 0
    const val AUDIO_TRACK = 1
    private const val NAL_SPS = 7
    private const val NAL_PPS = 8
  }
}
