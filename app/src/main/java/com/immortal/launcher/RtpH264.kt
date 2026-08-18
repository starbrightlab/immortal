/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/**
 * RTP packetisation for H.264, per RFC 6184 — the wire format an RTSP client expects.
 *
 * Pure and self-contained on purpose. This is the part of a streaming stack where mistakes are
 * both easy and invisible (an off-by-one in a fragmentation header produces a stream that
 * *almost* plays, or plays in VLC and not in a browser), and it's the only part that can be
 * tested properly without a Portal in front of you.
 *
 * Phase 2 of `docs/design/camera-streaming.md`.
 */
object RtpH264 {
  /** Dynamic payload type for H.264. Announced in the SDP; any value in 96..127 would do. */
  const val PAYLOAD_TYPE = 96

  /** H.264 RTP runs on a 90 kHz clock, fixed by RFC 6184. */
  const val CLOCK_HZ = 90_000L

  /** FU-A fragmentation unit, the NAL type used for a fragmented NAL. */
  const val NAL_FU_A = 28

  /**
   * Conservative payload budget per packet: a 1500-byte Ethernet MTU less IP, UDP and RTP
   * headers, with room to spare for tunnelling. Fragmentation is cheap; a dropped oversize
   * datagram is not.
   */
  const val MAX_PAYLOAD = 1400

  /**
   * Split one NAL unit into RTP payloads.
   *
   * A NAL that fits goes out whole (a "single NAL unit packet" — the payload *is* the NAL,
   * header byte included). Anything larger is fragmented FU-A: the original NAL header is
   * replaced by a two-byte prefix carrying its type and start/end markers, so the receiver can
   * rebuild it.
   *
   * @param nal one NAL unit **without** its start code.
   */
  fun packetize(nal: ByteArray, maxPayload: Int = MAX_PAYLOAD): List<ByteArray> {
    if (nal.isEmpty()) return emptyList()
    if (nal.size <= maxPayload) return listOf(nal)

    val header = nal[0].toInt() and 0xff
    val indicator = ((header and 0xE0) or NAL_FU_A).toByte() // F and NRI kept, type = FU-A
    val nalType = (header and 0x1F).toByte()
    // The original header byte isn't sent; its type moves into the FU header.
    val body = nal.copyOfRange(1, nal.size)
    val perPacket = maxPayload - 2 // FU indicator + FU header
    val out = ArrayList<ByteArray>((body.size + perPacket - 1) / perPacket)
    var offset = 0
    while (offset < body.size) {
      val take = minOf(perPacket, body.size - offset)
      val start = offset == 0
      val end = offset + take >= body.size
      var fuHeader = nalType.toInt()
      if (start) fuHeader = fuHeader or 0x80
      if (end) fuHeader = fuHeader or 0x40
      out.add(
          ByteArray(take + 2).also {
            it[0] = indicator
            it[1] = fuHeader.toByte()
            System.arraycopy(body, offset, it, 2, take)
          })
      offset += take
    }
    return out
  }

  /**
   * The 12-byte RTP header. [marker] goes on the final packet of an access unit — that's how the
   * receiver knows a frame is complete and can be handed to the decoder, so a stream that never
   * sets it stalls or plays late.
   */
  fun header(
      sequence: Int,
      timestamp: Long,
      ssrc: Int,
      marker: Boolean,
      payloadType: Int = PAYLOAD_TYPE,
  ): ByteArray {
    val b = ByteArray(12)
    b[0] = 0x80.toByte() // version 2, no padding, no extension, 0 CSRCs
    b[1] = (payloadType or if (marker) 0x80 else 0).toByte()
    b[2] = (sequence ushr 8).toByte()
    b[3] = sequence.toByte()
    b[4] = (timestamp ushr 24).toByte()
    b[5] = (timestamp ushr 16).toByte()
    b[6] = (timestamp ushr 8).toByte()
    b[7] = timestamp.toByte()
    b[8] = (ssrc ushr 24).toByte()
    b[9] = (ssrc ushr 16).toByte()
    b[10] = (ssrc ushr 8).toByte()
    b[11] = ssrc.toByte()
    return b
  }

  /** Microseconds (what MediaCodec reports) to the 90 kHz RTP clock. */
  fun timestamp(presentationTimeUs: Long): Long = presentationTimeUs * CLOCK_HZ / 1_000_000L

  /**
   * Split an Annex B buffer — what MediaCodec emits, NALs separated by 3- or 4-byte start codes
   * — into the individual NAL units. The codec hands us the SPS and PPS glued together in one
   * config buffer, so this is how they get taken apart for the SDP.
   */
  fun splitAnnexB(buffer: ByteArray): List<ByteArray> {
    val starts = ArrayList<Int>()
    var i = 0
    while (i + 3 <= buffer.size) {
      val threeByte = buffer[i] == 0.toByte() && buffer[i + 1] == 0.toByte() && buffer[i + 2] == 1.toByte()
      val fourByte =
          i + 4 <= buffer.size &&
              buffer[i] == 0.toByte() &&
              buffer[i + 1] == 0.toByte() &&
              buffer[i + 2] == 0.toByte() &&
              buffer[i + 3] == 1.toByte()
      when {
        fourByte -> {
          starts.add(i + 4)
          i += 4
        }
        threeByte -> {
          starts.add(i + 3)
          i += 3
        }
        else -> i++
      }
    }
    if (starts.isEmpty()) return emptyList()
    return starts.mapIndexed { idx, from ->
      // A NAL runs to the start code of the next one, minus that code's leading zero bytes.
      val to = if (idx + 1 < starts.size) trimStartCode(buffer, starts[idx + 1]) else buffer.size
      buffer.copyOfRange(from, maxOf(from, to))
    }
        .filter { it.isNotEmpty() }
  }

  /** Walk back off the start code preceding [nextNalStart] to find where the previous NAL ends. */
  private fun trimStartCode(buffer: ByteArray, nextNalStart: Int): Int {
    var end = nextNalStart - 3 // the 00 00 01 always present
    if (end > 0 && buffer[end - 1] == 0.toByte()) end-- // 4-byte form
    return end
  }

  /** The NAL type of [nal] (7 = SPS, 8 = PPS, 5 = IDR), or -1 when empty. */
  fun nalType(nal: ByteArray): Int = if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1F
}
