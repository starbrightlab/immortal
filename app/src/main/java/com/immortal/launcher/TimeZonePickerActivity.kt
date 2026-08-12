/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.SampleAppTheme
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Search every timezone the device knows about and add one to the world clock. The curated city
 * list in the world-clock screen covers the common cases; this is the escape hatch for everywhere
 * else, so no one is stuck because their city isn't on a hand-written list.
 */
class TimeZonePickerActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { TimeZonePickerScreen(onBack = { finish() }) } }
  }
}

/** Every zone id, minus the legacy aliases that would just clutter the list. */
private fun allZoneIds(): List<String> =
    TimeZone.getAvailableIDs()
        .filter { it.contains('/') && !it.startsWith("SystemV/") && !it.startsWith("Etc/") }
        .sorted()

/**
 * Cities people search for that aren't zone names. IANA names each zone after one representative
 * city, so searching "Seattle" or "Manchester" finds nothing even though both are perfectly ordinary
 * places. They sit inside America/Los_Angeles and Europe/London. This maps the common ones onto
 * their zone, and picking one names the clock after the city you actually searched for.
 *
 * Only cities that genuinely share the target zone are listed. Anywhere with a zone of its own
 * (Melbourne, Monterrey, Cancún) is already findable and deliberately absent.
 */
