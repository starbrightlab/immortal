/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * An ambient sensor the Portal may or may not physically have, and how it should look in Home
 * Assistant. Everything is auto-detected: a Portal without the hardware simply never publishes
 * that entity, so nothing promises a reading the device can't take.
 *
 * @param key MQTT object id (the topic segment and the HA `unique_id` suffix).
 * @param minDelta smallest change worth a message — below this the value is sensor noise.
 */
enum class AmbientKind(
    val key: String,
    val title: String,
    val sensorType: Int,
    val deviceClass: String,
    val unit: String,
    val decimals: Int,
    val minDelta: Double,
) {
  TEMPERATURE("temperature", "Temperature", Sensor.TYPE_AMBIENT_TEMPERATURE, "temperature", "°C", 1, 0.1),
  ILLUMINANCE("illuminance", "Ambient light", Sensor.TYPE_LIGHT, "illuminance", "lx", 0, 1.0),
  HUMIDITY("humidity", "Humidity", Sensor.TYPE_RELATIVE_HUMIDITY, "humidity", "%", 0, 1.0),
  PRESSURE("pressure", "Pressure", Sensor.TYPE_PRESSURE, "pressure", "hPa", 1, 0.2),
}

/**
 * When a new reading is worth putting on the wire, and how it's rendered. Pure — no Android, no
 * clock of its own — so the rate limiting is unit-tested rather than eyeballed on a device.
 */
object AmbientPolicy {
  /** Ordinary cadence: a drifting value gets at most one message per this interval. */
  const val MIN_INTERVAL_MS = 20_000L

  /** Floor for the big-change fast path, so a flickering sensor still can't flood the broker. */
  const val FAST_MIN_MS = 2_000L

  /** A change this many times [AmbientKind.minDelta] is an event, not drift (a light switch). */
  const val BIG_CHANGE_FACTOR = 10

  /**
   * True when [next] should be published. The first reading always goes out; after that a value
   * must have moved by at least [minDelta] AND waited out [MIN_INTERVAL_MS] — unless it moved a
   * lot, which is exactly the case an automation is waiting on (someone turned the lights on),
   * so that jumps the queue down to [FAST_MIN_MS].
   */
  fun shouldPublish(
      prev: Double?,
      next: Double,
      lastPublishMs: Long,
      nowMs: Long,
      minDelta: Double,
  ): Boolean {
    if (prev == null) return true
    val delta = abs(next - prev)
    if (delta < minDelta) return false
    val elapsed = nowMs - lastPublishMs
    if (elapsed >= MIN_INTERVAL_MS) return true
    return delta >= minDelta * BIG_CHANGE_FACTOR && elapsed >= FAST_MIN_MS
  }

  /**
   * Render for MQTT. Locale.US is load-bearing: a device in a comma-decimal locale would
   * otherwise publish "21,4", which Home Assistant reads as a non-numeric state.
   */
  fun format(value: Double, decimals: Int): String =
      String.format(Locale.US, "%.${decimals}f", value)

  /**
   * Apply the user's temperature calibration. An ambient sensor sitting inside a powered device
   * reads its own waste heat, so it runs warm by a few degrees — a fixed offset is how you get
   * it to agree with a thermometer in the same room.
   */
  fun calibrate(celsius: Double, offsetC: Int): Double = celsius + offsetC
}

/**
 * Publishes the Portal's ambient sensors to whoever is listening — in practice [MqttPublisher],
 * which owns this object's lifetime and only runs it while a broker is connected, so an
 * un-configured device pays nothing for it.
 *
 * Readings are throttled by [AmbientPolicy] rather than forwarded raw: `TYPE_LIGHT` in
 * particular fires continuously, and one MQTT message per sensor event would bury the broker in
 * noise nobody automates on.
 *
 * @param onValue called with the object key and the formatted state, on the sensor thread.
 */
class AmbientSensors(
    private val appContext: Context,
    private val onValue: (AmbientKind, String) -> Unit,
) : SensorEventListener {

  private val manager by lazy {
    appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
  }
  // Concurrent: readings arrive on the sensor thread while start()/stop() clear these from the
  // publisher's worker (or the main thread, on teardown).
  private val lastValue = ConcurrentHashMap<AmbientKind, Double>()
  private val lastPublishMs = ConcurrentHashMap<AmbientKind, Long>()
  @Volatile private var running = false

  /** The kinds this Portal actually has hardware for, in enum order. */
  fun available(): List<AmbientKind> {
    val m = manager ?: return emptyList()
    return AmbientKind.values().filter {
      runCatching { m.getDefaultSensor(it.sensorType) != null }.getOrDefault(false)
    }
  }

  /**
   * Start listening to every available sensor. Registration itself delivers the current reading
   * for on-change sensors, so the first values land without waiting for the room to change.
   */
  fun start() {
    if (running) return
    val m = manager ?: return
    val kinds = available()
    if (kinds.isEmpty()) {
      Log.i(TAG, "no ambient sensors on this Portal")
      return
    }
    running = true
    lastValue.clear()
    lastPublishMs.clear()
    kinds.forEach { kind ->
      val sensor = runCatching { m.getDefaultSensor(kind.sensorType) }.getOrNull() ?: return@forEach
      runCatching { m.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL) }
          .onFailure { Log.w(TAG, "couldn't register ${kind.key}", it) }
    }
    Log.i(TAG, "ambient sensors started: ${kinds.joinToString { it.key }}")
  }

  fun stop() {
    if (!running) return
    running = false
    runCatching { manager?.unregisterListener(this) }
    lastValue.clear()
    lastPublishMs.clear()
    Log.i(TAG, "ambient sensors stopped")
  }

  override fun onSensorChanged(event: SensorEvent) {
    if (!running) return
    val kind = AmbientKind.values().firstOrNull { it.sensorType == event.sensor.type } ?: return
    val raw = event.values.firstOrNull()?.toDouble() ?: return
    if (raw.isNaN() || raw.isInfinite()) return
    val value =
        if (kind == AmbientKind.TEMPERATURE) {
          AmbientPolicy.calibrate(raw, MqttConfig.tempOffset(appContext))
        } else {
          raw
        }
    val now = System.currentTimeMillis()
    if (!AmbientPolicy.shouldPublish(
        lastValue[kind], value, lastPublishMs[kind] ?: 0L, now, kind.minDelta)) {
      return
    }
    lastValue[kind] = value
    lastPublishMs[kind] = now
    runCatching { onValue(kind, AmbientPolicy.format(value, kind.decimals)) }
        .onFailure { Log.w(TAG, "publishing ${kind.key} failed", it) }
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

  private companion object {
    const val TAG = "ImmortalSensors"
  }
}
