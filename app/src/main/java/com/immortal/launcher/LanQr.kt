/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Small LAN helpers shared by the on-Portal "connect from your phone" surfaces (the remote
 * pairing screen): this device's site-local IPv4, and a QR encoder for the pairing URL.
 */

/** The device's site-local IPv4 (e.g. 192.168.x.x), or null if not on a LAN. */
fun lanIp(): String? =
    runCatching {
          NetworkInterface.getNetworkInterfaces().asSequence().filter { it.isUp && !it.isLoopback }
              .flatMap { it.inetAddresses.asSequence() }
              .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
              ?.hostAddress
        }
        .getOrNull()

/** A black-on-white QR code [Bitmap] for [text] (square, [sizePx] px), or null on failure. */
fun lanSetupQr(text: String, sizePx: Int): Bitmap? =
    runCatching {
          val matrix =
              QRCodeWriter().encode(
                  text, BarcodeFormat.QR_CODE, sizePx, sizePx, mapOf(EncodeHintType.MARGIN to 1))
          val w = matrix.width
          val h = matrix.height
          val pixels = IntArray(w * h)
          for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
          }
          Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, w, 0, 0, w, h)
          }
        }
        .getOrNull()
