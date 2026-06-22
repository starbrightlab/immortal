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
 * One declarative setting. A spec binds to an existing `*Config` object: [putValue] reads the
 * current value from an already-loaded snapshot `S`, and [applyFrom] writes a pushed value through
 * the existing (clamping, side-effect-free) setter. A [SettingsDomain] folds its specs three ways —
 * [putValue] builds the flat legacy wire payload, [metaJson] builds the generic schema that drives
 * both the phone-remote PWA and (later) the on-device renderer, and [applyFrom] performs a partial,
 * validated apply. Storage is never reimplemented here, so the persisted SharedPreferences keys and
 * their clamps are untouched. See docs / the settings-standardization plan.
 */
sealed interface SettingSpec<S> {
  /** Canonical wire + UI key. */
  val key: String

  /** Whether this control should be shown, given the snapshot and the environment. */
  fun visibleWhen(c: Context, s: S): Boolean

  /** Put this spec's current value into [out] under its wire key. Pure (reads only the snapshot). */
  fun putValue(out: JSONObject, s: S)

  /**
   * The control's schema entry for a generic renderer (`{key,type,title,value,…constraints}`), or
   * null when this spec contributes a flat value but isn't itself a rendered control (e.g. a
   * read-only derived field that exists only for the legacy wire format).
   */
  fun metaJson(c: Context, s: S): JSONObject?

  /** Apply from a wire [body] iff this spec's [key] (or an alias) is present and valid. */
  fun applyFrom(c: Context, body: JSONObject): Boolean
}

/** How a [StringSpec] / group is edited on-device: in place, or by launching a dedicated screen. */
sealed interface Entry {
  object Inline : Entry

  data class Nav(val activity: Class<*>) : Entry
}

private fun Entry.wire(): String =
    when (this) {
      is Entry.Inline -> "inline"
      is Entry.Nav -> "nav"
    }

/** Pick the present wire key from [key] + [aliases], or null if none is in [body]. */
private fun firstPresent(key: String, aliases: List<String>, body: JSONObject): String? =
    (listOf(key) + aliases).firstOrNull { body.has(it) }

private fun JSONObject.withHelp(help: String?): JSONObject = apply { help?.let { put("help", it) } }

class BoolSpec<S>(
    override val key: String,
    val title: String,
    val get: (S) -> Boolean,
    val set: (Context, Boolean) -> Unit,
    val help: String? = null,
    /** Extra accepted write-keys (e.g. the calendar's `enabled` aliasing the `widgetOn` toggle). */
    val aliases: List<String> = emptyList(),
    val visible: (Context, S) -> Boolean = { _, _ -> true },
) : SettingSpec<S> {
  override fun visibleWhen(c: Context, s: S) = visible(c, s)

  override fun putValue(out: JSONObject, s: S) {
    out.put(key, get(s))
  }

  override fun metaJson(c: Context, s: S): JSONObject =
      JSONObject().put("key", key).put("type", "bool").put("title", title).withHelp(help).put("value", get(s))

  override fun applyFrom(c: Context, body: JSONObject): Boolean {
    val k = firstPresent(key, aliases, body) ?: return false
    set(c, body.optBoolean(k))
    return true
  }
}

class IntSpec<S>(
    override val key: String,
    val title: String,
    val get: (S) -> Int,
    val set: (Context, Int) -> Unit,
    val min: Int,
    val max: Int,
    val step: Int = 1,
    /** Time-of-day values wrap at the bounds; everything else clamps. Drives the UI stepper only. */
    val wrap: Boolean = false,
    val format: (Int) -> String = { it.toString() },
    val help: String? = null,
    val aliases: List<String> = emptyList(),
    val visible: (Context, S) -> Boolean = { _, _ -> true },
) : SettingSpec<S> {
  override fun visibleWhen(c: Context, s: S) = visible(c, s)

  override fun putValue(out: JSONObject, s: S) {
    out.put(key, get(s))
  }

  override fun metaJson(c: Context, s: S): JSONObject =
      JSONObject()
          .put("key", key)
          .put("type", "int")
          .put("title", title)
          .withHelp(help)
          .put("value", get(s))
          .put("display", format(get(s)))
          .put("min", min)
          .put("max", max)
          .put("step", step)
          .put("wrap", wrap)

  override fun applyFrom(c: Context, body: JSONObject): Boolean {
    val k = firstPresent(key, aliases, body) ?: return false
    set(c, body.optInt(k)) // the underlying setter clamps/wraps
    return true
  }
}

