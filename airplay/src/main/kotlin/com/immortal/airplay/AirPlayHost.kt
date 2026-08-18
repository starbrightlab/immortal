/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.airplay

/**
 * The one seam between the vendored upstream tree and its host app.
 *
 * Upstream's [io.github.jqssun.airplay.service.AirPlayService] hard-references `MainActivity` when a
 * sender connects, to bring the rendering surface to the front. That is the *only* symbol in the
 * whole vendored protocol layer that assumes a particular app shell, so instead of forking the file
 * we route it through here: each host registers the Activity it wants raised.
 *
 * - portal-receiver registers `MainActivity`.
 * - Immortal registers its full-screen `AirPlayActivity`.
 *
 * Leave it null to suppress the launch entirely (an audio-only host that never shows a surface).
 *
 * Keeping this to a single mutable field is deliberate: the corresponding local patch against
 * upstream is two lines, so `airplay/sync-upstream.sh` stays a clean diff. See airplay/UPSTREAM.md.
 */
object AirPlayHost {

    /**
     * Activity brought to the front when a sender opens a connection. Set it before the receiver is
     * started — typically from the host's `Application.onCreate` or its own service.
     */
    @Volatile
    @JvmStatic
    var surfaceActivity: Class<*>? = null
}
