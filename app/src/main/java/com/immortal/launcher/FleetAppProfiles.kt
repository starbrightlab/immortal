/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Desired-state app profiles for one Portal.
 *
 * The device owns persistence and the install/remove boundary. A profile is
 * either absent, installed, or failed; `pending` means the desired state has not
 * been reconciled yet. Attempt history is intentionally bounded so repeated
 * operator retries cannot turn the agent into an unbounded diagnostic sink.
 */
object FleetAppProfiles {
    private const val PREFS = "fleet_app_profiles"
    private const val KEY_DESIRED = "desired"
    private const val KEY_REPORT = "report"
    internal const val MAX_PACKAGES = 128
    internal const val ACTION_INSTALL = "install"
    internal const val ACTION_REMOVE = "remove"
    internal const val STATE_PENDING = "pending"
    internal const val STATE_INSTALLED = "installed"
    internal const val STATE_FAILED = "failed"
    internal const val RESULT_ALREADY_ABSENT = "already_absent"
    internal const val RESULT_REMOVED = "removed"

    data class Profile(
        val packageName: String,
        val action: String,
        val apkUrl: String?,
        val attempts: Int,
        val lastAttemptAtMs: Long?,
        val state: String,
    )

    fun load(c: Context): List<Profile> {
        val desired = readObject(c, KEY_DESIRED)
        val report = readObject(c, KEY_REPORT)
        return desired.keys().asSequence().map { pkg ->
            val item = desired.getJSONObject(pkg)
            val action = normalizeAction(item.optString("action")) ?: ""
            Profile(
                packageName = pkg,
                action = action,
                apkUrl = item.optString("apkUrl").ifBlank { null },
                attempts = report.optJSONObject(pkg)?.optInt("attempts", 0) ?: 0,
                lastAttemptAtMs =
                    report.optJSONObject(pkg)?.optLong("lastAttemptAtMs", 0L)
                    ?.takeIf { it > 0 },
                state = stateFor(pkg, report),
            )
        }.sortedBy { it.packageName }.toList()
    }

    fun set(
        c: Context,
        packageName: String,
        action: String,
        apkUrl: String?,
        reconcile: (Profile) -> String,
    ): Pair<Profile, String> {
        val normalizedPackage = validatePackageName(packageName)
        val normalizedAction = normalizeAction(action)
            ?: throw IllegalArgumentException("invalid_action")

        val desired = readObject(c, KEY_DESIRED)
        val previous = desired.optJSONObject(normalizedPackage)
        if (normalizedAction == ACTION_REMOVE) {
            if (apkUrl != null) throw IllegalArgumentException("apk_url_not_allowed")
            if (!desired.has(normalizedPackage)) {
                return absentProfile(normalizedPackage) to RESULT_ALREADY_ABSENT
            }
            desired.put(
                normalizedPackage,
                JSONObject()
                    .put("action", normalizedAction)
                    .put("apkUrl", "")
            )
        } else {
            if (desired.length() >= MAX_PACKAGES && !desired.has(normalizedPackage)) {
                throw IllegalArgumentException("profile_limit_reached")
            }
            desired.put(
                normalizedPackage,
                JSONObject()
                    .put("action", normalizedAction)
                    .put("apkUrl", apkUrl ?: "")
            )
        }
        write(c, KEY_DESIRED, desired)

        // A different action or APK target is a new deployment contract. Old
        // attempts must not remain visible as though they described this request.
        if (previous != null &&
            (previous.optString("action") != normalizedAction ||
                previous.optString("apkUrl") != apkUrl.orEmpty())
        ) {
            clearAttempts(c, normalizedPackage)
        }

        val profile = load(c).first { it.packageName == normalizedPackage }
        val result = reconcile(profile)
        if (normalizedAction == ACTION_REMOVE && result == STATE_INSTALLED) {
            remove(c, normalizedPackage)
            return absentProfile(normalizedPackage) to RESULT_REMOVED
        }
        return profile to result
    }