class EnumSpec<S>(
    override val key: String,
    val title: String,
    val get: (S) -> String,
    val set: (Context, String) -> Unit,
    /** Allowed values paired with display labels. */
    val options: List<Pair<String, String>>,
    /**
     * Map a pushed value to the value to store, or null to *skip* it (leave the setting unchanged).
     * Default applies the raw value and lets the setter clamp it — matching settings whose setter
     * normalises any input (e.g. calendar range/side). Pass a stricter coercer for settings that
     * should ignore an unrecognised value rather than flip on a typo (e.g. fit, presence mode).
     */
    val coerce: (String) -> String? = { it },
    val help: String? = null,
    val aliases: List<String> = emptyList(),
    val visible: (Context, S) -> Boolean = { _, _ -> true },
) : SettingSpec<S> {
  override fun visibleWhen(c: Context, s: S) = visible(c, s)

  override fun putValue(out: JSONObject, s: S) {
    out.put(key, get(s))
  }

  override fun metaJson(c: Context, s: S): JSONObject {
    val opts = JSONArray()
    options.forEach { (v, label) -> opts.put(JSONObject().put("value", v).put("label", label)) }
    return JSONObject()
        .put("key", key)
        .put("type", "enum")
        .put("title", title)
        .withHelp(help)
        .put("value", get(s))
        .put("options", opts)
  }

  override fun applyFrom(c: Context, body: JSONObject): Boolean {
    val k = firstPresent(key, aliases, body) ?: return false
    val v = coerce(body.optString(k)) ?: return false
    set(c, v)
    return true
  }
}

class StringSpec<S>(
    override val key: String,
    val title: String,
    val get: (S) -> String,
    val set: (Context, String) -> Unit,
    val secret: Boolean = false,
    val entry: Entry = Entry.Inline,
    /** Apply guard — e.g. only write when non-blank (the value keys that also flip a source). */
    val applyWhen: (String) -> Boolean = { true },
    val help: String? = null,
    val aliases: List<String> = emptyList(),
    val visible: (Context, S) -> Boolean = { _, _ -> true },
) : SettingSpec<S> {
  override fun visibleWhen(c: Context, s: S) = visible(c, s)

  override fun putValue(out: JSONObject, s: S) {
    out.put(key, get(s))
  }

  override fun metaJson(c: Context, s: S): JSONObject =
      JSONObject()
          .put("key", key)
          .put("type", "string")
          .put("title", title)
          .withHelp(help)
          .put("value", get(s))
          .put("secret", secret)
          .put("entry", entry.wire())

  override fun applyFrom(c: Context, body: JSONObject): Boolean {
    val k = firstPresent(key, aliases, body) ?: return false
    val v = body.optString(k)
    if (!applyWhen(v)) return false
    set(c, v)
    return true
  }
}

/**
 * A read-only computed field. Contributes a value to the flat wire payload (so the legacy format
 * round-trips) but is never written and renders, if at all, as a non-interactive value. [get] MUST
 * be pure over the snapshot and must never trigger a side-effecting/identity-minting read.
 */
class DerivedSpec<S>(
    override val key: String,
    /** Returns a JSON-ready value: Boolean, Int, String, or [JSONArray]. */
    val get: (S) -> Any,
    val title: String? = null,
    /** Whether to surface it in the schema at all (some derived fields are flat-payload-only). */
    val render: Boolean = false,
) : SettingSpec<S> {
  override fun visibleWhen(c: Context, s: S) = render

  override fun putValue(out: JSONObject, s: S) {
    out.put(key, get(s))
  }

  override fun metaJson(c: Context, s: S): JSONObject? {
    if (!render) return null
    return JSONObject()
        .put("key", key)
        .put("type", "info")
        .apply { title?.let { put("title", it) } }
        .put("value", get(s))
        .put("readOnly", true)
  }

  override fun applyFrom(c: Context, body: JSONObject): Boolean = false
}
