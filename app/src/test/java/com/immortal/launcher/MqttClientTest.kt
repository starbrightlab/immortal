/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [MqttClient] against a tiny loopback "broker" that just reads the CONNECT and replies
 * with a CONNACK. Guards the plain-TCP path through [MqttClient.connect] (the same path the TLS
 * change re-routes through [MqttClient.wrapTls]) — a real broker isn't needed to prove the
 * handshake plumbing.
 */
class MqttClientTest {

  /** Accept one connection, drain the CONNECT, and send back a CONNACK with [returnCode]. */
  private fun fakeBroker(returnCode: Int): ServerSocket {
    val server = ServerSocket()
    server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
    Thread {
          runCatching {
            server.accept().use { sock ->
              val inp = DataInputStream(sock.getInputStream())
              // CONNECT fixed header: type byte, then a remaining-length varint we decode so we
              // consume exactly the packet before replying.
              inp.readByte()
              var len = 0
              var shift = 0
              while (true) {
                val b = inp.readByte().toInt() and 0xff
                len = len or ((b and 0x7f) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
              }
              repeat(len) { inp.readByte() }
              // CONNACK: 0x20, remaining length 2, session-present 0, return code.
              sock.getOutputStream().apply {
                write(byteArrayOf(0x20, 0x02, 0x00, returnCode.toByte()))
                flush()
              }
            }
          }
        }
        .apply { isDaemon = true }
        .start()
    return server
  }

  private fun clientTo(server: ServerSocket) =
      MqttClient(
          host = "127.0.0.1",
          port = server.localPort,
          clientId = "test",
          username = "",
          password = "",
          will = null,
      )

  @Test
  fun connect_returnsTrue_whenBrokerAccepts() {
    fakeBroker(returnCode = 0).use { server ->
      val c = clientTo(server)
      try {
        assertTrue(c.connect(keepAliveSec = 30, connectTimeoutMs = 2000))
      } finally {
        c.close()
      }
    }
  }

  @Test
  fun connect_returnsFalse_whenBrokerRefuses() {
    fakeBroker(returnCode = 5).use { server -> // 5 = not authorized
      val c = clientTo(server)
      try {
        assertFalse(c.connect(keepAliveSec = 30, connectTimeoutMs = 2000))
      } finally {
        c.close()
      }
    }
  }

  /**
   * Drive a CONNACK + a single QoS-0 PUBLISH whose first-byte [publishFirstByte] carries
   * the bits we want to verify (in particular bit 0, the retain flag). The body is a small
   * fixed topic + payload — enough for [MqttClient.readPacket] to consume cleanly.
   */
  private fun fakeBrokerWithPublish(publishFirstByte: Int): ServerSocket {
    val server = ServerSocket()
    server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
    Thread {
          runCatching {
            server.accept().use { sock ->
              val inp = DataInputStream(sock.getInputStream())
              // Consume the CONNECT.
              inp.readByte()
              var len = 0
              var shift = 0
              while (true) {
                val b = inp.readByte().toInt() and 0xff
                len = len or ((b and 0x7f) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
              }
              repeat(len) { inp.readByte() }
              val out: OutputStream = sock.getOutputStream()
              // CONNACK.
              out.write(byteArrayOf(0x20, 0x02, 0x00, 0x00))
              // PUBLISH: first-byte = type | flags; remaining length = topic_len_be (2) +
              // topic bytes (2: "ab") + payload ("xy", 2) = 6.
              out.write(byteArrayOf(publishFirstByte.toByte(), 6, 0, 2, 'a'.code.toByte(), 'b'.code.toByte(), 'x'.code.toByte(), 'y'.code.toByte()))
              out.flush()
              // Hold the socket open briefly so the client can read before EOF.
              Thread.sleep(200)
            }
          }
        }
        .apply { isDaemon = true }
        .start()
    return server
  }

  @Test
  fun readPacket_extractsRetainBit_set() {
    // 0x31 = PUBLISH (0x30) | retain (0x01).
    fakeBrokerWithPublish(publishFirstByte = 0x31).use { server ->
      val c = clientTo(server)
      try {
        assertTrue(c.connect(keepAliveSec = 30, connectTimeoutMs = 2000))
        val pkt = c.readPacket()
        assertNotNull(pkt)
        assertEquals(0x30, pkt!!.type)
        // Retain bit lives at flags & 0x01 — exactly what MqttPublisher's connect loop
        // inspects to drop retained set-topic messages.
        assertEquals(1, pkt.flags and 0x01)
      } finally {
        c.close()
      }
    }
  }

  @Test
  fun readPacket_extractsRetainBit_unset() {
    // 0x30 = PUBLISH with retain=0.
    fakeBrokerWithPublish(publishFirstByte = 0x30).use { server ->
      val c = clientTo(server)
      try {
        assertTrue(c.connect(keepAliveSec = 30, connectTimeoutMs = 2000))
        val pkt = c.readPacket()
        assertNotNull(pkt)
        assertEquals(0x30, pkt!!.type)
        assertEquals(0, pkt.flags and 0x01)
        // Topic + payload should still parse correctly.
        val (topic, payload) = c.parsePublish(pkt)
        assertEquals("ab", topic)
        assertEquals("xy", String(payload, Charsets.UTF_8))
      } finally {
        c.close()
      }
    }
  }
}