    fun remove(c: Context, packageName: String): Boolean {
        validatePackageName(packageName)
        val desired = readObject(c, KEY_DESIRED)
        val existed = desired.has(packageName)
        desired.remove(packageName)
        write(c, KEY_DESIRED, desired)
        clearAttempts(c, packageName)
        return existed
    }

    /**
     * Reconcile an install profile through the same install helper used by
     * `/install`. This must be called from a Fleet HTTP worker thread because that
     * path intentionally blocks until installation reaches a terminal result.
     */
    fun reconcileInstall(
        routes: FleetRoutes,
        context: Context,
        profile: Profile,
    ): String {
        val app =
            if (profile.apkUrl != null) {
                syntheticUrlAppForProfile(profile.packageName, profile.apkUrl)
            } else {
                routes.currentCatalog.firstOrNull { it.packageName == profile.packageName }
                    ?: return if (routes.currentCatalog.isEmpty()) STATE_PENDING else STATE_FAILED
            }
        if (routes.installPaused()) return STATE_PENDING
        return when (routes.installApp(app)) {
            "installed" -> STATE_INSTALLED
            FleetRoutes.BUSY -> STATE_PENDING
            else -> STATE_FAILED
        }
    }

    fun reconcileRemove(context: Context, packageName: String): String =
        if (!StoreCatalog.isInstalled(context, packageName)) STATE_INSTALLED else STATE_FAILED

    fun recordAttempt(c: Context, packageName: String, state: String, nowMs: Long) {
        require(state in setOf(STATE_PENDING, STATE_INSTALLED, STATE_FAILED)) {
            "invalid_state"
        }
        val report = readObject(c, KEY_REPORT)
        val item = report.optJSONObject(packageName) ?: JSONObject()
        // Every reconciliation is observable work: busy attempts are counted too,
        // while their terminal state remains pending for later operator retry.
        item.put("attempts", item.optInt("attempts", 0) + 1)
        item.put("lastAttemptAtMs", nowMs)
        item.put("state", state)
        report.put(packageName, item)
        trimHistory(report)
        write(c, KEY_REPORT, report)
    }

    private fun stateFor(packageName: String, report: JSONObject): String {
        val item = report.optJSONObject(packageName)
        return item?.optString("state")?.takeIf { it.isNotBlank() } ?: STATE_PENDING
    }

    private fun clearAttempts(c: Context, packageName: String) {
        val report = readObject(c, KEY_REPORT)
        report.remove(packageName)
        write(c, KEY_REPORT, report)
    }

    private fun trimHistory(report: JSONObject) {
        if (report.length() <= MAX_PACKAGES * 2) return
        val removable = report.keys().asSequence().toList()
        for ((index, key) in removable.withIndex()) {
            if (report.length() <= MAX_PACKAGES) break
            report.remove(key)
        }
    }

    private fun readObject(c: Context, key: String): JSONObject =
        runCatching { JSONObject(prefs(c).getString(key, "{}") ?: "{}") }
            .getOrDefault(JSONObject())

    private fun write(c: Context, key: String, value: JSONObject) {
        prefs(c).edit().putString(key, value.toString()).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun absentProfile(packageName: String) = Profile(
        packageName = packageName,
        action = ACTION_REMOVE,
        apkUrl = null,
        attempts = 0,
        lastAttemptAtMs = null,
        state = STATE_INSTALLED,
    )

    internal fun validatePackageName(value: String): String {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 255) { "packageName_required" }
        require(trimmed.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '_' }) {
            "invalid_package_name"
        }
        return trimmed
    }

    internal fun normalizeAction(value: String): String? = when (value.trim().lowercase()) {
        ACTION_INSTALL -> ACTION_INSTALL
        ACTION_REMOVE -> ACTION_REMOVE
        else -> null
    }

    private fun syntheticUrlAppForProfile(packageName: String, apkUrl: String): CatalogApp =
        CatalogApp(
            name = packageName,
            packageName = packageName,
            source = "url",
            fdroidId = null,
            apkUrl = apkUrl,
            versionCode = null,
            description = "",
            category = "",
        )
}
