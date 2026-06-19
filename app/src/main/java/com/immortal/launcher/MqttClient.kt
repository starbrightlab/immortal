/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A minimal MQTT 3.1.1 client over a raw socket — no Gradle dependency, matching the
 * hand-rolled net code elsewhere in the app ([FleetHttpServer], [SnapcastControlClient],
 * [MaWebSocket]). Just what the HA MQTT-Discovery publisher needs: CONNECT (with auth +
 * last-will), QoS-0 PUBLISH (retained), SUBSCRIBE, and PINGREQ.
 *
 * Blocking by design: [connect] and [readPacket] block, so drive them from a worker
 * thread (see [MqttPublisher]). Writes ([publish]/[subscribe]/[ping]) are synchronized so
 * the ping thread and the state-publishing threads can't interleave a packet on the wire
 * while the read loop runs on its own thread.
 */
class MqttClient(
    private val host: String,
    private val port: Int,
    private val clientId: String,
    private val username: String,
    private val password: String,
    private val will: Will?,
) {
  /** A retained last-will, published by the broker if we drop without a clean DISCONNECT. */
  data class Will(val topic: String, val payload: String, val retain: Boolean)

  /** A decoded incoming control packet. [type] is the high nibble (e.g. 0x30 = PUBLISH). */
  data class Packet(val type: Int, val flags: Int, val body: ByteArray)

  private var socket: Socket? = null
  private var out: OutputStream? = null
  private var inp: InputStream? = null
  private val writeLock = Any()
  private var packetId = 0

  /** Open the socket and complete the MQTT handshake. Returns true on CONNACK accepted. */
  fun connect(keepAliveSec: Int, connectTimeoutMs: Int = 8000): Boolean {
    val s = Socket()
    s.connect(InetSocketAddress(host, port), connectTimeoutMs)
    s.tcpNoDelay = true
    socket = s
    out = BufferedOutputStream(s.getOutputStream())
    inp = BufferedInputStream(s.getInputStream())
    sendConnect(keepAliveSec)
    val ack = readPacket() ?: return false
    // CONNACK (0x20): byte[1] is the return code; 0 = accepted.
    return ack.type == 0x20 && ack.body.size >= 2 && ack.body[1].toInt() == 0
  }

  /** QoS-0 publish. [retain] tells the broker to keep it as the topic's last value. */
  fun publish(topic: String, payload: String, retain: Boolean) =
      publish(topic, payload.toByteArray(Charsets.UTF_8), retain)

  fun publish(topic: String, payload: ByteArray, retain: Boolean) {
    val header = 0x30 or (if (retain) 0x01 else 0x00) // PUBLISH, QoS 0
    sendPacket(header, encString(topic) + payload)
  }

  /** Subscribe to [topicFilter] at QoS 0 (SUBACK is consumed by the read loop). */
  fun subscribe(topicFilter: String) {
    val id = nextPacketId()
    sendPacket(0x82, encShort(id) + encString(topicFilter) + byteArrayOf(0x00))
  }

  fun ping() = sendPacket(0xC0, EMPTY)

  fun disconnect() = runCatching { sendPacket(0xE0, EMPTY) }.let {}

  fun close() {
    runCatching { socket?.close() }
    socket = null
  }

  /** Read one full control packet, blocking. Returns null on a clean EOF. */
  fun readPacket(): Packet? {
    val i = inp ?: return null
    val first = i.read()
    if (first < 0) return null
    val len = readRemainingLength(i) ?: return null
    val body = ByteArray(len)
    readFully(i, body)
    return Packet(first and 0xF0, first and 0x0F, body)
  }

  /** Parse a PUBLISH packet's body into (topic, payload). */
  fun parsePublish(pkt: Packet): Pair<String, ByteArray> {
    val qos = (pkt.flags and 0x06) shr 1
    val b = pkt.body
    val topicLen = ((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff)
    val topic = String(b, 2, topicLen, Charsets.UTF_8)
    var idx = 2 + topicLen
    if (qos > 0) idx += 2 // skip the packet id we don't need at QoS 0
    return topic to b.copyOfRange(idx, b.size)
  }

  // --- packet construction ----------------------------------------------------

  private fun sendConnect(keepAliveSec: Int) {
    var flags = 0x02 // clean session
    will?.let {
      flags = flags or 0x04 // will flag (will QoS stays 0)
      if (it.retain) flags = flags or 0x20
    }
    if (username.isNotBlank()) flags = flags or 0x80
    if (password.isNotBlank()) flags = flags or 0x40

    val varHeader =
        encString("MQTT") +
            byteArrayOf(0x04) + // protocol level 4 = MQTT 3.1.1
            byteArrayOf(flags.toByte()) +
            encShort(keepAliveSec)

    var payload = encString(clientId)
    will?.let { payload += encString(it.topic) + encString(it.payload) }
    if (username.isNotBlank()) payload += encString(username)
    if (password.isNotBlank()) payload += encString(password)

    sendPacket(0x10, varHeader + payload)
  }

  private fun sendPacket(header: Int, body: ByteArray) {
    synchronized(writeLock) {
      val o = out ?: return
      o.write(header)
      o.write(remainingLength(body.size))
      o.write(body)
      o.flush()
    }
  }

  private fun nextPacketId(): Int {
    packetId = (packetId % 65535) + 1
    return packetId
  }

  // --- wire helpers -----------------------------------------------------------

  /** MQTT string: 2-byte big-endian length prefix + UTF-8 bytes. */
  private fun encString(s: String): ByteArray {
    val b = s.toByteArray(Charsets.UTF_8)
    return encShort(b.size) + b
  }

  private fun encShort(v: Int): ByteArray =
      byteArrayOf(((v shr 8) and 0xff).toByte(), (v and 0xff).toByte())

  /** MQTT "remaining length" varint (1–4 bytes). */
  private fun remainingLength(len: Int): ByteArray {
    val out = ArrayList<Byte>(4)
    var x = len
    do {
      var enc = x % 128
      x /= 128
      if (x > 0) enc = enc or 0x80
      out.add(enc.toByte())
    } while (x > 0)
    return out.toByteArray()
  }

  private fun readRemainingLength(i: InputStream): Int? {
    var multiplier = 1
    var value = 0
    var digit: Int
    do {
      digit = i.read()
      if (digit < 0) return null
      value += (digit and 0x7f) * multiplier
      multiplier *= 128
      if (multiplier > 128 * 128 * 128) return null // malformed (>4 bytes)
    } while (digit and 0x80 != 0)
    return value
  }

  private fun readFully(i: InputStream, buf: ByteArray) {
    var off = 0
    while (off < buf.size) {
      val n = i.read(buf, off, buf.size - off)
      if (n < 0) throw EOFException("socket closed mid-packet")
      off += n
    }
  }

  private companion object {
    val EMPTY = ByteArray(0)
  }
}
