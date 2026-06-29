/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.util.Calendar

/**
 * Romanian (Orthodox) name-day calendar — "onomastica". In Romania and much of
 * Eastern Europe a person's name-day (ziua onomastică / ziua numelui) is celebrated
 * like a second birthday, on the feast of the saint they're named after.
 *
 * This is the set of the widely-celebrated fixed-date name-days; it isn't every
 * entry in the full church calendar, but it covers the names people actually mark.
 * Keyed by "month-day" (1-based month). [forToday] returns today's names, or empty.
 */
object NameDays {

  // month*100 + day -> celebrated names on that date.
  private val TABLE: Map<Int, List<String>> = mapOf(
      101 to listOf("Vasile", "Vasilica"),
      106 to listOf("Iordan", "Iordana"),
      107 to listOf("Ion", "Ioan", "Ioana", "Ionel", "Ionela", "Ionuț", "Nelu"),
      117 to listOf("Antonie", "Antonia"),
      118 to listOf("Atanasie", "Tanase"),
      125 to listOf("Grigore", "Grigorina"),
      130 to listOf("Vasile", "Grigore", "Ion"), // Sf. Trei Ierarhi
      202 to listOf("Marin", "Marina"),
      210 to listOf("Haralambie"),
      217 to listOf("Tudor", "Teodor", "Teodora"),
      301 to listOf("Albert", "Albertina"),
      309 to listOf("Mucenic"),
      325 to listOf("Maria"), // Buna Vestire
      323 to listOf("Toma"),
      404 to listOf("Gheorghe"),
      423 to listOf("Gheorghe", "George", "Gheorghița", "Geta"),
      425 to listOf("Marcu"),
      508 to listOf("Ioan"),
      521 to listOf("Constantin", "Elena", "Costel", "Costică", "Lenuța"),
      522 to listOf("Emil", "Emilia"),
      601 to listOf("Iustin"),
      608 to listOf("Cosmin", "Cosmina"),
      624 to listOf("Ioan"), // Sânziene / Nașterea Sf. Ioan Botezătorul
      629 to listOf("Petru", "Pavel", "Petre", "Petrică", "Paul", "Paula"),
      630 to listOf("Apostol"),
      701 to listOf("Cosma", "Damian"),
      708 to listOf("Procopie"),
      715 to listOf("Vladimir", "Vlad"),
      720 to listOf("Ilie", "Ilinca"),
      725 to listOf("Ana", "Anca", "Anuța"),
      726 to listOf("Veronica"),
      727 to listOf("Pantelimon"),
      801 to listOf("Macabei"),
      806 to listOf("Schimbarea la Față"),
      815 to listOf("Maria", "Marioara", "Mărioara", "Maricica"),
      830 to listOf("Alexandru", "Alexandra", "Sandu", "Sanda"),
      901 to listOf("Dragoș"),
      908 to listOf("Maria", "Adelina"), // Nașterea Maicii Domnului
      909 to listOf("Ioachim", "Ana"),
      914 to listOf("Cruce"), // Înălțarea Sfintei Cruci
      917 to listOf("Sofia", "Credința", "Speranța", "Iubirea"),
      923 to listOf("Tecla"),
      926 to listOf("Ioan"),
      1014 to listOf("Paraschiva", "Parascheva"),
      1023 to listOf("Iacob"),
      1026 to listOf("Dumitru", "Dumitra", "Mitică", "Mitruț"),
      1027 to listOf("Nestor"),
      1108 to listOf("Mihai", "Mihaela", "Gabriel", "Gabriela", "Mihail", "Mișu"),
      1111 to listOf("Mina"),
      1113 to listOf("Ioan"),
      1114 to listOf("Filip"),
      1116 to listOf("Matei"),
      1121 to listOf("Maria"), // Intrarea în Biserică a Maicii Domnului
      1125 to listOf("Ecaterina", "Caterina", "Cătălin", "Cătălina"),
      1130 to listOf("Andrei", "Andreea"),
      1204 to listOf("Varvara", "Barbara"),
      1205 to listOf("Sava"),
      1206 to listOf("Nicolae", "Nicoleta", "Niculina", "Nicu", "Nicușor"),
      1209 to listOf("Ana"),
      1212 to listOf("Spiridon"),
      1215 to listOf("Eleftérie"),
      1217 to listOf("Daniel", "Daniela", "Dan", "Dana"),
      1220 to listOf("Ignat"),
      1225 to listOf("Crăciun"), // Nașterea Domnului
      1226 to listOf("Emanuel", "Emanuela"),
      1227 to listOf("Ștefan", "Ștefania", "Fănica"),
  )

  /** Names celebrated on [month] (1-12) / [day], or empty if none well-known. */
  fun forDate(month: Int, day: Int): List<String> = TABLE[month * 100 + day] ?: emptyList()

  /** Names celebrated today. */
  fun forToday(now: Calendar = Calendar.getInstance()): List<String> =
      forDate(now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))

  /** A compact display string for today, e.g. "Nicolae, Nicoleta", or "". */
  fun todayLabel(now: Calendar = Calendar.getInstance()): String = forToday(now).joinToString(", ")
}
