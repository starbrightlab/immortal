/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.ComponentName
import android.content.Context
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Persisted home-screen widget placements owned by Immortal's home grid. */
object HomeWidgetStore {

  private const val PREFS = "immortal_home_widgets"
  private const val KEY = "widgets"

  data class HomeWidget(
      val appWidgetId: Int = 0,
      val providerPackage: String = "",
      val providerClass: String = "",
      val spanX: Int = DEFAULT_SPAN_X,
      val spanY: Int = DEFAULT_SPAN_Y,
      val kind: String = KIND_APP_WIDGET,
      val customId: String = "",
  ) {
    val provider: ComponentName
      get() = ComponentName(providerPackage, providerClass)

    val key: String
      get() = if (kind == KIND_APP_WIDGET) "widget:$appWidgetId" else "custom:$customId"

    val isAppWidget: Boolean
      get() = kind == KIND_APP_WIDGET
  }

  const val KIND_APP_WIDGET = "appwidget"
  const val KIND_WEATHER = "weather"
  const val KIND_WORLD_CLOCK = "world_clock"
  const val KIND_WORLD_CLOCK_DIGITAL = "world_clock_digital"
  const val KIND_TIMERS = "timers"

  const val DEFAULT_SPAN_X = 2
  const val DEFAULT_SPAN_Y = 2
  const val MAX_SPAN = 4

  fun load(context: Context): List<HomeWidget> {
    val raw =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
    return deserialize(raw)
  }

  fun save(context: Context, widgets: List<HomeWidget>) {
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY, serialize(widgets))
        .apply()
  }

  fun custom(kind: String, spanX: Int = DEFAULT_SPAN_X, spanY: Int = DEFAULT_SPAN_Y): HomeWidget =
      HomeWidget(
          kind = kind,
          customId = "$kind:${UUID.randomUUID()}",
          spanX = normalizeSpan(spanX),
          spanY = normalizeSpan(spanY),
      )

  fun withAdded(widgets: List<HomeWidget>, widget: HomeWidget): List<HomeWidget> =
      (widgets.filterNot { it.key == widget.key } + widget).distinctBy { it.key }

  fun without(widgets: List<HomeWidget>, key: String): List<HomeWidget> =
      widgets.filterNot { it.key == key }

  fun normalizeSpan(span: Int): Int = span.coerceIn(1, MAX_SPAN)

  internal fun serialize(widgets: List<HomeWidget>): String {
    val arr = JSONArray()
    widgets.forEach { w ->
      arr.put(
          JSONObject()
              .put("id", w.appWidgetId)
              .put("package", w.providerPackage)
              .put("class", w.providerClass)
              .put("spanX", normalizeSpan(w.spanX))
              .put("spanY", normalizeSpan(w.spanY))
              .put("kind", w.kind)
              .put("customId", w.customId))
    }
    return arr.toString()
  }

  internal fun deserialize(raw: String): List<HomeWidget> =
      runCatching {
            val arr = JSONArray(raw)
            buildList {
                  for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optInt("id", -1)
                    val pkg = obj.optString("package")
                    val cls = obj.optString("class")
                    val kind = obj.optString("kind", KIND_APP_WIDGET)
                    val customId = obj.optString("customId")
                    if (kind == KIND_APP_WIDGET && (id <= 0 || pkg.isBlank() || cls.isBlank())) {
                      continue
                    }
                    if (kind != KIND_APP_WIDGET && customId.isBlank()) continue
                    add(
                        HomeWidget(
                            appWidgetId = id,
                            providerPackage = pkg,
                            providerClass = cls,
                            spanX = normalizeSpan(obj.optInt("spanX", DEFAULT_SPAN_X)),
                            spanY = normalizeSpan(obj.optInt("spanY", DEFAULT_SPAN_Y)),
                            kind = kind,
                            customId = customId,
                        ))
                  }
                }
                .distinctBy { it.key }
          }
          .getOrDefault(emptyList())
}
