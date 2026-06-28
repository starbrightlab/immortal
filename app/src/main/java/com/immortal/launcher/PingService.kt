/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * "Ping the other room" — light up another Portal from this one with a tone, contact-free
 * and server-free. A tiny UDP listener on every Immortal device waits for a broadcast
 * ping on the LAN; when one arrives it plays the chime and speaks who sent it. No dialer,
 * no accounts, nothing to sign in to — just a doorbell between rooms on your Wi-Fi.
 *
 * Started from [ImmortalApp]. [send] fires a one-off broadcast from the Ping tile.
 */
object PingService {

  private const val TAG = "ImmortalPing"
  private const val PORT = 50121
  private const val MAGIC = "IMMORTAL_PING/1"

  @Volatile private var socket: DatagramSocket? = null
  @Volatile private var running = false
  private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

  /** Begin listening for pings (idempotent). */
  fun start(context: Context) {
    if (running) return
    running = true
    val app = context.applicationContext
    // Wi-Fi hardware filters out broadcast/multicast frames unless a lock is held, so
    // without this the listener would simply never receive a ping on most devices.
    runCatching {
      val wifi = app.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
      multicastLock = wifi.createMulticastLock("immortal-ping").apply {
        setReferenceCounted(false)
        acquire()
      }
    }.onFailure { Log.w(TAG, "multicast lock failed (broadcast receive may not work)", it) }
    thread(name = "immortal-ping-listener", isDaemon = true) {
      runCatching {
            val s = DatagramSocket(PORT).apply { broadcast = true; reuseAddress = true }
            socket = s
            val buf = ByteArray(256)
            while (running) {
              val pkt = DatagramPacket(buf, buf.size)
              s.receive(pkt) // blocks
              val msg = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
              if (msg.startsWith(MAGIC)) {
                val sender = msg.substringAfter('|', "").take(40).ifBlank { "Someone" }
                onPing(app, sender)
              }
            }
          }
          .onFailure { Log.w(TAG, "ping listener stopped", it) }
    }
  }

  private fun onPing(context: Context, sender: String) {
    runCatching {
      ChimePlayer.playPing(context, repeats = 2)
      ChimePlayer.announce(context, "$sender is calling")
    }
  }

  /** Broadcast a ping to the LAN. [name] is shown/spoken on the receiving devices. */
  fun send(context: Context, name: String) {
    thread(name = "immortal-ping-send", isDaemon = true) {
      runCatching {
        DatagramSocket().use { s ->
          s.broadcast = true
          val payload = "$MAGIC|${name.take(40)}".toByteArray(Charsets.UTF_8)
          val addr = InetAddress.getByName("255.255.255.255")
          // A couple of repeats in case a frame is dropped.
          repeat(3) {
            s.send(DatagramPacket(payload, payload.size, addr, PORT))
            Thread.sleep(120)
          }
        }
      }.onFailure { Log.w(TAG, "ping send failed", it) }
    }
  }
}
