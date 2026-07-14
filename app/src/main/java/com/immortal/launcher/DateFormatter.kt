package com.immortal.launcher

import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DateFormatter {
    /**
     * Formats a date using a localized pattern based on the provided skeleton.
     * Capitalizes the first letter if the locale naturally produces a lowercase start
     * (which is common in French and some other languages for days/months).
     *
     * Example skeletons: "EEEMMMd" -> "EEE, MMM d" (US) or "EEE d MMM" (FR)
     * "EEEEMMMMd" -> "EEEE, MMMM d" (US) or "EEEE d MMMM" (FR)
     */
    fun format(date: Date, skeleton: String, locale: Locale = Locale.getDefault()): String {
        val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
        val raw = SimpleDateFormat(pattern, locale).format(date)
        
        // Capitalize the first letter of each word (Title Case) for a premium UI look.
        // This universally solves issues in locales (like French or Spanish) where 
        // days and months are normally lowercase, without hardcoding country rules.
        return raw.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        }
    }
}
