#!/usr/bin/env python3
"""Validate catalog.json for the Immortal App Store.

Run by CI on every PR that touches the catalog, and runnable locally:
    python3 scripts/validate_catalog.py [--network]

Checks (offline): JSON validity, schema fields, duplicate packages, sane values.
With --network: download URLs resolve, F-Droid ids exist, icon URLs serve images.
"""

import json
import re
import sys
import urllib.request

NETWORK = "--network" in sys.argv

PKG_RE = re.compile(r"^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$")
ALLOWED_SOURCES = {"fdroid", "url"}
ALLOWED_DEVICES = {"touch", "tv"}
KNOWN_FIELDS = {
    "name", "packageName", "source", "fdroidId", "apkUrl", "versionCode",
    "versionUrl", "minSdk", "description", "longDescription", "iconUrl",
    "author", "homepage", "submittedBy", "devices",
}

errors = []
warnings = []


def err(msg):
    errors.append(msg)


def warn(msg):
    warnings.append(msg)


def head(url, what):
    try:
        req = urllib.request.Request(url, method="HEAD",
                                     headers={"User-Agent": "immortal-catalog-ci"})
        with urllib.request.urlopen(req, timeout=20) as r:
            if r.status >= 400:
                err(f"{what}: {url} returned HTTP {r.status}")
    except Exception as e:
        err(f"{what}: {url} failed ({e})")


def main():
    try:
        with open("catalog.json", encoding="utf-8") as f:
            cat = json.load(f)
    except Exception as e:
        print(f"FAIL: catalog.json is not valid JSON: {e}")
        return 1

    if cat.get("schemaVersion") != 2:
        err(f"schemaVersion must be 2, found {cat.get('schemaVersion')!r}")

    seen = {}
    apps = [(c.get("name", "?"), a)
            for c in cat.get("categories", [])
            for a in c.get("apps", [])]
    if not apps:
        err("no apps found under categories[].apps[]")

    for cat_name, a in apps:
        name = a.get("name") or "<missing name>"
        ctx = f"[{cat_name} / {name}]"

        for field in ("name", "packageName", "description"):
            if not a.get(field):
                err(f"{ctx} missing required field '{field}'")

        pkg = a.get("packageName", "")
        if pkg:
            if not PKG_RE.match(pkg):
                err(f"{ctx} packageName '{pkg}' doesn't look like an Android package id")
            if pkg in seen:
                err(f"{ctx} duplicate packageName '{pkg}' (also in {seen[pkg]})")
            seen[pkg] = ctx

        source = a.get("source", "fdroid")
        if source not in ALLOWED_SOURCES:
            err(f"{ctx} source must be one of {sorted(ALLOWED_SOURCES)}, found '{source}'")
        if source == "url" and not a.get("apkUrl"):
            err(f"{ctx} source 'url' requires apkUrl")
        if source == "fdroid" and not (a.get("fdroidId") or pkg):
            err(f"{ctx} source 'fdroid' requires fdroidId (or a valid packageName)")

        for ufield in ("apkUrl", "iconUrl", "homepage", "versionUrl"):
            u = a.get(ufield)
            if u and not u.startswith("https://"):
                err(f"{ctx} {ufield} must be https:// — found '{u}'")

        min_sdk = a.get("minSdk")
        if min_sdk is not None and not (isinstance(min_sdk, int) and 14 <= min_sdk <= 40):
            err(f"{ctx} minSdk should be an int in 14..40, found {min_sdk!r}")
        if isinstance(min_sdk, int) and min_sdk > 29:
            warn(f"{ctx} minSdk {min_sdk} means NO current Portal can run it "
                 f"(Portals are API 28/29) — listing it will just show as incompatible")

        vc = a.get("versionCode")
        if vc is not None and not isinstance(vc, int):
            err(f"{ctx} versionCode must be an integer, found {vc!r}")
        # A direct-URL app needs a live version source, else the store can't tell
        # when it's out of date and will never show an Update. Either a declared
        # versionCode (bumped per release) OR a versionUrl (a JSON manifest the
        # store reads live — zero catalog edits per release) satisfies this.
        # F-Droid apps don't need either (resolved from the API); the launcher
        # ships its own self-updater, so it's exempt.
        if (source == "url" and vc is None and not a.get("versionUrl")
                and pkg != "com.immortal.launcher"):
            warn(f"{ctx} source 'url' without versionCode or versionUrl won't get "
                 f"update detection — add a versionCode (bumped per release) or a "
                 f"versionUrl (a JSON manifest with a versionCode field, read live) "
                 f"so the store can offer updates without a delete-and-reinstall")

        devices = a.get("devices", [])
        if not isinstance(devices, list) or any(d not in ALLOWED_DEVICES for d in devices):
            err(f"{ctx} devices must be a list drawn from {sorted(ALLOWED_DEVICES)}")

        if len(a.get("description", "")) > 120:
            warn(f"{ctx} description is over 120 chars — it gets one line in the store; "
                 f"put detail in longDescription")

        unknown = set(a.keys()) - KNOWN_FIELDS
        if unknown:
            warn(f"{ctx} unknown fields {sorted(unknown)} (ignored by the client)")

        if NETWORK:
            if a.get("apkUrl"):
                head(a["apkUrl"], f"{ctx} apkUrl")
            if a.get("iconUrl"):
                head(a["iconUrl"], f"{ctx} iconUrl")
            if a.get("versionUrl"):
                head(a["versionUrl"], f"{ctx} versionUrl")
            if source == "fdroid":
                fid = a.get("fdroidId") or pkg
                head(f"https://f-droid.org/api/v1/packages/{fid}", f"{ctx} fdroidId")

    for w in warnings:
        print(f"WARN: {w}")
    for e in errors:
        print(f"FAIL: {e}")
    if errors:
        print(f"\n{len(errors)} error(s), {len(warnings)} warning(s).")
        return 1
    print(f"OK: {len(apps)} apps validated, {len(warnings)} warning(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
