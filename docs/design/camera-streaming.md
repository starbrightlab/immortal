# Camera streaming for Home Assistant — design

Expose a Portal's camera to Home Assistant as a live stream, with **optional audio**, so a
Portal in a nursery or hallway can double as a nanny/security camera. LAN only, off by default,
and unmistakable when it's live.

**Status:** live video and audio implemented and confirmed on hardware; phase 4 (motion) not started. Phase 1 (MQTT stills) was built, shipped in 1.69-1.71, and then **removed** — see [phase 1 was a stepping stone](#phase-1-was-a-stepping-stone). This records the decisions,
the parts we already have, the parts that are genuinely hard, and what has to be answered on real
hardware before writing much code.

## Goal

- A Portal appears in Home Assistant as a camera you can watch live, at a useful frame rate.
- Audio is **optional and separate** — a nursery wants it, a hallway camera pointed at the front
  door might not.
- Nothing leaves the LAN. No cloud, no account, no third-party service.
- A device that hasn't been switched on for this pays nothing: no camera open, no encoder, no
  microphone.

## What it is not (kept narrow on purpose)

- **Not recording or storage.** No clips, no ring buffer, no SD writing. Home Assistant (or
  Frigate) owns retention; the Portal is a source.
- **Not two-way audio.** [Intercom](../features/tools.md) already does push-to-talk between
  Portals; a security camera doesn't need to also be a speakerphone.
- **Not person/face detection.** Motion, at most, and only in a later phase. Anything smarter
  belongs in Home Assistant where the compute is.
- **Not a replacement for a purpose-built camera.** Fixed position, no IR, no night vision, and
  a lens designed for video calls at 1–3 m. It is a *useful second angle in a room you already
  have a Portal in*, and the docs should say so plainly rather than overselling it.

## What we already have

More than expected, which is why this is worth scoping rather than dismissing.

| Piece | Where | What it gives us |
| --- | --- | --- |
| Camera2 capture on Portal | `GestureCamera` | Opens the **front camera** with a standard Camera2 session and an `ImageReader` (320×240 `YUV_420_888`) for wave-to-advance. No Meta Smart Camera SDK, no signature permissions. |
| A hardened HTTP server | `FleetHttpServer` | Already serves the fleet agent, survives abandoned connections, and has socket-lifecycle tests. A snapshot/MJPEG endpoint is a small addition. |
| Microphone capture | `LanAudio` | Proven `AudioRecord` at 16 kHz mono PCM16 for the intercom. |
| MQTT entity plumbing | `MqttPublisher` | Discovery, switches, availability, teardown-on-disable all exist; new entities are declarative. |
| Permissions | `AndroidManifest.xml` | `CAMERA`, `RECORD_AUDIO` and `SYSTEM_ALERT_WINDOW` are already declared and granted by the [provisioning kit](../provisioning.md). |

`GestureCamera` is the important one: it means "can an unprivileged app get frames off a Portal
camera" is already answered in our own codebase. Note its own caveat though — it is marked
experimental and **unverified on real hardware**, so that claim needs confirming before it can
carry a feature.

## Transport: RTSP, because of audio

| Option | Verdict |
| --- | --- |
| **MJPEG** over the existing HTTP server | Easiest by far — JPEG frames, no encoder, works with HA's `mjpeg` integration. **But it cannot carry audio.** Fine as a first phase; not the destination. |
| **WebRTC** direct | Best latency, and what the HA card ultimately speaks. Far the heaviest to implement on-device. |
| **RTSP: H.264 video + AAC audio** | The choice. Android's `MediaCodec` encodes both natively, so no third-party encoder library is needed, and go2rtc ingests RTSP into the HA WebRTC card as a matter of routine. |

Encode **H.264 Constrained Baseline**. Browser WebRTC rejects higher profiles, so the stream
would play in VLC and fail in the dashboard — the exact trap `portal-ha-bridge` documents
hitting.

> **On `portal-ha-bridge`.** That project solves this problem already and its notes informed the
> hazards below. It is **PolyForm Noncommercial**, so it can be read for technique but no code
> can be copied into this MIT repo. The encoder work here is ours to write.

## The hard parts

**1. The encoder pipeline.** `Camera2 → Surface → MediaCodec(H.264) → RTP packetiser → RTSP`,
plus `AudioRecord → MediaCodec(AAC-LC) → RTP` on a second track, with timestamps that actually
correlate. This is the bulk of the work and the part most likely to need hardware iteration.
Note the shape change from `GestureCamera`: a stream feeds the encoder's input `Surface`
directly rather than reading frames into an `ImageReader`.

**2. Per-model field of view.** Portal+ front cameras expose a *virtual* sensor that scales the
FOV into whatever size is requested, so a naive 16:9 request comes out stretched. Known-good
shapes: `aloha` (Portal+ gen 1) ≈ square, 480×480; `cipher` (gen 2) portrait-mounted, 480×640
with the 4:3 applied viewer-side. Gate on `Build.DEVICE`, the way `Curation` already gates
Chrome off the Portal TV.

**3. Camera contention.** The camera is shared. A Portal call takes it; `GestureCamera` wants it
for wave-to-advance during the photo frame. Streaming and gesture-wave must be **mutually
exclusive**, with one owner arbitrating and the stream recovering after a call releases the
device.

**4. Microphone contention — was unmanaged; now brokered through `MicOwner`.** `LanAudio` (intercom) and `AudioNote` both
open `AudioSource.MIC` today with **no arbitration between them at all**. Audio streaming would
be a third consumer, and the failure is silent: whoever asks second gets nothing useful. This
needs a single mic broker with an explicit priority — a live intercom announcement should win,
and the stream should drop its audio track for the duration rather than fight.

On Portal+ there is a further constraint: Meta's own far-field mic service (`com.millennium`)
can hold the microphone, which is why the bridge ships a `--free-mic` provisioning flag. Expect
audio to be unavailable on some models until that's disabled, and detect it rather than assume.

**5. Mic mute has to gate audio — done, and unit-tested.** Immortal publishes a `mic_mute` switch to Home Assistant. A
stream that keeps sending audio while HA says the microphone is muted is both a contradiction
and a genuine privacy surprise. **When `isMicrophoneMute` is true the audio track must be
silent.** That rule is pure logic and should be unit-tested, not left to integration behaviour.

**6. Heap and thermals.** A continuous encode on Android 9 hardware with no `largeHeap`, on a
device that may already be running the photo frame. Bound the resolution, frame rate and
bitrate conservatively, and treat "streaming" as mutually exclusive with heavyweight screensaver
work where they'd collide.

## Privacy design (non-negotiable)

A camera and microphone in someone's home is a different posture from anything Immortal ships
today. These are requirements, not preferences:

- **Off by default**, per device, with no way for a Home Assistant command to switch the master
  on. If the master switch is off on the device, the Portal ignores stream requests entirely.
- **Three separate switches**, not one: `Camera` (master), `Camera streaming`, `Camera audio`.
  Audio cannot be on without the camera.
- **A visible on-device indicator whenever capture is live.** Settled as the Portal's own
  **hardware camera LED**, plus the foreground-service notification. An in-app overlay badge was
  built for this and then removed: it added nothing the LED doesn't do better (the LED is wired
  below the OS, so it can't be faked or suppressed) and it could be left stale on screen by a
  stream that died, which is worse than no indicator — an indicator that lies is a bug, not a
  safeguard.
- **Say who can turn it on.** The [notification design](mqtt-notifications.md) already assumes a
  trusted-LAN broker; here that assumption has teeth, because anyone with publish credentials
  can start a stream. This must be stated in the user docs, not just a design note.

## Home Assistant surface

| Entity | Type | Notes |
| --- | --- | --- |
| Camera | *device-only setting* | Master consent. Off means the camera is never opened. Deliberately **not** an MQTT entity: it's the one thing Home Assistant must never be able to turn on. |
| Camera streaming | `switch` | Starts/stops the RTSP server. Requires the master. |
| Camera audio | `switch` | Adds the audio track. Requires streaming; forced silent while mic-muted. |
| Stream URL | `sensor` (diagnostic) | `rtsp://<ip>:8554/` — so the dashboard card can be configured by copy-paste. |
| Motion | `binary_sensor` | Later phase; reuses the frame-difference logic `GestureCamera` already has. |

The live view is wired through go2rtc + the WebRTC Camera card rather than an MQTT `camera`
entity (which carries stills, not video). The setup guide gives the exact card YAML with the
device's own IP filled in.

## Phasing

Deliberately ordered so each phase is useful alone and de-risks the next.

1. **Snapshot only** — *built, shipped in 1.69-1.71, then removed*. See
   [phase 1 was a stepping stone](#phase-1-was-a-stepping-stone) below. It did its job: it proved
   Camera2 works unprivileged on a Portal, and it found the permission gap that would otherwise
   have been blamed on the streaming code.
2. **Video streaming** — *implemented*. RTSP + `MediaCodec` H.264, no audio yet.

    RTP is **interleaved over the RTSP TCP connection** rather than sent on separate UDP ports.
    One socket, no port negotiation, nothing to explain to a firewall on a home LAN, and no loss
    to conceal — at the cost of head-of-line blocking, a fair trade for a fixed indoor scene at
    15fps. The parts where a mistake is invisible rather than loud — RTP packetisation, FU-A
    fragmentation, the SDP — are pure and unit-tested (`RtpH264`, `RtspSdp`); the Camera2 and
    `MediaCodec` plumbing around them is not, and needs a device.
3. **Audio** — *implemented*. An AAC-LC track alongside the video, its own RTP clock and
   control URL, so a viewer that only wants a picture sets up one track and not the other.

    Both hazards named above are addressed. [`MicOwner`] arbitrates the microphone by priority,
    and the intercom and voice notes now go through it too — closing the pre-existing gap where
    nothing arbitrated at all and whoever asked second silently got nothing. Muting the
    microphone stops audio leaving the device rather than merely lowering it, which is the only
    reading of the `mic_mute` switch that isn't a lie.
4. **Motion** (optional). `binary_sensor` from the existing frame-diff, with a sensitivity
   number entity.

### Phase 1 was a stepping stone

Stills were delivered as an MQTT `camera` entity plus a `Take snapshot` button, on the reasoning
that HA's MQTT camera component needs no second auth story — unlike the fleet HTTP server, whose
bearer token HA's `generic` camera integration can't send. That reasoning was sound and the
feature still didn't survive contact with the hardware:

- **The images were far too big for MQTT.** A Portal Go produced a **12 MB** JPEG despite a 640px
  request and quality 75 — the camera's JPEG sizes don't honour the bound the way the encoder's
  video sizes do. An oversize publish doesn't get rejected on its own; Mosquitto drops the whole
  **connection** (`disconnected: oversize packet`), taking presence, sensors and every other
  entity down with it. A 200 KB refusal guard was added, after which the button reliably did
  nothing at all.
- **It fought the feature it was meant to lead to.** One camera, one holder: pressing the
  snapshot button while streaming *killed the stream*, verified in the log.

So it was removed in favour of the thing it was scaffolding for. Anyone who wants a still can
take one from the RTSP feed — that's what HA's `camera.snapshot` service and go2rtc are for, and
neither costs a broker connection. The retained discovery configs for both entities are cleared
unconditionally on connect, so Portals upgraded from 1.69-1.71 don't keep two dead entities.

**The lesson worth keeping:** the phase was still worth building. It answered the question the
whole design hung on (does Camera2 work unprivileged here?) and it surfaced the ungranted
`CAMERA` runtime permission — which would otherwise have shown up as "streaming is broken".
A stepping stone that gets thrown away after it's been stepped on has not been wasted.

## Unknowns to settle on hardware first

None of these are answerable from the source, and all of them can invalidate parts of the plan:

- ~~Does Camera2 work unprivileged on a Portal at all?~~ **Yes** — confirmed on hardware in
  phase 1: real capture, real JPEG, camera indicator lit. This was the question the whole design
  hung on.
- What resolutions does Camera2 actually offer per model, for stills and for an encoder surface?
- ~~Does Portal firmware permit camera access from a background service?~~ **No — and nothing
  gets around it.** Measured on a Portal Go: the stream runs happily until another app comes to
  the front, then dies with `ERROR_CAMERA_DISABLED` within seconds. Three things were tried, in
  order, and all three failed:

    1. **A foreground service.** Not sufficient on Android 9 — this was the first attempt and the
       one the failure was originally blamed on.
    2. **A visible overlay window** (`SYSTEM_ALERT_WINDOW`, already granted) — the lever the
       bridge documents needing. The badge was confirmed on screen (`camera-live badge shown` in
       the log) and the camera was revoked anyway. `IMPORTANT_FOREGROUND` is not enough; the gate
       appears to want the actual top activity.
    3. **Forcing the app-op**: `adb shell appops set com.immortal.launcher CAMERA allow`, on the
       theory that the op was resolving as `MODE_FOREGROUND`. No change, which suggests the
       restriction is enforced below AppOps.

  **So the constraint is accepted rather than defeated**, and it costs less than it sounds: a
  launcher is the foreground app almost all of the time, and the streaming this was built for
  runs for hours with nobody touching the device. What matters is that losing the camera is not
  treated as failure — `CameraStreamService` keeps the service, the RTSP port and the Home
  Assistant switch exactly as they were, and takes the camera back within seconds of the launcher
  returning to the front. The one thing genuinely ruled out is watching the stream in a player on
  the Portal that is producing it.

  The overlay badge that came out of attempt 2 was removed with it. It was justified as doubling
  as the "camera is live" signal, but the Portal's **hardware camera LED** already does that job
  properly: it's wired below the OS, so it can't be faked, and — unlike a badge that survived a
  crashed stream on a real device — it can't be left behind.
- ~~Is there a usable hardware `MediaCodec` H.264 encoder on API 28 Portal, and does it offer
  Constrained Baseline?~~ **Yes, with a caveat.** Confirmed encoding 640×480 at 15fps on a Portal
  Go and producing SPS/PPS. It rejects a profile hint given *without* a level
  (`CodecException 0x80001001` out of `configure()`), so both are set together, with a
  no-hint fallback behind it.
- Does `com.millennium` block microphone capture on the Portal+ models, and on which?
- Thermal behaviour of a sustained encode — the device is fanless and often wall-powered in a
  warm room.

**Recommendation:** build phase 1 first and answer the list above with it. It is a small amount
of code, it delivers a working (if low frame rate) camera in Home Assistant on its own, and
everything expensive in phases 2–3 depends on facts only phase 1 can establish.
