/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock

/** Validation rules and bounded reporting for desired-state reconciliation. */
class FleetAppProfilesTest {

  @Test
  fun normalizeAction_acceptsExactAndCasedActions() {
    assertEquals(FleetAppProfiles.ACTION_INSTALL, FleetAppProfiles.normalizeAction("install"))
    assertEquals(FleetAppProfiles.ACTION_INSTALL, FleetAppProfiles.normalizeAction(" Install "))
    assertEquals(FleetAppProfiles.ACTION_REMOVE, FleetAppProfiles.normalizeAction("REMOVE"))
    assertNull(FleetAppProfiles.normalizeAction(""))
    assertNull(FleetAppProfiles.normalizeAction("upgrade"))
  }

  @Test
  fun validatePackageName_acceptsAndroidPackageCharacters() {
    assertEquals(
        "com.example.app",
        FleetAppProfiles.validatePackageName(" com.example.app "))
    assertEquals("APP_2", FleetAppProfiles.validatePackageName("APP_2"))
  }

  @Test
  fun validatePackageName_rejectsBlankInvalidAndOversizedNames() {
    assertRejected("", "packageName_required")
    assertRejected("   ", "packageName_required")
    assertRejected("com/example", "invalid_package_name")
    assertRejected("com.example-", "invalid_package_name")
    assertRejected("com.\u00e9xample", "invalid_package_name")
    assertRejected("a".repeat(256), "packageName_required")
  }

  @Test
  fun recordAttempt_countsPendingWorkAndKeepsTerminalOutcomeCurrent() {
    val stored = AtomicReference("{}")
    val context = persistingContext(mapOf("report" to stored))

    FleetAppProfiles.recordAttempt(
        context,
        "com.example.app",
        FleetAppProfiles.STATE_PENDING,
        nowMs = 1_000L,
    )
    val pending = JSONObject(stored.get()).getJSONObject("com.example.app")
    assertEquals(1, pending.getInt("attempts"))
    assertEquals(1_000L, pending.getLong("lastAttemptAtMs"))
    assertEquals("pending", pending.getString("state"))

    FleetAppProfiles.recordAttempt(
        context,
        "com.example.app",
        FleetAppProfiles.STATE_FAILED,
        nowMs = 2_000L,
    )
    val failed = JSONObject(stored.get()).getJSONObject("com.example.app")
    assertEquals(2, failed.getInt("attempts"))
    assertEquals(2_000L, failed.getLong("lastAttemptAtMs"))
    assertEquals("failed", failed.getString("state"))
  }

  @Test
  fun set_preservesAttemptsWhenTheInstallContractIsUnchanged() {
    val stored = keyedStorage()
    val context = persistingContext(stored)
    stored.getValue("desired").set(
        JSONObject()
            .put(
                "com.example.app",
                JSONObject().put("action", "install").put("apkUrl", "https://example.invalid/a.apk"))
            .toString())
    FleetAppProfiles.recordAttempt(context, "com.example.app", "failed", nowMs = 1_000L)

    var reconciledAttempts: Int? = null
    val (profile, _) = FleetAppProfiles.set(
        context,
        "com.example.app",
        "install",
        "https://example.invalid/a.apk",
    ) { candidate ->
        reconciledAttempts = candidate.attempts
        "installed"
    }

    assertEquals(1, profile.attempts)
    assertEquals(1, reconciledAttempts)
    assertEquals("failed", profile.state)
  }

  @Test
  fun set_clearsOldAttemptsWhenTheActionOrApkTargetChanges() {
    val stored = keyedStorage()
    val context = persistingContext(stored)
    stored.getValue("desired").set(
        JSONObject()
            .put(
                "com.example.app",
                JSONObject().put("action", "install").put("apkUrl", "https://example.invalid/a.apk"))
            .toString())
    FleetAppProfiles.recordAttempt(context, "com.example.app", "failed", nowMs = 1_000L)

    var removeProfile: FleetAppProfiles.Profile? = null
    val (removeResult, _) = FleetAppProfiles.set(
        context,
        "com.example.app",
        "remove",
        apkUrl = null,
    ) { candidate ->
        removeProfile = candidate
        "failed"
    }

    assertEquals(0, removeResult.attempts)
    assertEquals(0, removeProfile?.attempts)
    assertEquals("remove", removeProfile?.action)

    var installProfile: FleetAppProfiles.Profile? = null
    val (installResult, _) = FleetAppProfiles.set(
        context,
        "com.example.app",
        "install",
        "https://example.invalid/b.apk",
    ) { candidate ->
        installProfile = candidate
        "failed"
    }

    assertEquals(0, installResult.attempts)
    assertEquals(0, installProfile?.attempts)
    assertEquals("https://example.invalid/b.apk", installProfile?.apkUrl)
  }

  private fun assertRejected(packageName: String, expectedMessage: String) {
    try {
      FleetAppProfiles.validatePackageName(packageName)
    } catch (e: IllegalArgumentException) {
      assertEquals(expectedMessage, e.message)
      return
    }
    fail("Expected $packageName to be rejected")
  }

  private fun keyedStorage(): MutableMap<String, AtomicReference<String>> =
      mutableMapOf("desired" to AtomicReference("{}"), "report" to AtomicReference("{}"))

  private fun persistingContext(
      stored: Map<String, AtomicReference<String>>,
      fallback: AtomicReference<String> = AtomicReference("{}"),
  ): Context {
    val context = mock(Context::class.java)
    val preferences = mock(SharedPreferences::class.java)
    val editor = mock(SharedPreferences.Editor::class.java)

    `when`(context.getSharedPreferences(anyString(), anyInt()))
        .thenReturn(preferences)
    `when`(preferences.getString(anyString(), anyString()))
        .thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            (stored[key] ?: fallback).get()
        }
    `when`(preferences.edit())
        .thenReturn(editor)
    doAnswer { invocation ->
          val key = invocation.getArgument<String>(0)
          val value = invocation.getArgument<String>(1)
          (stored[key] ?: fallback).set(value)
          editor
        }
        .`when`(editor)
        .putString(anyString(), anyString())
    doAnswer { invocation ->
          val key = invocation.getArgument<String>(0)
          (stored[key] ?: fallback).set("{}")
          editor
        }
        .`when`(editor)
        .remove(anyString())

    return context
  }
}
