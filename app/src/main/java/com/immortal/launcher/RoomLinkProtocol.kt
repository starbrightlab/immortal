/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.InputStream
import java.nio.ByteBuffer

enum class RoomLinkFrameKind(val value: Byte) {
  HELLO(1),
  SYNC(2),
  FRAME(3),
}

data class RoomLinkHeader(
    val kind: RoomLinkFrameKind,
    val sequence: Int,
    val timestampNanos: Long,
)

sealed interface RoomLinkPlaybackDecision {
  data class Wait(val delayMs: Long) : RoomLinkPlaybackDecision
  object PlayNow : RoomLinkPlaybackDecision
  object Drop : RoomLinkPlaybackDecision
}

/**
 * Closed wire contract for dependency-free synchronized room audio. Timestamps use the source's
 * monotonic nanosecond timeline; receivers map that timeline onto their own monotonic clock.
 */
object RoomLinkProtocol {
  const val VERSION: Byte = 1
  const val HEADER_BYTES = 16
  const val MAGIC = 0x494D524C // "IMRL"

  fun hello(localRequestNanos: Long): ByteArray =
      header(RoomLinkFrameKind.HELLO, 0, localRequestNanos)

  fun sync(senderElapsedNanos: Long): ByteArray =
      header(RoomLinkFrameKind.SYNC, 0, senderElapsedNanos)

  fun frame(sequence: Int, senderElapsedNanos: Long, pcm: ByteArray): ByteArray {
    require(pcm.isNotEmpty()) { "room-link frame cannot be empty" }
    val packet = ByteArray(HEADER_BYTES + pcm.size)
    header(RoomLinkFrameKind.FRAME, sequence, senderElapsedNanos).copyInto(packet)
    pcm.copyInto(packet, HEADER_BYTES)
    return packet
  }

  fun parse(bytes: ByteArray): RoomLinkHeader? {
    if (bytes.size < HEADER_BYTES) return null
    val source = ByteBuffer.wrap(bytes)
    if (source.int != MAGIC || source.get() != VERSION) return null
    val kindValue = source.get()
    val kind = RoomLinkFrameKind.entries.firstOrNull { it.value == kindValue } ?: return null
    val sequence = source.short.toInt() and 0xffff
    val timestamp = source.long
    return RoomLinkHeader(kind, sequence, timestamp)
  }

  fun readHeader(input: InputStream): RoomLinkHeader? {
    val header = ByteArray(HEADER_BYTES)
    var read = 0
    while (read < header.size) {
      val count = input.read(header, read, header.size - read)
      if (count < 0) return null
      read += count
    }
    return parse(header)
  }

  private fun header(kind: RoomLinkFrameKind, sequence: Int, timestampNanos: Long): ByteArray {
    return ByteBuffer.allocate(HEADER_BYTES)
        .putInt(MAGIC)
        .put(VERSION)
        .put(kind.value)
        .putShort(sequence.toShort())
        .putLong(timestampNanos)
        .array()
  }
}

class RoomLinkSyncHandshake private constructor(val clock: RoomLinkPlaybackClock) {
  companion object {
    /**
     * Performs the receiver half of a delay/request handshake. Half the observed round trip
     * approximates one-way latency without requiring NTP or synchronized wall clocks.
     */
    fun start(socket: java.net.Socket): RoomLinkSyncHandshake {
      val requestAt = System.nanoTime()
      val output = socket.getOutputStream()
      output.write(RoomLinkProtocol.hello(requestAt))
      output.flush()
      val response = RoomLinkProtocol.readHeader(socket.getInputStream())
          ?: throw java.io.IOException("source did not answer the room-link handshake")
      if (response.kind != RoomLinkFrameKind.SYNC) {
        throw java.io.IOException("unexpected room-link handshake response")
      }
      val replyAt = System.nanoTime()
      return RoomLinkSyncHandshake(
          RoomLinkPlaybackClock(
              sourceElapsedNanos = response.timestampNanos,
              localRequestNanos = requestAt,
              localReplyNanos = replyAt,
          )
      )
    }
  }
}

class RoomLinkPlaybackClock(
    private val sourceElapsedNanos: Long,
    private val localRequestNanos: Long,
    private val localReplyNanos: Long,
    private val startupDelayNanos: Long = STARTUP_DELAY_MS * 1_000_000,
    private val lateDropNanos: Long = LATE_DROP_MS * 1_000_000,
) {
  init {
    require(localReplyNanos >= localRequestNanos) { "round trip cannot be negative" }
    require(startupDelayNanos >= 0 && lateDropNanos >= 0) { "sync delays cannot be negative" }
  }

  private val localTimeAtSourceZero: Long by lazy {
    val roundTrip = localReplyNanos - localRequestNanos
    localReplyNanos - roundTrip / 2 - sourceElapsedNanos
  }

  fun dueAt(sourceElapsedNanos: Long): Long =
      localTimeAtSourceZero + sourceElapsedNanos + startupDelayNanos

  fun schedule(sourceElapsedNanos: Long, nowNanos: Long): RoomLinkPlaybackDecision {
    val dueAt = dueAt(sourceElapsedNanos)
    return when {
      nowNanos < dueAt -> RoomLinkPlaybackDecision.Wait((dueAt - nowNanos + 999_999) / 1_000_000)
      nowNanos <= dueAt + lateDropNanos -> RoomLinkPlaybackDecision.PlayNow
      else -> RoomLinkPlaybackDecision.Drop
    }
  }

  private companion object {
    const val STARTUP_DELAY_MS = 180L
    const val LATE_DROP_MS = 100L
  }
}
