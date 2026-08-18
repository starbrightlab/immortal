/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives a real [FleetHttpServer] over loopback. Guards the connection-level failure
 * handling: a phone browser opens speculative connections and abandons them, and a
 * socket error from one of those must not escape its pool thread — that reached the
 * uncaught handler as `java.lang.Error` and took the whole launcher down.
 */
class FleetHttpServerSocketTest {

  private fun freePort(): Int = ServerSocket(0).use { it.localPort }

  private fun serverOn(port: Int, handler: (FleetHttpServer.Request) -> FleetHttpServer.Response) =
      FleetHttpServer(port, handler).apply { start() }

  private fun connect(port: Int): Socket =
      Socket().apply {
        connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 2_000)
        soTimeout = 5_000
      }

  /** Send [text] verbatim over a fresh connection; returns the status line. */
  private fun raw(port: Int, text: String): String =
      connect(port).use { s ->
        s.getOutputStream().apply {
          write(text.toByteArray(Charsets.US_ASCII))
          flush()
        }
        s.getInputStream().bufferedReader().readLine()
      }

  /** Send one well-formed request line over a fresh connection; returns the status line. */
  private fun request(port: Int, line: String): String =
      raw(port, "$line HTTP/1.1\r\nHost: portal\r\n\r\n")

  private fun ok(port: Int) = request(port, "GET /info")

  @Test
  fun connectionResetDoesNotEscapeThePoolThread_andTheServerKeepsServing() {
    val caught = mutableListOf<Throwable>()
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { _, t -> synchronized(caught) { caught.add(t) } }
    val port = freePort()
    val server = serverOn(port) { FleetHttpServer.Response(200, """{"ok":true}""") }
    try {
      // SO_LINGER 0 makes close() send an RST, so the server's header read fails the same
      // way an abandoned browser connection does — without waiting out the read timeout.
      repeat(3) {
        Socket().apply {
          connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 2_000)
          setSoLinger(true, 0)
          close()
        }
      }
      // The pool must still answer — proof no worker died and the pool wasn't poisoned.
      assertEquals("HTTP/1.1 200 OK", ok(port))
      synchronized(caught) { assertTrue("uncaught: $caught", caught.isEmpty()) }
    } finally {
      server.stop()
      Thread.setDefaultUncaughtExceptionHandler(previous)
    }
  }

  @Test
  fun malformedRequestGetsA400AndTheServerSurvives() {
    val port = freePort()
    val server = serverOn(port) { FleetHttpServer.Response(200, """{"ok":true}""") }
    try {
      // One token where a method + target belong — parseHead rejects it.
      assertTrue(raw(port, "garbage\r\nHost: portal\r\n\r\n").startsWith("HTTP/1.1 400"))
      assertEquals("HTTP/1.1 200 OK", ok(port))
    } finally {
      server.stop()
    }
  }

  @Test
  fun optionsPreflightIsAnsweredBeforeTheHandlerRuns() {
    val port = freePort()
    var handled = 0
    val server =
        serverOn(port) {
          handled++
          FleetHttpServer.Response(200, """{"ok":true}""")
        }
    try {
      assertTrue(request(port, "OPTIONS /info").startsWith("HTTP/1.1 204"))
      assertEquals(0, handled)
      assertEquals("HTTP/1.1 200 OK", ok(port))
      assertEquals(1, handled)
    } finally {
      server.stop()
    }
  }

  @Test
  fun aThrowingHandlerBecomesA500RatherThanACrash() {
    val port = freePort()
    val server = serverOn(port) { error("boom") }
    try {
      assertTrue(request(port, "GET /info").startsWith("HTTP/1.1 500"))
    } finally {
      server.stop()
    }
  }

  @Test
  fun stopClosesTheListeningPort() {
    val port = freePort()
    val server = serverOn(port) { FleetHttpServer.Response(200, """{"ok":true}""") }
    assertEquals("HTTP/1.1 200 OK", ok(port))
    server.stop()
    // Prove the port was released by binding it ourselves: if stop() had left the listener open,
    // this throws BindException.
    //
    // The obvious alternative — assert that a fresh connect() fails — is the same claim but it
    // races, and it flaked on CI while passing locally every time. Once stop() frees the port the
    // OS is free to hand that number to anything else asking for an ephemeral port, including
    // another test in this suite calling freePort(). If something else binds it first, the connect
    // succeeds and the test fails while stop() did its job perfectly.
    ServerSocket().use { probe ->
      probe.reuseAddress = true
      probe.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
      assertTrue("stop() should release the listening port", probe.isBound)
    }
  }
}
