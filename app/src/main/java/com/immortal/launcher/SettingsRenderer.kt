/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.settings.BoolSpec
import com.immortal.launcher.settings.DerivedSpec
import com.immortal.launcher.settings.EnumSpec
import com.immortal.launcher.settings.Entry
import com.immortal.launcher.settings.IntSpec
import com.immortal.launcher.settings.NavSpec
import com.immortal.launcher.settings.SettingSpec
import com.immortal.launcher.settings.SettingsDomain
import com.immortal.launcher.settings.StringSpec
import org.json.JSONObject

/**
 * The on-device counterpart to the phone-remote's generic settings renderer: turns a
 * [SettingsDomain] into Compose rows built on the shared [SettingsComponents], so the on-device
 * screens render from the SAME registry that drives the remote and persistence. Stateless — the
 * caller owns the snapshot and reloads it after each [onApply] (which routes through the registry's
 * `apply`, so the domain's `onApplied` side effects fire on the on-device path too).
 *
 * Specs render by type: bool→toggle, enum→segmented, int→stepper, string(nav)/NavSpec→nav row into
 * a bespoke Activity, derived→skipped. Controls are grouped into the domain's `sections`, each in a
 * card, with the spec's `help` shown beneath it.
 */
@Composable
fun <S> SettingsList(
    domain: SettingsDomain<S>,
    snapshot: S,
    exclude: Set<String> = emptySet(),
    onApply: (key: String, value: Any) -> Unit,
) {
  val context = LocalContext.current
  val visible = domain.specs.filter { it.key !in exclude && it.visibleWhen(context, snapshot) }
  val groups = LinkedHashMap<String?, MutableList<SettingSpec<S>>>()
  visible.forEach { groups.getOrPut(domain.sections[it.key]) { mutableListOf() }.add(it) }
  groups.forEach { (section, specs) ->
    if (section != null) SectionLabel(section)
    Card {
      specs.forEachIndexed { i, spec ->
        if (i > 0) Divider()
        SettingControl(spec, snapshot, onApply)
      }
    }
    Spacer(Modifier.size(22.dp))
  }
}

@Composable
private fun <S> SettingControl(spec: SettingSpec<S>, snapshot: S, onApply: (String, Any) -> Unit) {
  val context = LocalContext.current
  @Suppress("UNCHECKED_CAST")
  when (spec) {
    is BoolSpec<*> -> {
      val s = spec as BoolSpec<S>
      Column {
        ToggleRow(s.title, s.get(snapshot)) { onApply(s.key, it) }
        HelpText(s.help)
      }
    }
    is EnumSpec<*> -> {
      val s = spec as EnumSpec<S>
      Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(s.title, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
          // EnumSpec.options is value→label; Segmented wants label→value.
          Segmented(s.options.map { (v, l) -> l to v }, s.get(snapshot)) { onApply(s.key, it) }
        }
        HelpText(s.help)
      }
    }
    is IntSpec<*> -> {
      val s = spec as IntSpec<S>
      val v = s.get(snapshot)
      Column {
        Stepper(
            label = s.title,
            valueText = s.format(v),
            widthMin = 72.dp,
            onMinus = { onApply(s.key, v - s.step) },
            onPlus = { onApply(s.key, v + s.step) },
        )
        HelpText(s.help)
      }
    }
    is StringSpec<*> -> {
      val s = spec as StringSpec<S>
      val entry = s.entry
      if (entry is Entry.Nav) {
        Column {
          NavRow(s.title, s.get(snapshot).ifBlank { "Not set" }) {
            context.startActivity(Intent(context, entry.activity))
          }
          HelpText(s.help)
        }
      }
      // An inline string on a D-pad screen is rare; the on-device screens that need free text use a
      // dedicated entry Activity (Entry.Nav). An Inline string here renders nothing for now.
    }
    is NavSpec<*> -> {
      val s = spec as NavSpec<S>
      Column {
        NavRow(s.title, s.value(context, snapshot)) {
          context.startActivity(Intent(context, s.activity))
        }
        HelpText(s.help)
      }
    }
    is DerivedSpec<*> -> {} // read-only/flat-only — not rendered on-device
  }
}

/** The grey description shown beneath a control (the spec's `help`), matching the screens' style. */
@Composable
private fun HelpText(help: String?) {
  if (help.isNullOrBlank()) return
  Text(
      help,
      color = Color(0xFF9A9A9A),
      fontSize = 13.sp,
      modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
  )
}
