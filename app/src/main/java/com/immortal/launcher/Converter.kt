/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Walk-up-and-use unit + currency converter. Units convert fully offline; currency
 * uses the keyless Frankfurter API (European Central Bank reference rates), cached so
 * a mostly-offline Portal keeps converting at the last-known rate.
 */
object Converter {

  private const val PREFS = "immortal_converter"

  /** A measurement family. [units] map a display name to its factor relative to the
   * family's base unit; temperature is special-cased (affine, not a pure factor). */
  data class Category(val name: String, val units: List<String>)

  val LENGTH =
      linkedMapOf(
          "m" to 1.0, "km" to 1000.0, "cm" to 0.01, "mm" to 0.001,
          "mi" to 1609.344, "yd" to 0.9144, "ft" to 0.3048, "in" to 0.0254)
  val MASS =
      linkedMapOf("kg" to 1.0, "g" to 0.001, "lb" to 0.45359237, "oz" to 0.028349523125, "st" to 6.35029318)
  val VOLUME =
      linkedMapOf(
          "L" to 1.0, "mL" to 0.001, "gal (US)" to 3.785411784, "qt (US)" to 0.946352946,
          "cup (US)" to 0.2365882365, "fl oz (US)" to 0.0295735295625)
  val SPEED =
      linkedMapOf("km/h" to 1.0, "m/s" to 3.6, "mph" to 1.609344, "kn" to 1.852)

  val UNIT_CATEGORIES =
      listOf(
          "Length" to LENGTH,
          "Mass" to MASS,
          "Volume" to VOLUME,
          "Speed" to SPEED,
          "Temperature" to linkedMapOf("°C" to 1.0, "°F" to 1.0, "K" to 1.0),
      )

  /** Convert [value] from [from] to [to] within [category]. Pure + unit-tested. */
  fun convert(category: String, from: String, to: String, value: Double): Double {
    if (category == "Temperature") return convertTemp(from, to, value)
    val map = UNIT_CATEGORIES.firstOrNull { it.first == category }?.second ?: return value
    val f = map[from] ?: return value
    val t = map[to] ?: return value
    return value * f / t // to base, then to target
  }

  /** Affine temperature conversion across °C / °F / K. */
  fun convertTemp(from: String, to: String, value: Double): Double {
    val celsius =
        when (from) {
          "°F" -> (value - 32.0) * 5.0 / 9.0
          "K" -> value - 273.15
          else -> value
        }
    return when (to) {
      "°F" -> celsius * 9.0 / 5.0 + 32.0
      "K" -> celsius + 273.15
      else -> celsius
    }
  }

  // ---- Currency (keyless Frankfurter / ECB, Bank of England fallback) ----------

  /** A small, common set so the picker stays glanceable on a 50–100 cm screen. */
  val CURRENCIES =
      listOf("EUR", "USD", "GBP", "RON", "PLN", "CHF", "SEK", "NOK", "DKK", "CAD", "AUD", "JPY", "CZK", "HUF")

  /** Convert [amount] [from]→[to] using cached/fetched ECB rates. Null if no rate is
   * available (offline with an empty cache). */
  fun convertCurrency(context: Context, from: String, to: String, amount: Double): Double? {
    if (from == to) return amount
    val rates = rates(context, from) ?: return null
    val r = rates[to] ?: return null
    return amount * r
  }

  /** Rates with [base] = 1.0, fetched from Frankfurter and cached per base. */
  private fun rates(context: Context, base: String): Map<String, Double>? {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val cacheKey = "rates_$base"
    val tsKey = "rates_${base}_ts"
    val fresh = System.currentTimeMillis() - prefs.getLong(tsKey, 0L) < 6L * 60 * 60 * 1000
    if (fresh) {
      prefs.getString(cacheKey, null)?.let { return parseRates(it) }
    }
    val fetched = fetchRatesJson(base)
    if (fetched != null && parseRates(fetched) != null) {
      prefs.edit().putString(cacheKey, fetched).putLong(tsKey, System.currentTimeMillis()).apply()
      return parseRates(fetched)
    }
    // Stale cache beats nothing.
    return prefs.getString(cacheKey, null)?.let { parseRates(it) }
  }

  /** Frankfurter's `{"rates":{...}}` for [base], or the same shape built from the Bank of
   * England's daily table (keyless, via allratestoday.com) when Frankfurter is unreachable. */
  private fun fetchRatesJson(base: String): String? {
    val targets = CURRENCIES.filter { it != base }.joinToString(",")
    runCatching { httpGet("https://api.frankfurter.app/latest?from=$base&to=$targets") }
        .getOrNull()
        ?.takeIf { parseRates(it) != null }
        ?.let {
          return it
        }
    return runCatching { boeRatesJson(base) }.getOrNull()
  }

  /** The Bank of England table lists each pair as "1 base = value quote" against GBP; rebase
   * it to [base] and re-encode in Frankfurter's shape so the cache and parser stay as they are.
   * Null when the table has no rate for [base] (e.g. RON, CZK, HUF). */
  private fun boeRatesJson(base: String): String? {
    val table = JSONObject(httpGet("https://allratestoday.com/api/open/central-bank/boe")).getJSONArray("rates")
    val perPound = hashMapOf("GBP" to 1.0)
    for (i in 0 until table.length()) {
      val row = table.getJSONObject(i)
      val value = row.optDouble("value")
      if (row.optString("type") != "reference" || value.isNaN() || value <= 0.0) continue
      when {
        row.optString("base") == "GBP" -> perPound[row.getString("quote")] = value
        row.optString("quote") == "GBP" -> perPound[row.getString("base")] = 1.0 / value
      }
    }
    val baseUnits = perPound[base] ?: return null
    val rates = JSONObject()
    for (c in CURRENCIES) if (c != base) perPound[c]?.let { rates.put(c, it / baseUnits) }
    return if (rates.length() == 0) null else JSONObject().put("rates", rates).toString()
  }

  private fun parseRates(json: String): Map<String, Double>? =
      runCatching {
            val r = JSONObject(json).getJSONObject("rates")
            buildMap {
              r.keys().forEach { k -> put(k, r.getDouble(k)) }
            }
          }
          .getOrNull()
          ?.takeIf { it.isNotEmpty() }

  private fun httpGet(spec: String): String {
    val c = URL(spec).openConnection() as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 10000
    c.setRequestProperty("User-Agent", "Immortal/1.0")
    return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
  }
}
