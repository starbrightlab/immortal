#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""
Immich screensaver compression + load audit.

Answers the two questions the launcher's Immich source raises for a video wall:
  1. What is the real compression ratio between originals and the *optimized*
     variants the screensaver actually fetches (preview JPEG for photos,
     /video/playback for videos)?
  2. How much would an on-device cache save, and how much is the wall pulling
     from Immich today with no cache?

It hits the SAME endpoints the app does (see app/.../ImmichSource.kt):
  - POST /api/search/metadata      enumerate an album (or the whole library)
  - GET  /api/assets/{id}          original size via exifInfo.fileSizeInByte
  - GET  /api/assets/{id}/thumbnail?size=preview   the photo bytes served
  - GET  /api/assets/{id}/video/playback           the video bytes served

Optimized sizes are read from the response Content-Length header WITHOUT
downloading the bodies, so the audit itself is light on the server.

Your URL and API key stay on your machine. Prefer the environment variables so
the key never lands in your shell history:

    export IMMICH_URL=https://immich.example.lan
    export IMMICH_API_KEY=xxxxxxxx
    python3 scripts/immich_audit.py --album "Wall Videos"

Flags:
    --url URL            Immich base URL      (or env IMMICH_URL)
    --key KEY            API key              (or env IMMICH_API_KEY)
    --album NAME|ID      Scope to one album; omit for the whole library
    --sample N           Assets measured per type for the size estimate (default 60)
    --interval SEC       Slideshow dwell per item, for the load estimate (default 30)
    --portals N          Portals looping this album, for the load estimate (default 8)
    --insecure           Skip TLS verification (self-signed home certs)
