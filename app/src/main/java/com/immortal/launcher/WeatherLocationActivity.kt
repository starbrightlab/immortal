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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.SampleAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * City picker for the weather location (issue #177). IP geolocation often lands on the
 * ISP's city and the first answer is cached forever, so this screen lets the user
 * search Open-Meteo's keyless geocoder and pin the location — or wipe the cache and
 * re-detect automatically.
 */
class WeatherLocationActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { WeatherLocationScreen(onDone = { finish() }) } }
  }
}

@Composable
private fun WeatherLocationScreen(onDone: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var query by remember { mutableStateOf("") }
  var results by remember { mutableStateOf<List<Weather.Place>>(emptyList()) }
  var searching by remember { mutableStateOf(false) }
  var searched by remember { mutableStateOf(false) }
  var detecting by remember { mutableStateOf(false) }
  var currentLabel by remember { mutableStateOf(Weather.locationLabel(context)) }

  fun runSearch() {
    val q = query.trim()
    if (q.isEmpty() || searching) return
    searching = true
    scope.launch(Dispatchers.IO) {
      val found = Weather.searchPlaces(q)
      withContext(Dispatchers.Main) {
        results = found
        searching = false
        searched = true
      }
    }
  }

  BackHandler { onDone() }

  Column(
      modifier =
          Modifier.fillMaxSize()
              .background(Color(0xFF101012))
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 28.dp, vertical = 32.dp),
  ) {
    Column(modifier = Modifier.widthIn(max = 1100.dp)) {
      Text(
          "Weather location",
          color = Color.White,
          fontSize = 34.sp,
          fontWeight = FontWeight.SemiBold,
      )
      Text(
          "Used for the weather, forecast, sunrise & sunset times, and ISS passes. " +
              "Automatic detection uses your internet connection, which can be off by a city " +
              "or two — search for your city below to pin it.",
          color = Color(0xFF9A9A9A),
          fontSize = 16.sp,
          modifier = Modifier.padding(top = 6.dp),
      )

      Spacer(Modifier.heightIn(min = 18.dp))

      Text(
          "Current: $currentLabel",
          color = Color(0xFFBBBBBB),
          fontSize = 15.sp,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.padding(start = 4.dp),
      )

      Spacer(Modifier.heightIn(min = 18.dp))

      OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          placeholder = { Text("City name, e.g. Boston", color = Color(0xFF777777)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
          shape = RoundedCornerShape(14.dp),
      )

      Spacer(Modifier.heightIn(min = 12.dp))

      Surface(
          color = if (query.trim().isNotEmpty()) Color(0xFF2E6BE6) else Color(0xFF2A2A2C),
          shape = RoundedCornerShape(16.dp),
          modifier =
              Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) {
                runSearch()
              },
      ) {
        Text(
            if (searching) "Searching…" else "Search",
            color = if (query.trim().isNotEmpty()) Color.White else Color(0xFF777777),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth(),
        )
      }

      if (searched && results.isEmpty() && !searching) {
        Text(
            "No matches — try just the city name, without state or country.",
            color = Color(0xFFE89090),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 10.dp, start = 4.dp),
        )
      }

      results.forEach { place ->
        Spacer(Modifier.heightIn(min = 10.dp))
        Surface(
            color = Color(0xFF1C1C1E),
            shape = RoundedCornerShape(16.dp),
            modifier =
                Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) {
                  Weather.setManualLocation(context, place)
                  onDone()
                },
        ) {
          Text(
              place.label,
              color = Color.White,
              fontSize = 16.sp,
              modifier = Modifier.padding(vertical = 14.dp, horizontal = 18.dp).fillMaxWidth(),
          )
        }
      }

      Spacer(Modifier.heightIn(min = 26.dp))

      Surface(
          color = Color(0xFF1C1C1E),
          shape = RoundedCornerShape(16.dp),
          modifier =
              Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) {
                if (!detecting) {
                  detecting = true
                  Weather.redetectAutomatically(context)
                  currentLabel = "Detecting…"
                  scope.launch(Dispatchers.IO) {
                    Weather.coordinates(context) // re-resolves and re-caches from scratch
                    withContext(Dispatchers.Main) {
                      currentLabel = Weather.locationLabel(context)
                      detecting = false
                    }
                  }
                }
              },
      ) {
        Text(
            "Detect automatically instead",
            color = Color(0xFFDDDDDD),
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth(),
        )
      }

      Spacer(Modifier.heightIn(min = 12.dp))

      Surface(
          color = Color(0xFF1C1C1E),
          shape = RoundedCornerShape(16.dp),
          modifier =
              Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) {
                onDone()
              },
      ) {
        Text(
            "Done",
            color = Color(0xFFDDDDDD),
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth(),
        )
      }
    }
  }
}