private val CITY_ALIASES: Map<String, String> =
    mapOf(
        // North America
        "Seattle" to "America/Los_Angeles",
        "San Francisco" to "America/Los_Angeles",
        "San Diego" to "America/Los_Angeles",
        "Portland" to "America/Los_Angeles",
        "Las Vegas" to "America/Los_Angeles",
        "Salt Lake City" to "America/Denver",
        "Houston" to "America/Chicago",
        "Dallas" to "America/Chicago",
        "Austin" to "America/Chicago",
        "New Orleans" to "America/Chicago",
        "Nashville" to "America/Chicago",
        "Boston" to "America/New_York",
        "Philadelphia" to "America/New_York",
        "Washington DC" to "America/New_York",
        "Atlanta" to "America/New_York",
        "Miami" to "America/New_York",
        "Orlando" to "America/New_York",
        "Pittsburgh" to "America/New_York",
        "Ottawa" to "America/Toronto",
        "Calgary" to "America/Edmonton",
        "Victoria" to "America/Vancouver",
        // UK & Ireland
        "Manchester" to "Europe/London",
        "Birmingham" to "Europe/London",
        "Glasgow" to "Europe/London",
        "Edinburgh" to "Europe/London",
        "Liverpool" to "Europe/London",
        "Leeds" to "Europe/London",
        "Bristol" to "Europe/London",
        "Cardiff" to "Europe/London",
        "Belfast" to "Europe/London",
        "Newcastle" to "Europe/London",
        "Sheffield" to "Europe/London",
        "Brighton" to "Europe/London",
        "Oxford" to "Europe/London",
        "Cambridge" to "Europe/London",
        "Cork" to "Europe/Dublin",
        "Galway" to "Europe/Dublin",
        // South America
        "Rio de Janeiro" to "America/Sao_Paulo",
        "Brasilia" to "America/Sao_Paulo",
        "Belo Horizonte" to "America/Sao_Paulo",
        "Medellin" to "America/Bogota",
        "Guadalajara" to "America/Mexico_City",
        "Puebla" to "America/Mexico_City",
        // Europe
        "Munich" to "Europe/Berlin",
        "Frankfurt" to "Europe/Berlin",
        "Hamburg" to "Europe/Berlin",
        "Cologne" to "Europe/Berlin",
        "Stuttgart" to "Europe/Berlin",
        "Lyon" to "Europe/Paris",
        "Marseille" to "Europe/Paris",
        "Nice" to "Europe/Paris",
        "Bordeaux" to "Europe/Paris",
        "Nantes" to "Europe/Paris",
        "Lille" to "Europe/Paris",
        "Barcelona" to "Europe/Madrid",
        "Valencia" to "Europe/Madrid",
        "Seville" to "Europe/Madrid",
        "Malaga" to "Europe/Madrid",
        "Bilbao" to "Europe/Madrid",
        "Milan" to "Europe/Rome",
        "Naples" to "Europe/Rome",
        "Florence" to "Europe/Rome",
        "Venice" to "Europe/Rome",
        "Turin" to "Europe/Rome",
        "Bologna" to "Europe/Rome",
        "Genoa" to "Europe/Rome",
        "Dortmund" to "Europe/Berlin",
        "Leipzig" to "Europe/Berlin",
        "Dresden" to "Europe/Berlin",
        "Aarhus" to "Europe/Copenhagen",
        "Tampere" to "Europe/Helsinki",
        "Cluj" to "Europe/Bucharest",
        "Varna" to "Europe/Sofia",
        "Thessaloniki" to "Europe/Athens",
        "Kazan" to "Europe/Moscow",
        "Porto" to "Europe/Lisbon",
        "Rotterdam" to "Europe/Amsterdam",
        "The Hague" to "Europe/Amsterdam",
        "Utrecht" to "Europe/Amsterdam",
        "Antwerp" to "Europe/Brussels",
        "Ghent" to "Europe/Brussels",
        "Geneva" to "Europe/Zurich",
        "Basel" to "Europe/Zurich",
        "Bern" to "Europe/Zurich",
        "Salzburg" to "Europe/Vienna",
        "Krakow" to "Europe/Warsaw",
        "Gdansk" to "Europe/Warsaw",
        "Gothenburg" to "Europe/Stockholm",
        "Bergen" to "Europe/Oslo",
        "Saint Petersburg" to "Europe/Moscow",
        "Ankara" to "Europe/Istanbul",
        "Izmir" to "Europe/Istanbul",
        // Africa & Middle East
        "Cape Town" to "Africa/Johannesburg",
        "Durban" to "Africa/Johannesburg",
        "Pretoria" to "Africa/Johannesburg",
        "Alexandria" to "Africa/Cairo",
        "Marrakesh" to "Africa/Casablanca",
        "Abu Dhabi" to "Asia/Dubai",
        "Jeddah" to "Asia/Riyadh",
        "Mecca" to "Asia/Riyadh",
        "Tel Aviv" to "Asia/Jerusalem",
        // The zone is Atlantic/Stanley; the place is usually written Port Stanley.
        "Port Stanley" to "Atlantic/Stanley",
        // Asia & Oceania
        "Mumbai" to "Asia/Kolkata",
        "Delhi" to "Asia/Kolkata",
        "New Delhi" to "Asia/Kolkata",
        "Bangalore" to "Asia/Kolkata",
        "Bengaluru" to "Asia/Kolkata",
        "Chennai" to "Asia/Kolkata",
        "Hyderabad" to "Asia/Kolkata",
        "Pune" to "Asia/Kolkata",
        "Lahore" to "Asia/Karachi",
        "Islamabad" to "Asia/Karachi",
        "Rawalpindi" to "Asia/Karachi",
        "Osaka" to "Asia/Tokyo",
        "Kyoto" to "Asia/Tokyo",
        "Yokohama" to "Asia/Tokyo",
        "Nagoya" to "Asia/Tokyo",
        "Sapporo" to "Asia/Tokyo",
        "Fukuoka" to "Asia/Tokyo",
        "Kobe" to "Asia/Tokyo",
        "Busan" to "Asia/Seoul",
        "Incheon" to "Asia/Seoul",
        "Daegu" to "Asia/Seoul",
        // China keeps one timezone nationwide, so every Chinese city is Asia/Shanghai.
        "Beijing" to "Asia/Shanghai",
        "Shenzhen" to "Asia/Shanghai",
        "Guangzhou" to "Asia/Shanghai",
        "Wuhan" to "Asia/Shanghai",
        "Chengdu" to "Asia/Shanghai",
        "Hangzhou" to "Asia/Shanghai",
        "Tianjin" to "Asia/Shanghai",
        "Nanjing" to "Asia/Shanghai",
        "Qingdao" to "Asia/Shanghai",
        "Xi'an" to "Asia/Shanghai",
        "Kaohsiung" to "Asia/Taipei",
        // Vietnam is a single zone; the IANA zone is named after Ho Chi Minh City.
        "Hanoi" to "Asia/Ho_Chi_Minh",
        "Da Nang" to "Asia/Ho_Chi_Minh",
        "Chiang Mai" to "Asia/Bangkok",
        "Phuket" to "Asia/Bangkok",
        "Cebu" to "Asia/Manila",
        "Surabaya" to "Asia/Jakarta",
        "Bandung" to "Asia/Jakarta",
        "Wellington" to "Pacific/Auckland",
        "Christchurch" to "Pacific/Auckland",
        "Dunedin" to "Pacific/Auckland",
        "Queenstown" to "Pacific/Auckland",
        "Canberra" to "Australia/Sydney",
        "Wollongong" to "Australia/Sydney",
        // "Newcastle" alone goes to the English one; the Australian namesake is spelled out.
        "Newcastle NSW" to "Australia/Sydney",
        "Gold Coast" to "Australia/Brisbane",
        "Cairns" to "Australia/Brisbane",
        "Geelong" to "Australia/Melbourne",
    )

