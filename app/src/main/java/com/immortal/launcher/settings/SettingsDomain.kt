/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher.settings

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A coherent group of [SettingSpec]s over one config snapshot type `S` (e.g. the screensaver or the
 * calendar). A domain is the unit of remote exposure and on-device rendering. It folds its specs
 * three ways:
 *  - [flatJson] — the flat `{key:value}` wire payload (what the legacy `Fleet*` serializers emit;
 *    the façades delegate here so existing tests stay green).
 *  - [schemaJson] — the generic, self-describing schema (controls + metadata) a renderer builds from.
 *  - [apply] — a partial, validated write: each present key is applied through its spec, then
 *    [onApplied] fires ONCE with the set of applied keys (so a domain's side effects — reaffirming
 *    the dream, reconnecting MQTT, etc. — run a single time per batch, not per key).
 */
class SettingsDomain<S>(
    val id: String,
    val title: String,
    val load: (Context) -> S,
    val specs: List<SettingSpec<S>>,
    /** Optional `controlKey -> sub-group label`, so a renderer can break a long list into sections. */
    val sections: Map<String, String> = emptyMap(),
    /** Optional factory for a fresh, all-defaults snapshot — lets the schema advertise each
     *  control's `default` so a client can offer "reset to defaults". */
    val defaults: (() -> S)? = null,
    /** When true, the on-device renderer batches edits behind an explicit Apply (e.g. MQTT). */
    val explicitApply: Boolean = false,
    val onApplied: (Context, Set<String>) -> Unit = { _, _ -> },
) {
  /** The flat legacy wire payload — every spec's current value. Pure over the snapshot. */
  fun flatJson(s: S): JSONObject {
    val out = JSONObject()
    specs.forEach { it.putValue(out, s) }
    return out
  }

  fun flatJson(c: Context): JSONObject = flatJson(load(c))

  /** The self-describing schema (visible controls + their metadata/values) for a generic renderer. */
  fun schemaJson(c: Context): JSONObject {
    val s = load(c)
    val defJson = defaults?.let { flatJson(it()) }
    val controls = JSONArray()
    specs.forEach { spec ->
      if (spec.visibleWhen(c, s))
          spec.metaJson(c, s)?.let { meta ->
            sections[spec.key]?.let { meta.put("section", it) }
            if (defJson != null && defJson.has(spec.key)) meta.put("default", defJson.get(spec.key))
            controls.put(meta)
          }
    }
    return JSONObject().put("id", id).put("title", title).put("controls", controls)
  }

  /**
   * Apply every present key, then fire [onApplied] once. Returns the set of applied keys. Serialized
   * per-domain so two concurrent applies (two remote clients, or remote + on-device) can't interleave
   * their writes or have the post-apply read-back reflect a different writer's half-applied state.
   */
  fun apply(c: Context, body: JSONObject): Set<String> =
      synchronized(this) {
        val applied = applyValues(c, body)
        if (applied.isNotEmpty()) onApplied(c, applied)
        applied
      }

  /**
   * Apply every present key WITHOUT firing [onApplied]. For callers that already run their own
   * side effects (e.g. `FleetScreensaver.apply`, whose route handlers call `afterScreensaverApply`
   * themselves) — delegating their value writes here unifies the spec definitions without
   * double-firing the domain's hook. Most callers want [apply].
   */
  fun applyValues(c: Context, body: JSONObject): Set<String> =
      synchronized(this) {
        val applied = LinkedHashSet<String>()
        specs.forEach { if (it.applyFrom(c, body)) applied.add(it.key) }
        applied
      }
}

/**
 * The single registry of every settings domain — the one source of truth the persistence layer,
 * the on-device settings UI, and the phone-remote PWA all read from. Domains are registered in
 * [SettingsDomains].
 */
object SettingsRegistry {
  val domains: List<SettingsDomain<*>>
    get() = SettingsDomains.all

  fun domain(id: String): SettingsDomain<*>? = domains.firstOrNull { it.id == id }

  /** Every domain's schema, for a client that renders all settings generically. */
  fun schemaJson(c: Context): JSONObject {
    val arr = JSONArray()
    domains.forEach { arr.put(it.schemaJson(c)) }
    return JSONObject().put("domains", arr)
  }

  /** Apply a body to one domain by id; null if no such domain. */
  fun apply(c: Context, domainId: String, body: JSONObject): Set<String>? =
      domain(domainId)?.apply(c, body)
}
