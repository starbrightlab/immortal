/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/**
 * The session description an RTSP client fetches with DESCRIBE, and the encodings it needs.
 *
 * Pure, so the exact bytes can be checked without a Portal. It matters: a client decides whether
 * it can play the stream from this text alone, and a wrong `profile-level-id` or a mangled
 * parameter set produces a black picture with no error anywhere.
 */
object RtspSdp {
  private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

  /**
   * Standard base64. Hand-rolled because `java.util.Base64` needs API 26 and `android.util.Base64`
   * is stubbed out in JVM unit tests — this way the SDP is testable and works on every Portal.
   */
  fun base64(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val out = StringBuilder((bytes.size + 2) / 3 * 4)
    var i = 0
    while (i < bytes.size) {
      val b0 = bytes[i].toInt() and 0xff
      val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
      val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
      out.append(B64[b0 ushr 2])
      out.append(B64[((b0 and 0x03) shl 4) or (b1 ushr 4)])
      out.append(if (i + 1 < bytes.size) B64[((b1 and 0x0F) shl 2) or (b2 ushr 6)] else '=')
      out.append(if (i + 2 < bytes.size) B64[b2 and 0x3F] else '=')
      i += 3
    }
    return out.toString()
  }

  /**
   * The `profile-level-id`: profile_idc, the constraint flags, and level_idc as six hex digits,
   * read straight out of the SPS. Taken from the SPS rather than from what we *asked* the encoder
   * for, so a device that quietly ignored the profile request describes itself honestly.
   */
  fun profileLevelId(sps: ByteArray): String {
    // sps[0] is the NAL header; the three bytes after it are what this field encodes.
    if (sps.size < 4) return "42001f" // constrained baseline 3.1, a safe-to-advertise default
    return "%02x%02x%02x".format(sps[1].toInt() and 0xff, sps[2].toInt() and 0xff, sps[3].toInt() and 0xff)
  }

  /**
   * A minimal single-video-track SDP. `packetization-mode=1` says the stream may use FU-A
   * fragmentation, which it will for any real keyframe; the parameter sets travel here rather
   * than in the stream, which is why the encoder's config buffer is kept rather than sent.
   */
  fun videoSdp(
      width: Int,
      height: Int,
      sps: ByteArray,
      pps: ByteArray,
      payloadType: Int = RtpH264.PAYLOAD_TYPE,
  ): String {
    val params = "${base64(sps)},${base64(pps)}"
    return buildString {
      append("v=0\r\n")
      append("o=- 0 0 IN IP4 0.0.0.0\r\n")
      append("s=Immortal\r\n")
      append("c=IN IP4 0.0.0.0\r\n")
      append("t=0 0\r\n")
      append("a=tool:immortal\r\n")
      append("m=video 0 RTP/AVP $payloadType\r\n")
      append("a=rtpmap:$payloadType H264/${RtpH264.CLOCK_HZ}\r\n")
      append(
          "a=fmtp:$payloadType packetization-mode=1;profile-level-id=${profileLevelId(sps)};" +
              "sprop-parameter-sets=$params\r\n")
      append("a=framesize:$payloadType $width-$height\r\n")
      append("a=control:trackID=0\r\n")
    }
  }
}