"""

import argparse
import json
import os
import ssl
import sys
import urllib.error
import urllib.request

TIMEOUT = 20


def die(msg):
    print(f"error: {msg}", file=sys.stderr)
    sys.exit(1)


def human(n):
    """Bytes -> human string (KB/MB/GB, base 1024)."""
    if n is None:
        return "unknown"
    x = float(n)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if x < 1024 or unit == "TB":
            return f"{x:.1f} {unit}" if unit != "B" else f"{int(x)} B"
        x /= 1024


class Immich:
    def __init__(self, base, key, insecure):
        self.base = base.rstrip("/")
        if self.base.lower().endswith("/api"):
            self.base = self.base[:-4]
        self.key = key.strip()
        self.ctx = ssl._create_unverified_context() if insecure else None

    def _open(self, path, method="GET", body=None):
        url = f"{self.base}{path}"
        headers = {"x-api-key": self.key, "Accept": "application/json"}
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        return urllib.request.urlopen(req, timeout=TIMEOUT, context=self.ctx)

    def get_json(self, path, method="GET", body=None):
        with self._open(path, method, body) as r:
            return json.load(r)

    def content_length(self, path):
        """Length of what this endpoint would serve, read from the header only.

        Opens the stream, reads Content-Length, and closes before pulling the
        body -- so we learn the served size without downloading the asset.
        Returns (bytes_or_None, note).
        """
        try:
            r = self._open(path)
        except urllib.error.HTTPError as e:
            return None, f"HTTP {e.code}"
        except Exception as e:  # noqa: BLE001 - best-effort probe
            return None, str(e)[:40]
        try:
            cl = r.headers.get("Content-Length")
            return (int(cl), "") if cl is not None else (None, "no Content-Length")
        finally:
            r.close()

    def resolve_album(self, name_or_id):
        albums = self.get_json("/api/albums")
        for a in albums:
            if a.get("id") == name_or_id:
                return a["id"], a.get("albumName", name_or_id), a.get("assetCount")
        lowered = name_or_id.lower()
        for a in albums:
            if a.get("albumName", "").lower() == lowered:
                return a["id"], a["albumName"], a.get("assetCount")
        die(
            f"album {name_or_id!r} not found. Available: "
            + ", ".join(sorted(a.get("albumName", "?") for a in albums))
        )

    def enumerate_assets(self, album_id):
        """All (id, type) in the album (or whole library when album_id is None)."""
        out = []
        page = 1
        while True:
            body = {"size": 1000, "page": page}
            if album_id:
                body["albumIds"] = [album_id]
            resp = self.get_json("/api/search/metadata", method="POST", body=body)
            assets = resp.get("assets") or {}
            items = assets.get("items") or []
            if not items:
                break
            out.extend((it["id"], it.get("type")) for it in items)
            nxt = assets.get("nextPage")
            if not nxt:
                break
            try:
                page = int(nxt)
            except (TypeError, ValueError):
                break
        return out

    def asset_meta(self, asset_id):
        """(original_bytes, duration_seconds) from the asset detail. None on failure."""
        try:
            a = self.get_json(f"/api/assets/{asset_id}")
        except Exception:  # noqa: BLE001
            return None, None
        exif = a.get("exifInfo") or {}
        return exif.get("fileSizeInByte"), parse_duration(a.get("duration"))


def parse_duration(s):
    """Immich duration string 'H:MM:SS.ffffff' -> seconds (float). None if absent."""
    if not s:
        return None
    try:
        h, m, sec = s.split(":")
        return int(h) * 3600 + int(m) * 60 + float(sec)
    except (ValueError, AttributeError):
        return None


def evenly_sampled(items, n):
    """Deterministic stride sample of up to n items across the list."""
    if n <= 0 or len(items) <= n:
        return list(items)
    step = len(items) / n
    return [items[int(i * step)] for i in range(n)]


def measure(client, ids, is_video, sample_n):
    """Return per-asset (original, optimized, note, duration) for a sample of ids."""
    sample = evenly_sampled(ids, sample_n)
    rows = []
    for i, aid in enumerate(sample, 1):
        orig, dur = client.asset_meta(aid)
        if is_video:
            opt, note = client.content_length(f"/api/assets/{aid}/video/playback")
        else:
            opt, note = client.content_length(
                f"/api/assets/{aid}/thumbnail?size=preview"
            )
        rows.append((orig, opt, note, dur))
        kind = "video" if is_video else "photo"
        extra = f"  {dur:.0f}s" if (is_video and dur) else ""
        print(
            f"  {kind} {i}/{len(sample)}  original={human(orig):>10}  "
            f"served={human(opt):>10}{extra}  {note}",
            file=sys.stderr,
        )
    return rows


def summarize(rows):
    """Averages over rows with both sizes known: (avg_orig, avg_opt, n, avg_dur)."""
    good = [(o, p, d) for (o, p, _, d) in rows if o and p]
    if not good:
        return None
    avg_o = sum(o for o, _, _ in good) / len(good)
    avg_p = sum(p for _, p, _ in good) / len(good)
    durs = [d for _, _, d in good if d]
    avg_d = sum(durs) / len(durs) if durs else None
    return avg_o, avg_p, len(good), avg_d


def main():
    ap = argparse.ArgumentParser(description="Immich screensaver compression + load audit")
    ap.add_argument("--url", default=os.environ.get("IMMICH_URL"))
    ap.add_argument("--key", default=os.environ.get("IMMICH_API_KEY"))
    ap.add_argument("--album")
    ap.add_argument("--sample", type=int, default=60)
    ap.add_argument("--interval", type=float, default=30)
    ap.add_argument("--portals", type=int, default=8)
    ap.add_argument(
        "--target-mbps",
        type=float,
        default=1.5,
        help="bitrate for the predicted 1200x800 derivative (default 1.5)",
    )
    ap.add_argument("--insecure", action="store_true")
    args = ap.parse_args()

    if not args.url:
        die("no Immich URL (pass --url or set IMMICH_URL)")
    if not args.key:
        die("no API key (pass --key or set IMMICH_API_KEY)")

    client = Immich(args.url, args.key, args.insecure)

    # Connectivity + auth check, mirroring the app's testConnection.
    try:
        client.get_json("/api/users/me")
    except Exception as e:  # noqa: BLE001
        die(f"cannot reach Immich or key rejected: {e}")

    album_id = None
    scope = "whole library"
    if args.album:
        album_id, album_name, _ = client.resolve_album(args.album)
        scope = f"album {album_name!r}"

    print(f"Enumerating {scope} ...", file=sys.stderr)
    assets = client.enumerate_assets(album_id)
    photos = [aid for (aid, t) in assets if t == "IMAGE"]
    videos = [aid for (aid, t) in assets if t == "VIDEO"]
    print(
        f"Found {len(assets)} assets: {len(photos)} photos, {len(videos)} videos.\n"
        f"Sampling up to {args.sample} of each for sizes ...",
        file=sys.stderr,
    )

    photo_rows = measure(client, photos, False, args.sample) if photos else []
    video_rows = measure(client, videos, True, args.sample) if videos else []

    p = summarize(photo_rows)
    v = summarize(video_rows)

    # Video transcode-policy tell: served size ~= original means /video/playback
    # is handing back the ORIGINAL (no transcode) -- the heaviest case for a wall.
    untranscoded = sum(
        1 for (o, s, _, _) in video_rows if o and s and s >= o * 0.95
    )
    measured_videos = sum(1 for (o, s, _, _) in video_rows if o and s)

    line = "=" * 64
    print(f"\n{line}\nImmich screensaver audit -- {scope}\n{line}")

    def block(label, count, summ):
        if not summ:
            print(f"\n{label}: {count} assets (no size sample available)")
            return 0.0
        avg_o, avg_p, n, _ = summ
        ratio = avg_o / avg_p if avg_p else 0
        total_opt = avg_p * count
        total_orig = avg_o * count
        print(
            f"\n{label}: {count} assets  (sized from {n})\n"
            f"  avg original : {human(avg_o)}\n"
            f"  avg served   : {human(avg_p)}\n"
            f"  ratio        : {ratio:.1f}x smaller\n"
            f"  album served total (= cache footprint) : {human(total_opt)}\n"
            f"  album original total                   : {human(total_orig)}"
        )
        return total_opt

    photo_cache = block("PHOTOS", len(photos), p)
    video_cache = block("VIDEOS", len(videos), v)

    if measured_videos:
        print(
            f"\nVideo transcode check: {untranscoded}/{measured_videos} sampled "
            f"videos are served at ~original size."
        )
        if untranscoded:
            print(
                "  -> /video/playback is streaming ORIGINAL bytes for those. If you\n"
                "     expected transcodes, check Immich Admin > Video Transcoding\n"
                "     (target resolution / transcode policy)."
            )

    # What a purpose-built 1200x800 derivative would cost. Duration is unchanged by
    # re-encoding, so predicted size = target_bitrate * duration. This is the number
    # that decides whether an on-device cache actually fits.
    if v and v[3]:
        avg_dur = v[3]
        served_mbps = (v[1] * 8) / avg_dur / 1e6
        deriv_avg = args.target_mbps * 1e6 / 8 * avg_dur  # bytes per clip
        deriv_total = deriv_avg * len(videos)
        print(
            f"\n{line}\nDERIVATIVE PREDICTION (purpose-built for 1200x800)\n"
            f"  avg clip duration        : {avg_dur:.0f}s\n"
            f"  current served bitrate    : {served_mbps:.1f} Mbps  (for a <1MP screen!)\n"
            f"  target                    : 1200x800 H.264 @ {args.target_mbps:g} Mbps\n"
            f"  predicted avg size        : {human(deriv_avg)}  "
            f"({v[1] / deriv_avg:.0f}x smaller than served)\n"
            f"  predicted album footprint : {human(deriv_total)}  "
            f"(vs {human(video_cache)} served today)"
        )

    total_cache = photo_cache + video_cache
    print(f"\n{line}\nCACHE FOOTPRINT (per portal, if it holds the whole album)")
    print(f"  serving Immich's current output : {human(total_cache)}")
    if v and v[3]:
        deriv_total = args.target_mbps * 1e6 / 8 * v[3] * len(videos)
        print(f"  serving 1200x800 derivatives    : {human(deriv_total + photo_cache)}")

    # Current no-cache load: every dwell fetches one asset, on every portal, forever.
    # Weight the average served size by the real photo/video mix -- a video-heavy
    # wall pulls far more than a naive two-type average would suggest.
    if args.interval > 0 and (p or v):
        served_bytes = (p[1] * len(photos) if p else 0) + (v[1] * len(videos) if v else 0)
        served_count = (len(photos) if p else 0) + (len(videos) if v else 0)
        avg_served = served_bytes / served_count if served_count else 0
        fetches_per_day_per_portal = 86400 / args.interval
        bytes_day = avg_served * fetches_per_day_per_portal * args.portals
        print(
            f"\nCURRENT LOAD (no cache): {args.portals} portals, {args.interval:g}s dwell"
        )
        print(
            f"  ~{fetches_per_day_per_portal:,.0f} fetches/portal/day "
            f"x {args.portals} portals\n"
            f"  ~{human(bytes_day)}/day pulled from Immich, every day, forever\n"
            f"  With a cache: each asset fetched once per portal, then served locally."
        )
    print(line)


if __name__ == "__main__":
    main()