/** One row of search results: a zone, plus the city name that found it. */
private data class ZoneHit(val zone: String, val title: String, val viaAlias: Boolean)

/**
 * Country and territory name → its zones, built from the device's own ICU data rather than a
 * hand-written list, so it stays right as the tz database changes and covers everywhere at once.
 *
 * This is what makes searching by country work. Zone ids only ever name a city, so "Vietnam" finds
 * nothing on its own even though Asia/Ho_Chi_Minh is sitting right there, and the Falkland Islands
 * hide behind Atlantic/Stanley. Multi-zone countries list all of theirs, so "Australia" or "Brazil"
 * shows the full set to choose from.
 */
private fun countryZones(): List<Pair<String, List<String>>> =
    Locale.getISOCountries().mapNotNull { cc ->
      val zones =
          runCatching { android.icu.util.TimeZone.getAvailableIDs(cc) }
              .getOrNull()
              ?.filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
              ?.sorted()
              .orEmpty()
      val name = Locale("", cc).displayCountry
      if (zones.isEmpty() || name.isBlank() || name.equals(cc, ignoreCase = true)) null
      else name to zones
    }

@Composable
private fun TimeZonePickerScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val all = remember { allZoneIds() }
  val countries = remember { countryZones() }
  var query by remember { mutableStateOf("") }
  var selected by remember { mutableStateOf(ImmortalSettings.worldClockZones(context)) }
  val (_, initialFocus) = rememberInitialFocus()
  val now = remember { Date() }

  BackHandler { onBack() }

  // Match on the zone id (so "auck", "pacific" and "Pacific/Auckland" all land) and on the alias
  // cities, so somewhere like Seattle, a real place with no zone of its own, is still findable.
  val matches =
      remember(query, all, countries) {
        val q = query.trim().lowercase(Locale.getDefault())
        if (q.isEmpty()) {
          all.map { ZoneHit(it, ImmortalSettings.cityFromZoneId(it), viaAlias = false) }.take(60)
        } else {
          val byZone =
              all.filter { it.lowercase(Locale.getDefault()).contains(q) }
                  .map { ZoneHit(it, ImmortalSettings.cityFromZoneId(it), viaAlias = false) }
          val byAlias =
              CITY_ALIASES.filter { (city, _) -> city.lowercase(Locale.getDefault()).contains(q) }
                  .map { (city, zone) -> ZoneHit(zone, city, viaAlias = true) }
          val byCountry =
              countries
                  .filter { (name, _) -> name.lowercase(Locale.getDefault()).contains(q) }
                  .flatMap { (_, zones) ->
                    zones.map { ZoneHit(it, ImmortalSettings.cityFromZoneId(it), viaAlias = false) }
                  }
          // Alias hits first: someone typing "seattle" wants Seattle, not every zone whose id
          // happens to contain those letters. Country hits last, since they are the broadest match.
          (byAlias + byZone + byCountry).distinctBy { it.zone to it.title }.take(60)
        }
      }

  Column(
      modifier =
          Modifier.fillMaxSize()
              .background(Color(0xFF101012))
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 28.dp, vertical = 32.dp),
  ) {
    Column(modifier = Modifier.widthIn(max = 1100.dp).focusGroup()) {
      Text("Add a timezone", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
      Text(
          "Search for a city or a country, then pick one to add to the world clock.",
          color = Color(0xFF9A9A9A),
          fontSize = 16.sp,
          modifier = Modifier.padding(top = 6.dp),
      )

      Spacer(Modifier.size(20.dp))

      OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          placeholder = { Text("auckland", color = Color(0xFF777777)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).then(initialFocus),
          shape = RoundedCornerShape(14.dp),
      )

      Text(
          if (query.isBlank()) "${all.size} timezones. Start typing to narrow it down."
          else "${matches.size} shown",
          color = Color(0xFF7C7C7C),
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 10.dp, start = 4.dp),
      )
      Text(
          "Places without a timezone of their own still work. Seattle gives you Los Angeles time, " +
              "named Seattle.",
          color = Color(0xFF7C7C7C),
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 4.dp, start = 4.dp),
      )

      Spacer(Modifier.size(18.dp))

      Card {
        matches.forEachIndexed { i, hit ->
          if (i > 0) Divider()
          val on = hit.zone in selected
          Row(
              modifier =
                  Modifier.fillMaxWidth()
                      .tvFocusableRow {
                        if (!on) {
                          selected = selected + hit.zone
                          ImmortalSettings.setWorldClockZones(context, selected)
                        }
                        // Picking "Seattle" should give you a clock that says Seattle, not one that
                        // says Los Angeles. Never overwrite a name the user set themselves.
                        if (hit.viaAlias &&
                            ImmortalSettings.worldClockLabels(context)[hit.zone] == null) {
                          ImmortalSettings.setWorldClockLabel(context, hit.zone, hit.title)
                        }
                        onBack()
                      }
                      .padding(horizontal = 18.dp, vertical = 14.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(hit.title, color = Color.White, fontSize = 16.sp)
              Text(
                  if (hit.viaAlias) "${ImmortalSettings.cityFromZoneId(hit.zone)} time · ${hit.zone}"
                  else hit.zone,
                  color = Color(0xFF9A9A9A),
                  fontSize = 13.sp,
                  modifier = Modifier.padding(top = 2.dp),
              )
            }
            Text(
                if (on) "added" else offsetLabel(hit.zone, now),
                color = if (on) Color(0xFF8AB4F8) else Color(0xFF7C7C7C),
                fontSize = 13.sp,
            )
          }
        }
      }

      if (matches.isEmpty()) {
        Text(
            "Nothing matches \"${query.trim()}\". Try a city like Auckland, or a country like Vietnam.",
            color = Color(0xFF9A9A9A),
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 4.dp, start = 4.dp),
        )
      }

      Spacer(Modifier.size(18.dp))
      Text(
          "Press Back to return without adding anything.",
          color = Color(0xFF7C7C7C),
          fontSize = 13.sp,
          modifier = Modifier.padding(start = 4.dp),
      )
    }
  }
}

/** Offset of [zone] against this Portal's own clock, e.g. "+12h" / "-5h" / "same". */
private fun offsetLabel(zone: String, now: Date): String {
  val diffH =
      (TimeZone.getTimeZone(zone).getOffset(now.time) - TimeZone.getDefault().getOffset(now.time)) /
          3_600_000
  return when {
    diffH == 0 -> "same"
    diffH > 0 -> "+${diffH}h"
    else -> "${diffH}h"
  }
}
