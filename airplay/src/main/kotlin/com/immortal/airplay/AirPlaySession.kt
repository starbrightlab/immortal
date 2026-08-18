/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.airplay

import io.github.jqssun.airplay.service.AirPlayService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * What a sender is currently doing, as the three questions a host actually asks.
 *
 * The service exposes this as four independent flags plus a poll timestamp, and every consumer that
 * folds them itself ends up with a slightly different idea of when video is "live" — which matters,
 * because the mirroring → AirPlay-video handover briefly leaves *all* of them false and a consumer
 * that reads that as "the sender is gone" tears the session down mid-handover.
 */
data class AirPlaySession(
    /** A sender is connected. False means the session is over, whatever the other two say. */
    val connected: Boolean,
    /** The sender is streaming audio with no picture. */
    val audioOnly: Boolean,
    /** A picture is live or imminent: mirroring, AirPlay video, or a video channel just polled. */
    val video: Boolean,
)

/**
 * How long a session may look finished while it is only handing over.
 *
 * Mirroring stops before the AirPlay-video URL arrives; the measured gap is around a second. Any
 * host reaction that tears something down on "the video ended" — closing a surface, handing back
 * the speakers — has to wait this out first, or it fires in the middle of a YouTube cast. Several
 * times the measured gap, because over-waiting only costs a stale frame or a quiet speaker.
 */
const val AIRPLAY_HANDOVER_GRACE_MS = 5_000L

/**
 * The session as one flow.
 *
 * `videoSessionPending` is sampled rather than collected — it is a timestamp window, not a flow, so
 * it has no edges of its own. It only ever widens [AirPlaySession.video], and the flags it rides
 * along with change often enough during a handover to carry it. Including it is what makes the gap
 * between mirroring and AirPlay video read as "still video", roughly a second before the URL lands.
 */
fun AirPlayService.sessionFlow(): Flow<AirPlaySession> =
    combine(connectionCount, audioOnly, mirroringActive, videoPlaybackActive) {
        connections,
        audio,
        mirroring,
        playback ->
        AirPlaySession(
            connected = connections > 0,
            audioOnly = audio,
            video = mirroring || playback || videoSessionPending(),
        )
    }
