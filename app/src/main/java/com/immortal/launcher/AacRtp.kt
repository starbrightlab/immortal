/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/**
 * RTP packetisation for AAC (RFC 3640, `AAC-hbr` mode) and the AudioSpecificConfig an SDP has to
 * carry — the audio counterpart to [RtpH264].
 *
 * Pure, for the same reason: a wrong `config` string or a mis-sized AU header gives a stream that
 * connects and produces silence or static, with nothing in any log to say why.
 *
 * Phase 3 of `docs/design/camera-streaming.md`.
 */
object AacRtp {
  /** Dynamic payload type for the audio track (video uses 96). */
  const val PAYLOAD_TYPE = 97

  /** AAC-LC. The `2` in an AudioSpecificConfig's five-bit object type. */
  const val OBJECT_TYPE_AAC_LC = 2

  /** Sample rates in the order MPEG-4 indexes them; the index goes in the config, not the rate. */
  val SAMPLE_RATE_TABLE =
      listOf(
          96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

  /** MPEG-4 sampling frequency index for [rate], or -1 if it isn't one of the standard rates. */
  fun sampleRateIndex(rate: Int): Int = SAMPLE_RATE_TABLE.indexOf(rate)

  /**
   * The two-byte AudioSpecificConfig for AAC-LC: 5 bits object type, 4 bits rate index, 4 bits
   * channel count, then padding. This is what the SDP's `config=` carries, and it's how the
   * decoder learns the sample rate — get it wrong and audio plays at the wrong speed, or not at
   * all, while everything else looks healthy.
   */
  fun audioSpecificConfig(sampleRate: Int, channels: Int): ByteArray {
    val rateIndex = sampleRateIndex(sampleRate).coerceAtLeast(0)
    val bits = (OBJECT_TYPE_AAC_LC shl 11) or (rateIndex shl 7) or (channels shl 3)
    return byteArrayOf((bits ushr 8).toByte(), bits.toByte())
  }

  /** The config as the lower-case hex the SDP expects. */
  fun configHex(sampleRate: Int, channels: Int): String =
      audioSpecificConfig(sampleRate, channels).joinToString("") { "%02x".format(it) }

  /**
   * Wrap one AAC access unit as an RTP payload.
   *
   * `AAC-hbr` prefixes the audio with a 2-byte AU-headers-length **in bits**, then one 16-bit AU
   * header per unit: 13 bits of size and 3 bits of index. One unit per packet keeps latency low
   * and the arithmetic honest.
   *
   * @param frame a raw AAC frame with **no** ADTS header — MediaCodec emits raw frames, and an
   *   ADTS header left on would be decoded as audio data.
   */
  fun packetize(frame: ByteArray): ByteArray {
    val payload = ByteArray(4 + frame.size)
    payload[0] = 0x00 // AU-headers-length: 16 bits...
    payload[1] = 0x10 // ...i.e. one 16-bit header
    val header = (frame.size shl 3) // 13-bit size, 3-bit index (0)
    payload[2] = (header ushr 8).toByte()
    payload[3] = header.toByte()
    System.arraycopy(frame, 0, payload, 4, frame.size)
    return payload
  }

  /** RTP timestamps for audio run at the sample rate, not 90 kHz. */
  fun timestamp(presentationTimeUs: Long, sampleRate: Int): Long =
      presentationTimeUs * sampleRate / 1_000_000L

  /**
   * The `m=audio` half of the SDP. `sizelength`/`indexlength`/`indexdeltalength` must agree with
   * the 13+3 split [packetize] writes, or the receiver mis-parses every packet.
   */
  fun audioSdp(sampleRate: Int, channels: Int, trackId: Int, payloadType: Int = PAYLOAD_TYPE): String =
      buildString {
        append("m=audio 0 RTP/AVP $payloadType\r\n")
        append("a=rtpmap:$payloadType mpeg4-generic/$sampleRate/$channels\r\n")
        append(
            "a=fmtp:$payloadType streamtype=5;profile-level-id=1;mode=AAC-hbr;" +
                "sizelength=13;indexlength=3;indexdeltalength=3;" +
                "config=${configHex(sampleRate, channels)}\r\n")
        append("a=control:trackID=$trackId\r\n")
      }
}

/**
 * Who may hold the microphone.
 *
 * The Portal has one, and before this nothing arbitrated for it: [LanAudio] (the intercom) and
 * [AudioNote] both opened `AudioSource.MIC` directly, so whoever asked second simply got nothing
 * useful — silently. Adding a third consumer (the camera's audio track) made that untenable.
 *
 * Priority, not fairness: a person talking beats a background stream. A higher-priority claim
 * takes the microphone from a lower-priority holder, which then finds out via [holder] and stops.
 * Equal priority does not preempt — the incumbent keeps it.
 */
object MicOwner {
  /** A live intercom announcement — someone is speaking right now. */
  const val PRIORITY_INTERCOM = 100

  /** A voice note being recorded deliberately by the user. */
  const val PRIORITY_NOTE = 90

  /** The camera's audio track: continuous, and the first thing that should yield. */
  const val PRIORITY_STREAM = 10

  private val lock = Any()
  private var current: String? = null
  private var currentPriority = 0

  /** Who holds the microphone, or null if nobody does. */
  val holder: String?
    get() = synchronized(lock) { current }

  /**
   * Claim the microphone for [owner]. Returns true when granted — either it was free, or [owner]
   * already had it, or [priority] beat the incumbent.
   */
  fun acquire(owner: String, priority: Int): Boolean =
      synchronized(lock) {
        val held = current
        if (held == null || held == owner || priority > currentPriority) {
          current = owner
          currentPriority = priority
          true
        } else {
          false
        }
      }

  /** Release, but only if [owner] still holds it — a preempted owner must not clear the winner. */
  fun release(owner: String) =
      synchronized(lock) {
        if (current == owner) {
          current = null
          currentPriority = 0
        }
      }

  /** True while [owner] still holds the microphone; a preempted holder should stop capturing. */
  fun holds(owner: String): Boolean = synchronized(lock) { current == owner }

  /** Test seam — the object is a process-wide singleton. */
  fun resetForTest() =
      synchronized(lock) {
        current = null
        currentPriority = 0
      }
}
