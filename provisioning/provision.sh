#!/usr/bin/env bash
#
# Copyright (c) Meta Platforms, Inc. and affiliates.
# Licensed under the MIT license found in the LICENSE file in the repo root.
#
# One-shot Portal provisioner. Finds (or downloads) ADB, waits for a connected
# Portal, then: installs the client app, pushes photo assets, pre-grants
# permissions, disables Meta's package verifier, and sets the custom launcher
# and screensaver. Run with --restore to undo everything.
#
# Usage:
#   ./provision.sh            provision the connected Portal
#   ./provision.sh --restore  put the stock launcher/screensaver/verifier back
#   ./provision.sh --status   show what's currently set
#   ./provision.sh --overlay-fix  fix the Gen-1 white-on-white installer dialog
#   ./provision.sh --shizuku  start the Shizuku server (optional; for apps that
#                             use the Shizuku API, e.g. Aurora's Shizuku mode)
#   ./provision.sh --fleet    enable the WiFi fleet agent and record this device
#                             for the laptop fleet tool (the persistent channel)
#   ./provision.sh --wifi-adb on-demand raw adb-over-WiFi for shell/scrcpy (temp;
#                             pauses Shizuku, resets on reboot)
#   ./provision.sh --alexa    restore the original Amazon Alexa app (the "hey"
#                             free tier): revive falcon + install the wake word

set -u
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ----- pretty output ---------------------------------------------------------
if [ -t 1 ]; then B=$'\033[1m'; G=$'\033[32m'; Y=$'\033[33m'; R=$'\033[31m'; D=$'\033[2m'; N=$'\033[0m'; else B=; G=; Y=; R=; D=; N=; fi
step() { printf "%s==>%s %s\n" "$B" "$N" "$1"; }
ok()   { printf "  %s✓%s %s\n" "$G" "$N" "$1"; }
warn() { printf "  %s!%s %s\n" "$Y" "$N" "$1"; }
die()  { printf "%sERROR:%s %s\n" "$R" "$N" "$1" >&2; exit 1; }

# ----- load config -----------------------------------------------------------
[ -f config.env ] || die "config.env not found next to this script."
# shellcheck disable=SC1091
set -a; . ./config.env; set +a

# Per-device snapshot of the ORIGINAL stock launcher/screensaver, written on the
# device itself the first time we provision. Restore reads this so it works on
# any Portal model (Go/Mini/Plus/TV/Gen-1) and from any computer — the hardcoded
# STOCK_* in config.env are only fallbacks if this snapshot is missing.
STATE_FILE=/sdcard/immortal_restore.env

# ----- resolve adb (bundled -> PATH -> download) -----------------------------
resolve_adb() {
  if [ -x "$SCRIPT_DIR/platform-tools/adb" ]; then ADB="$SCRIPT_DIR/platform-tools/adb"; return; fi
  if command -v adb >/dev/null 2>&1; then ADB="$(command -v adb)"; return; fi
  step "Android platform-tools (adb) not found — downloading the official package from Google"
  local os zip url
  case "$(uname -s)" in
    Darwin) os=darwin ;;
    Linux)  os=linux ;;
    *) die "Unsupported OS for auto-download. Install Android platform-tools and re-run." ;;
  esac
  url="https://dl.google.com/android/repository/platform-tools-latest-${os}.zip"
  zip="$SCRIPT_DIR/platform-tools.zip"
  curl -fL "$url" -o "$zip" || die "Download failed. Check your internet connection."
  unzip -oq "$zip" -d "$SCRIPT_DIR" || die "Could not unzip platform-tools."
  rm -f "$zip"
  [ -x "$SCRIPT_DIR/platform-tools/adb" ] || die "adb missing after download."
  ADB="$SCRIPT_DIR/platform-tools/adb"
  ok "platform-tools installed locally"
}

a() { "$ADB" "$@"; }

# ----- wait for an authorized device -----------------------------------------
wait_for_device() {
  step "Looking for your Portal"
  a start-server >/dev/null 2>&1
  local printed_plug=0 printed_auth=0 printed_adb=0
  while true; do
    local line state devs n
    if [ -n "${ANDROID_SERIAL:-}" ]; then
      # Pinned (by the user, or by the multi-device prompt below): adb itself
      # honours ANDROID_SERIAL, so just probe that device's state.
      state="$(a get-state 2>/dev/null | tr -d '\r')"
      [ "$state" = "device" ] || state=""
    else
      devs="$(a devices | awk 'NR>1 && $2=="device"{print $1}')"
      n="$(printf "%s" "$devs" | grep -c . || true)"
      if [ "$n" -gt 1 ]; then
        # adb refuses every command when more than one device is attached —
        # without this the run dies later at the first install with a bare
        # "Install failed." Offer a numbered pick-list up front instead.
        [ -t 0 ] || die "Multiple devices are connected. Unplug the others, or re-run with ANDROID_SERIAL=<serial>."
        local dev_serials=() dev_labels=() dline dserial dmodel idx choice
        while IFS= read -r dline; do
          [ -n "$dline" ] || continue
          dserial="${dline%% *}"
          dmodel="$(printf "%s" "$dline" | sed -n 's/.*model:\([^ ]*\).*/\1/p')"
          dev_serials+=("$dserial")
          dev_labels+=("${dmodel:-unknown}  ($dserial)")
        done <<EOF
$(a devices -l | awk 'NR>1 && $2=="device"')
EOF
        warn "More than one device is connected — which one is this setup for?"
        idx=1
        for dline in "${dev_labels[@]}"; do
          printf "    %s%d)%s %s\n" "$B" "$idx" "$N" "$dline"
          idx=$((idx + 1))
        done
        while :; do
          printf "  Number: "
          read -r choice || die "No selection made."
          case "$choice" in
            *[!0-9]* | "") : ;;
            *) [ "$choice" -ge 1 ] && [ "$choice" -le "${#dev_serials[@]}" ] && break ;;
          esac
          warn "Enter a number between 1 and ${#dev_serials[@]}."
        done
        ANDROID_SERIAL="${dev_serials[$((choice - 1))]}"
        export ANDROID_SERIAL
        ok "Using ${dev_labels[$((choice - 1))]}"
        continue
      elif [ "$n" = 1 ]; then
        # Pin the one authorized device so a second one appearing mid-run
        # (or a lingering unauthorized entry) can't break later commands.
        ANDROID_SERIAL="$devs"
        export ANDROID_SERIAL
        state="device"
      else
        line="$(a devices | awk 'NR>1 && NF{print; exit}')"
        state="$(printf "%s" "$line" | awk '{print $2}')"
      fi
    fi
    case "$state" in
      device)
        local model; model="$(a shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
        ok "Connected: ${model:-device}"
        case "$model" in
          *Portal*) : ;;
          "" ) : ;;
          *) warn "This doesn't look like a Portal (model: $model). Continuing anyway." ;;
        esac
        return ;;
      unauthorized)
        if [ "$printed_auth" = 0 ]; then
          printf "  %sOn the Portal screen, tap %sAllow%s (check \"Always allow from this computer\").%s\n" "$Y" "$B" "$N$Y" "$N"
          printed_auth=1
        fi ;;
      "")
        if [ "$printed_plug" = 0 ]; then
          printf "  %sPlug the Portal into this computer with a USB-C cable.%s\n" "$Y" "$N"
          printf "  %sOn the Portal: Settings > Debug > ADB Enabled.%s\n" "$D" "$N"
          printed_plug=1
        fi ;;
      *)
        if [ "$printed_adb" = 0 ]; then warn "Device state: $state — waiting…"; printed_adb=1; fi ;;
    esac
    sleep 2
  done
}

# ----- individual actions ----------------------------------------------------
# Resolve a download URL for the signed release APK. An explicit RELEASE_APK_URL
# wins (pin a specific build); otherwise ask GitHub for the latest release on
# RELEASE_REPO and pick its first .apk asset — so versioned asset names
# (immortal-<version>.apk) keep working without editing this kit every release.
resolve_release_apk_url() {
  if [ -n "${RELEASE_APK_URL:-}" ]; then printf '%s\n' "$RELEASE_APK_URL"; return 0; fi
  [ -n "${RELEASE_REPO:-}" ] || return 1
  curl -fsSL -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/${RELEASE_REPO}/releases/latest" 2>/dev/null \
    | grep -o '"browser_download_url"[[:space:]]*:[[:space:]]*"[^"]*\.apk"' \
    | head -1 \
    | sed 's/.*"\(https[^"]*\)".*/\1/'
}

install_client() {
  local apk
  apk="$(ls $APK_GLOB 2>/dev/null | head -1)"
  if [ -z "$apk" ]; then
    local url; url="$(resolve_release_apk_url)"
    [ -n "$url" ] || die "No local APK in apks/ and couldn't resolve a release APK. Drop a signed APK in apks/, or set RELEASE_APK_URL / RELEASE_REPO in config.env."
    step "No local APK — downloading the latest Immortal release"
    mkdir -p "$(dirname "$APK_GLOB")"
    apk="$(dirname "$APK_GLOB")/immortal.apk"
    curl -fL "$url" -o "$apk" || die "Could not download the release APK. Check your connection."
    ok "Downloaded $(basename "$apk")"
  fi
  [ -n "$apk" ] || die "No client APK found matching '$APK_GLOB'. Drop your signed APK in apks/."
  step "Installing client app ($(basename "$apk"))"
  a install -r -d "$apk" >/dev/null 2>&1 && ok "Installed $PKG" || die "Install failed."
}

start_shizuku() {
  # Shizuku (moe.shizuku.privileged.api) is a privileged broker: started over ADB
  # once, it lets apps that speak its API run shell-level operations without root.
  # We install + start it on EVERY Portal — it's broadly useful (Aurora Store's
  # silent installs, and the future on-device Alexa-restore path that needs the
  # privileged grants), and harmless where unused. The Gen-1 Portal+ also relies on
  # it because its stock installer dialog is broken.
  #
  # Like our own daemon — and by Shizuku's own design — the server does NOT
  # survive a reboot. Re-run `provision.sh --shizuku` to restart it.
  local SZ=moe.shizuku.privileged.api
  local installed
  installed="$(a shell pm list packages "$SZ" 2>/dev/null | tr -d '\r' | grep -c "package:$SZ")"
  if [ "${installed:-0}" = 0 ]; then
    if [ -n "${SHIZUKU_APK_URL:-}" ]; then
      step "Installing Shizuku (privileged broker — useful on every Portal)"
      local tmp="$(dirname "$APK_GLOB")/shizuku.apk"; mkdir -p "$(dirname "$tmp")"
      if curl -fsL "$SHIZUKU_APK_URL" -o "$tmp" 2>/dev/null && a install -r "$tmp" >/dev/null 2>&1; then
        ok "Shizuku installed"
      else
        warn "Shizuku install failed — skipping"; rm -f "$tmp"; return
      fi
      rm -f "$tmp"
    else
      return  # No SHIZUKU_APK_URL configured: nothing to do, silently.
    fi
  fi
  step "Starting Shizuku server (for third-party silent installs)"
  # Derive the version-specific starter path. Shizuku ships a tiny native binary
  # (libshizuku.so) under its install dir's lib/<abi>/ folder; running it as the
  # shell user starts the server. The install-dir hash and ABI vary per device,
  # so resolve both at runtime rather than hard-coding them.
  local apkpath apkdir starter
  apkpath="$(a shell pm path "$SZ" 2>/dev/null | tr -d '\r' | sed 's/^package://' | head -1)"
  apkdir="$(a shell "dirname '$apkpath'" 2>/dev/null | tr -d '\r')"
  starter="$(a shell "ls $apkdir/lib/*/libshizuku.so 2>/dev/null" | tr -d '\r' | head -1)"
  [ -n "$starter" ] || { warn "Couldn't find Shizuku's starter — open the Shizuku app and tap Start once"; return; }
  a shell "$starter" >/dev/null 2>&1
  # The starter printing "exit with 0" only means the STARTER process exited
  # cleanly — NOT that shizuku_server survived (on some firmware, notably the
  # Gen-1's Android 9, the server is killed right after). Verify the real server
  # process is alive instead of trusting the starter's output. pgrep matches the
  # server's cmdline and excludes itself, so there's no false self-match.
  local sz_try=0
  while [ "$sz_try" -lt 6 ]; do
    if [ -n "$(a shell 'pgrep -f shizuku_server' 2>/dev/null | tr -d '\r')" ]; then
      ok "Shizuku server running"; return
    fi
    sz_try=$((sz_try + 1)); sleep 1
  done
  warn "Shizuku server didn't stay up (some firmware kills it on launch). Open the Shizuku app once and tap Start — or skip Shizuku: set Aurora Store to its Session installer, which routes through the system installer (usable via the Gen-1 overlay fix)."
}

install_apps() {
  # Silent adb-install of the configured apps. Works on every model — the only
  # reliable path on devices whose on-device installer dialog is broken.
  local tmp="$(dirname "$APK_GLOB")"; mkdir -p "$tmp"
  for spec in ${PREINSTALL_FDROID:-}; do
    local id="${spec%%:*}" vc=""
    case "$spec" in *:*) vc="${spec##*:}" ;; esac
    if [ -z "$vc" ]; then
      vc="$(curl -fsL "https://f-droid.org/api/v1/packages/$id" 2>/dev/null | grep -o '"suggestedVersionCode":[0-9]*' | grep -o '[0-9]*' | head -1)"
    fi
    [ -n "$vc" ] || { warn "Skipping $id (couldn't resolve a version)"; continue; }
    step "Installing $id"
    if curl -fsL "https://f-droid.org/repo/${id}_${vc}.apk" -o "$tmp/$id.apk" 2>/dev/null \
       && a install -r "$tmp/$id.apk" >/dev/null 2>&1; then ok "$id installed"; else warn "$id failed"; fi
    rm -f "$tmp/$id.apk"
  done
  for url in ${PREINSTALL_APKS:-}; do
    local f="$tmp/$(basename "$url")"
    step "Installing $(basename "$url")"
    if curl -fsL "$url" -o "$f" 2>/dev/null && a install -r "$f" >/dev/null 2>&1; then ok "installed"; else warn "failed"; fi
    rm -f "$f"
  done
}

push_assets() {
  [ -d "$ASSET_DIR" ] || { warn "No assets/ folder — skipping photos"; return; }
  local dir="/sdcard/Android/data/$PKG/files"
  a shell mkdir -p "$dir" >/dev/null 2>&1
  local first=1 n=0
  for img in "$ASSET_DIR"/*.jpg "$ASSET_DIR"/*.jpeg "$ASSET_DIR"/*.png; do
    [ -e "$img" ] || continue
    if [ "$first" = 1 ]; then a push "$img" "$dir/frame.jpg" >/dev/null 2>&1; first=0; else a push "$img" "$dir/" >/dev/null 2>&1; fi
    n=$((n+1))
  done
  [ "$n" -gt 0 ] && ok "Pushed $n image(s) to the photo frame" || warn "No images in assets/"
}

apply_system_tweaks() {
  step "Applying system display tweaks"
  # Hide the status bar across all apps by default. Swipe from the top still
  # reveals it transiently. Eliminates the white-on-white problem on
  # light-background apps (Aurora, Android Settings, etc.). Android 5.0+.
  a shell settings put global policy_control "immersive.status=*" >/dev/null 2>&1
  # (Forced dark mode was removed: it was a redundant second attempt at the Gen-1
  # white-on-white installer dialog — disable_installer_overlay is the real fix — and
  # it had unaudited side effects on stock UI. The status-bar hide above stays.)
  # Allow apps (including Immortal) to call internal Android APIs that would
  # otherwise be blocked by the hidden-API blacklist. 1 = warn-only (calls
  # succeed; logcat warning only). Covers both pre-P and P+ app targets.
  a shell settings put global hidden_api_policy_pre_p_apps 1 >/dev/null 2>&1
  a shell settings put global hidden_api_policy_p_apps 1 >/dev/null 2>&1
  a shell settings put global hidden_api_policy 1 >/dev/null 2>&1
  # Unlock developer options (needed for ADB tooling and the debug menu).
  a shell settings put global development_settings_enabled 1 >/dev/null 2>&1
  ok "System tweaks applied"
}

grant_perms() {
  step "Granting permissions"
  for p in $PERMISSIONS; do a shell pm grant "$PKG" "$p" >/dev/null 2>&1; done
  # Self-healing: lets Immortal reaffirm its screensaver settings if reset.
  a shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1
  # Lets the "Install an APK" browser see downloaded APKs, and the fleet agent
  # read/write /sdcard over WiFi.
  a shell pm grant "$PKG" android.permission.READ_EXTERNAL_STORAGE >/dev/null 2>&1
  a shell pm grant "$PKG" android.permission.WRITE_EXTERNAL_STORAGE >/dev/null 2>&1
  # Lets the fleet agent's /logcat endpoint read system-wide logs (READ_LOGS is a
  # development permission, so pm grant works). Harmless if it can't be granted.
  a shell pm grant "$PKG" android.permission.READ_LOGS >/dev/null 2>&1
  # Lets Immortal bring the photo frame back instantly when the system force-wakes
  # the screensaver (~2 min in, a quirk of Meta's power manager) even if another
  # app is in the foreground. SYSTEM_ALERT_WINDOW holders may start activities
  # from the background on Android 10.
  a shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
  # Lets Immortal install apps via the system PackageInstaller (the App Store / self-update
  # path now that the silent-install daemon is gone). The Portal's on-device "install unknown
  # apps" toggle is non-functional, so grant the source op directly here; combined with the
  # Gen-1 installer-overlay fix, the confirm dialog is then visible and usable.
  a shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1
  # Lets the app switcher (Quick buttons) list recently-used apps via UsageStatsManager —
  # getRecentTasks can't see other apps since Android 5. Harmless when the feature is off.
  a shell appops set "$PKG" GET_USAGE_STATS allow >/dev/null 2>&1
  # Device admin (force-lock only): lets Immortal turn the screen off for its idle and
  # overnight sleep features (and the Home Assistant screen control) via lockNow(). Warn
  # rather than swallow a failure — without it, screen-off silently won't work, which is a
  # confusing thing to debug after the fact.
  if a shell dpm set-active-admin "$PKG/.AdminReceiver" 2>&1 | grep -q "Success"; then
    ok "Screen-off (device admin) enabled"
  else
    warn "Couldn't enable screen-off device admin — screensaver sleep and the Home Assistant screen control won't work on this device. Re-run setup; if it keeps failing, check Device health in Immortal settings."
  fi
  # Lets Immortal read the device's active media sessions (native now-playing) for
  # the screensaver card + header mini-player. `cmd notification allow_listener`
  # writes the secure setting AND rebinds the listener reliably on A9/A10. The app
  # also self-enables on launch (WRITE_SECURE_SETTINGS), so this is belt-and-braces.
  a shell cmd notification allow_listener \
    "$PKG/com.immortal.launcher.MediaNotificationListenerService" >/dev/null 2>&1 || true
  ok "Permissions granted"
}

# Apps Immortal relaunches after a reboot (for ones with no boot receiver of their
# own, e.g. the Music Assistant / Sendspin player). Writes the per-device list
# Immortal reads on boot; also editable in-app under Settings > Start on boot.
configure_boot_apps() {
  local dir="/sdcard/Android/data/$PKG/files"
  a shell "mkdir -p '$dir'" >/dev/null 2>&1
  if [ -n "${BOOT_APPS:-}" ]; then
    step "Setting apps to relaunch on boot"
    a shell "printf '%s\n' $BOOT_APPS > '$dir/boot_apps.txt'" >/dev/null 2>&1
    ok "Boot-launch apps: $BOOT_APPS"
  else
    a shell "rm -f '$dir/boot_apps.txt'" >/dev/null 2>&1
  fi
}

disable_verifier() {
  [ "${DISABLE_VERIFIER:-true}" = true ] || return
  step "Disabling Meta's install verifier (lets the client install other apps on-device)"
  a shell pm disable-user --user 0 "$VERIFIER_PKG" >/dev/null 2>&1
  a shell settings put global package_verifier_enable 0 >/dev/null 2>&1
  ok "Verifier disabled"
}

disable_installer_overlay() {
  # GEN-1 PORTAL+ ONLY (Android 9). Meta ships a Runtime Resource Overlay (RRO)
  # that re-themes framework resources so the *stock* package-installer dialog
  # renders white-on-white: the confirm screen comes up blank with an invisible
  # "Install" button in the bottom-right corner. Disabling the overlay restores
  # the normal styling, so the on-device installer dialog becomes usable again.
  #
  # Why we do this even though Immortal's silent daemon already bypasses the
  # dialog: the daemon (and Shizuku) DON'T survive a reboot, but overlay state is
  # persisted by the framework in /data/system/overlays.xml and DOES. So a
  # rebooted Gen-1 with the daemon down still has a working stock installer —
  # third-party stores (Aurora/F-Droid) and APK sideloads keep working until the
  # kit is re-run. `cmd overlay` takes effect immediately; no reboot required, so
  # it won't kill the daemon/Shizuku the way a night-mode reboot would.
  [ "${DISABLE_INSTALLER_OVERLAY:-true}" = true ] || return
  local sdk; sdk="$(a shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
  # Newer Portals (API >= 29) have a working installer dialog and don't ship this
  # overlay — skip them so we don't touch theming that isn't broken.
  [ "${sdk:-99}" -lt 29 ] 2>/dev/null || return
  step "Fixing the on-device installer dialog (disabling Meta's white-on-white overlay)"
  local did=0
  for ov in $INSTALLER_OVERLAY_PKGS; do
    # Only act on overlays actually present on this device.
    a shell "cmd overlay list 2>/dev/null" | tr -d '\r' | grep -q "$ov" || continue
    a shell "cmd overlay disable --user 0 $ov" >/dev/null 2>&1 && { ok "Disabled $ov"; did=1; }
  done
  if [ "$did" = 1 ]; then
    # Marker the app reads (no hidden IOverlayManager API needed) to know the
    # stock dialog is usable, so the store can fall back to it when the daemon is
    # down instead of telling the user to reconnect.
    a shell settings put global immortal_overlay_fix 1 >/dev/null 2>&1
    ok "Installer dialog fix applied"
  else
    warn "No installer overlay found to disable (already fixed, or not a Gen-1)"
  fi
}

disable_ota() {
  # Ask the user (default YES). Blocking OTA stops a future Meta update from undoing this
  # setup (re-enabling the verifier / replacing the launcher) — but also forgoes OS/security
  # updates. DISABLE_OTA in config forces true/false for unattended runs; blank => ask
  # interactively (and default to blocking on a non-interactive run).
  local want="${DISABLE_OTA:-}"
  if [ -z "$want" ]; then
    if [ -t 0 ]; then
      printf "\n%sBlock Meta OS updates on this Portal?%s A future Meta OTA could undo this\n" "$B" "$N"
      printf "  setup (re-enable the verifier, replace the launcher). Blocking prevents that, but\n"
      printf "  also stops OS / security updates. %s[Y/n]%s " "$B" "$N"
      local ans; read -r ans || ans=""
      case "$ans" in [Nn]*) want=false ;; *) want=true ;; esac
    else
      want=true
    fi
  fi
  [ "$want" = true ] || { warn "Leaving Meta OS updates enabled"; return; }
  step "Disabling Meta OS updates (so a future OTA can't undo this setup)"
  for p in $OTA_PACKAGES; do a shell pm disable-user --user 0 "$p" >/dev/null 2>&1; done
  ok "OS updates disabled"
}

disable_presence() {
  # OFF BY DEFAULT. The system uses Meta's presence detector to choose ambient
  # vs sleep at the screen timeout (someone nearby → photos; empty room → real
  # sleep), and Immortal cooperates with that. Disable only if you want the
  # camera never used at all — the trade-off is the device can't tell an empty
  # room from an occupied one. Reversible — restore re-enables it.
  [ "${DISABLE_PRESENCE:-false}" = true ] || return
  step "Disabling Meta's presence detector (camera off; loses empty-room sleep smarts)"
  a shell pm disable-user --user 0 "$PRESENCE_PKG" >/dev/null 2>&1
  ok "Presence detector disabled"
}

snapshot_stock() {
  # Record this device's real stock components for an accurate restore. Done
  # once per device; a re-provision keeps the original values.
  if a shell "[ -f $STATE_FILE ]" >/dev/null 2>&1; then return; fi
  local home dream ddream
  # The stock home is whichever HOME activity isn't ours, the system resolver, or
  # Settings' fallback. query-activities lists them all as flattened components,
  # so this is reliable across Portal models even with our launcher installed.
  home="$(a shell 'cmd package query-activities --components -a android.intent.action.MAIN -c android.intent.category.HOME' 2>/dev/null \
            | tr -d '\r' \
            | grep -E '^[A-Za-z0-9_.]+/' \
            | grep -v "^$PKG/" \
            | grep -v '^android/' \
            | grep -v '^com.android.settings/' \
            | head -1)"
  # Screensaver: the live setting is the stock dream on a first provision (we
  # haven't overwritten it yet); guard against capturing ours on a re-run.
  dream="$(a shell settings get secure screensaver_components 2>/dev/null | tr -d '\r')"
  ddream="$(a shell settings get secure screensaver_default_component 2>/dev/null | tr -d '\r')"
  [ -n "$home" ] || home="$STOCK_HOME"
  case "$dream" in "$PKG"/*|""|null) dream="$STOCK_DREAM" ;; esac
  case "$ddream" in "$PKG"/*|""|null) ddream="$STOCK_DEFAULT_DREAM" ;; esac
  printf 'STOCK_HOME=%s\nSTOCK_DREAM=%s\nSTOCK_DEFAULT_DREAM=%s\n' "$home" "$dream" "$ddream" \
    | a shell "cat > $STATE_FILE" 2>/dev/null
  ok "Saved this device's stock launcher/screensaver for restore"
}

load_state() {
  # Pull the per-device snapshot (if any) over the config fallbacks.
  a shell "[ -f $STATE_FILE ]" >/dev/null 2>&1 || { warn "No saved snapshot on device — using config.env fallbacks for restore"; return; }
  local key val
  while IFS='=' read -r key val; do
    val="$(printf '%s' "$val" | tr -d '\r')"
    case "$key" in
      STOCK_HOME) [ -n "$val" ] && STOCK_HOME="$val" ;;
      STOCK_DREAM) [ -n "$val" ] && STOCK_DREAM="$val" ;;
      STOCK_DEFAULT_DREAM) [ -n "$val" ] && STOCK_DEFAULT_DREAM="$val" ;;
    esac
  done < <(a shell cat "$STATE_FILE" 2>/dev/null)
}

set_launcher() {
  [ "${SET_LAUNCHER:-true}" = true ] || return
  step "Setting custom home launcher"
  a shell cmd package set-home-activity "$HOME_ACTIVITY" >/dev/null 2>&1 && ok "Home = $HOME_ACTIVITY" || warn "Could not set launcher"
}

set_screensaver() {
  [ "${SET_SCREENSAVER:-true}" = true ] || return
  step "Setting custom screensaver (active + dock/idle default)"
  # Active component (what Settings shows) AND the dock/idle default — Portal
  # uses the latter for its docked photo-frame path, so set both.
  a shell settings put secure screensaver_components "$DREAM_SERVICE" >/dev/null 2>&1
  a shell settings put secure screensaver_default_component "$DREAM_SERVICE" >/dev/null 2>&1
  a shell settings put secure screensaver_enabled 1 >/dev/null 2>&1
  a shell settings put secure screensaver_activate_on_dock 1 >/dev/null 2>&1
  a shell settings put secure screensaver_activate_on_sleep 1 >/dev/null 2>&1
  ok "Screensaver = $DREAM_SERVICE"
}

# ----- Alexa restore (the "hey" free tier) -----------------------------------
# Revives the original Amazon Alexa client ("falcon") on this locked, unrooted
# Portal: reconstruct our patched+signed APK from the PUBLIC stock dump via our
# binary diff (we never host Amazon's binary), install it, apply the privileged
# grants, then install the "hey" wake-word app. Optional, opt-in, and NON-FATAL:
# a failure here never aborts an otherwise-successful provision. Config in
# config.env (ALEXA_* / FALCON_* / MILLENNIUM_*); local-path overrides let us
# test against built artifacts before the hosted URLs exist.
sha256() {
  if command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else echo ""; fi
}

restore_alexa() {
  local FP="${FALCON_PKG:-com.amazon.alexa.multimodal.falcon}"
  local SIM="$FP/com.amazon.alexa.multimodal.falcon.SIMActivity"
  # The SETUP entry: on a fresh device it runs falcon's OOBE → the Amazon CBL linking code; once
  # linked it just bootstraps the connection and yields to the launcher. (SIMActivity, by contrast,
  # only ever shows the debug client — never the sign-in — so we use this for sign-in + connect.)
  local SETUP="$FP/com.amazon.alexa.multimodal.LaunchActivity"
  local work="$SCRIPT_DIR/alexa"; mkdir -p "$work"
  local patched=""
  step "Restoring Amazon Alexa (reviving the original falcon client)"

  # Gate: Android 10+ Portals (e.g. the Portal Go) HARD-BLOCK background mic capture for
  # sideloaded apps — even a foregroundServiceType=microphone FGS is silenced — so falcon
  # never receives audio during the wake/button handoff (verified exhaustively on a clean
  # provision). Alexa revival is supported on A9-and-below ONLY for now. The launcher itself
  # still installs fine on A10. Override with ALEXA_FORCE_A10=1 to attempt anyway (for testing
  # other A10 families). See the mic-management notes / DISTRIBUTION.md.
  local sdk; sdk="$(a shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
  if [ -n "$sdk" ] && [ "$sdk" -ge 29 ] 2>/dev/null && [ "${ALEXA_FORCE_A10:-}" != 1 ]; then
    warn "Skipping Alexa — not supported on this Portal yet (Android 10+, SDK $sdk blocks background mic for sideloaded apps)."
    printf "  %sThe launcher is installed and working; only the Alexa restore is skipped.%s\n" "$D" "$N"
    printf "  %s(A10 support is in research. To attempt it anyway for testing: ALEXA_FORCE_A10=1 ./provision.sh --alexa)%s\n" "$D" "$N"
    return 0
  fi

  # 1. Obtain the patched+signed falcon APK — a local build if given, else
  #    reconstruct it byte-identically from the public stock APK + our diff.
  if [ -n "${FALCON_PATCHED_LOCAL:-}" ] && [ -f "$FALCON_PATCHED_LOCAL" ]; then
    patched="$FALCON_PATCHED_LOCAL"; ok "Using local patched falcon ($(basename "$patched"))"
  elif [ -n "${FALCON_PATCHED_URL:-}" ]; then
    # PRIMARY path: download the patched falcon directly. One cross-platform download — no bspatch
    # (Windows has none), no flaky firmware-dump fetch, no stock+diff checksum drift. Resumable.
    step "Downloading Alexa (falcon) — about 115 MB"
    rm -f "$work/falcon-patched.apk"   # never resume onto a stale/partial file — fetch fresh
    curl -fSL --retry 3 --retry-all-errors --retry-delay 2 --connect-timeout 30 \
      -o "$work/falcon-patched.apk" "$FALCON_PATCHED_URL" \
      || { warn "falcon download failed — check your connection, then re-run './provision.sh --alexa'."; return 1; }
    patched="$work/falcon-patched.apk"
    if [ -n "${FALCON_RESULT_SHA256:-}" ] && [ "$(sha256 "$patched")" != "$FALCON_RESULT_SHA256" ]; then
      warn "falcon download looks corrupted (checksum mismatch). Delete the 'alexa' folder next to this script, then re-run './provision.sh --alexa'."; return 1
    fi
    ok "Downloaded + verified"
  else
    # FALLBACK (only when FALCON_PATCHED_URL is blank): reconstruct from the public stock APK + our
    # binary diff. Needs bspatch — macOS/Linux only (Windows ships none).
    command -v bspatch >/dev/null 2>&1 || { warn "bspatch not found (macOS ships it; Linux: apt/brew install bsdiff). Skipping Alexa."; return 1; }
    local stock="${FALCON_STOCK_LOCAL:-$work/stock-falcon.apk}"
    if [ ! -f "$stock" ] || { [ -n "${FALCON_STOCK_SHA256:-}" ] && [ "$(sha256 "$stock")" != "$FALCON_STOCK_SHA256" ]; }; then
      [ -n "${FALCON_STOCK_URL:-}" ] || { warn "No FALCON_STOCK_URL configured — skipping Alexa."; return 1; }
      step "Downloading stock falcon (~120 MB) from the public firmware dump"
      curl -fSL --retry 2 -o "$work/stock-falcon.apk" "$FALCON_STOCK_URL" || { warn "Stock download failed — skipping Alexa."; return 1; }
      stock="$work/stock-falcon.apk"
    fi
    if [ -n "${FALCON_STOCK_SHA256:-}" ] && [ "$(sha256 "$stock")" != "$FALCON_STOCK_SHA256" ]; then warn "Stock falcon checksum mismatch — skipping Alexa."; return 1; fi
    local diff="${FALCON_BSDIFF_LOCAL:-$work/falcon.bsdiff}"
    if [ ! -f "$diff" ]; then
      [ -n "${FALCON_BSDIFF_URL:-}" ] || { warn "No falcon patch (FALCON_BSDIFF_URL/LOCAL) — skipping Alexa."; return 1; }
      step "Downloading the falcon patch"
      curl -fSL --retry 2 -o "$work/falcon.bsdiff" "$FALCON_BSDIFF_URL" || { warn "Patch download failed — skipping Alexa."; return 1; }
      diff="$work/falcon.bsdiff"
    fi
    step "Reconstructing the patched falcon (bspatch)"
    bspatch "$stock" "$work/falcon-patched.apk" "$diff" || { warn "bspatch failed — skipping Alexa."; return 1; }
    patched="$work/falcon-patched.apk"
    if [ -n "${FALCON_RESULT_SHA256:-}" ] && [ "$(sha256 "$patched")" != "$FALCON_RESULT_SHA256" ]; then warn "Reconstructed falcon checksum mismatch (bad stock or diff) — skipping Alexa."; return 1; fi
    ok "Reconstructed + verified byte-identical"
  fi

  # 2. Install falcon — `install -r` ONLY. NEVER uninstall: wiping falcon's data
  #    drops its Amazon registration and the next launch mints a NEW ghost device.
  step "Installing falcon"
  local out; out="$(a install -r "$patched" 2>&1)" || true
  if printf '%s' "$out" | grep -q Success; then ok "falcon installed"
  elif printf '%s' "$out" | grep -q INSTALL_FAILED_VERSION_DOWNGRADE; then ok "falcon already current"
  elif printf '%s' "$out" | grep -qi duplicate; then warn "Duplicate-permission conflict with com.amazon.dee.app — needs the coexistence build. Skipping."; return 1
  elif a shell pm path "$FP" >/dev/null 2>&1; then warn "install -r reported an issue but falcon is present; continuing"
  else warn "falcon install failed — skipping Alexa."; return 1; fi

  # 3. Provision falcon (privileged grants; persist across reboot).
  step "Provisioning falcon"
  a shell pm grant "$FP" android.permission.READ_PHONE_STATE     >/dev/null 2>&1
  a shell pm grant "$FP" android.permission.INTERACT_ACROSS_USERS >/dev/null 2>&1
  a shell pm grant "$FP" android.permission.RECORD_AUDIO          >/dev/null 2>&1
  a shell settings put secure user_setup_complete 1               >/dev/null 2>&1
  a shell appops set "$FP" SYSTEM_ALERT_WINDOW allow              >/dev/null 2>&1
  ok "falcon provisioned"

  # 3b. Surface the Amazon sign-in NOW, right after install, so a fresh Portal shows the linking
  #     code immediately and the user can complete registration WHILE we install the wake app and
  #     wait to connect — turning a fresh setup into a single pass. (Already-linked Portals just
  #     reconnect; this launch is harmless for them.) We clear logcat here so the ReadyState we
  #     watch for below is from this launch onward, not a stale entry from a prior run.
  step "Opening Alexa to link your Amazon account"
  a logcat -c >/dev/null 2>&1 || true
  a shell dumpsys deviceidle whitelist +"$FP" >/dev/null 2>&1   # skip the "run in background?" prompt → straight to the code
  a shell am start -n "$SETUP" >/dev/null 2>&1
  printf "  %sIf this Portal isn't linked yet, an Amazon sign-in is now on screen — go to amazon.com/code\n  and enter the code shown. You can do this while the rest of setup runs.%s\n" "$Y" "$N"

  # 4. millennium = the "hey" wake-word app (drives falcon hands-free).
  local MP="${MILLENNIUM_PKG:-com.millennium}"
  local mapk="${MILLENNIUM_APK_LOCAL:-}"
  if [ -z "$mapk" ] && [ -n "${MILLENNIUM_APK_URL:-}" ]; then
    step "Downloading the hey (millennium) app"
    if curl -fSL --retry 2 -o "$work/millennium.apk" "$MILLENNIUM_APK_URL" 2>/dev/null; then mapk="$work/millennium.apk"; else warn "millennium download failed (Alexa text/voice still works; wake word needs it)"; fi
  fi
  if [ -n "$mapk" ] && [ -f "$mapk" ]; then
    step "Installing hey (millennium)"
    a install -r "$mapk" >/dev/null 2>&1 && ok "millennium installed" || warn "millennium install failed"
  fi
  a shell pm path "$MP" >/dev/null 2>&1 && a shell pm grant "$MP" android.permission.RECORD_AUDIO >/dev/null 2>&1

  # 5. Wait for ReadyState — EVENT-DRIVEN, not on a timer. falcon is already on screen (3b).
  #
  # falcon logs `AccountRegisteredCondition: isMet` the instant the Amazon account is linked, and
  # NEVER before (the SIM state machine only initializes once there's an account) — so this is a
  # precise "sign-in just completed" signal that can't fire mid-sign-in. Behaviour:
  #   • already-linked Portal → connects on its own → we see `in ReadyState` and finish, no restart.
  #   • fresh Portal → after we see `isMet` it parks in IgnoreWhileDisconnectedState and won't
  #     connect on its own, so we give it a short grace to self-connect and then force-stop+relaunch
  #     to kick it (re-kicking if still stuck). We only ever kick AFTER `isMet`, so an in-progress
  #     sign-in is never interrupted. The overall cap is just a safety net.
  step "Waiting for Alexa to connect"
  printf "  %sFinish any on-screen Amazon sign-in (amazon.com/code) — this connects automatically once you do…%s\n" "$D" "$N"
  local i=0 ready=0 reg_at=-1 last_kick=-100 log
  while [ "$i" -lt 72 ]; do                                    # ~6 min safety cap (72 × 5s)
    log="$(a logcat -d 2>/dev/null)"
    if printf '%s' "$log" | grep -q 'in ReadyState'; then ready=1; break; fi
    if [ "$reg_at" -lt 0 ] && printf '%s' "$log" | grep -q 'AccountRegisteredCondition: isMet'; then
      reg_at="$i"; step "Amazon account linked — connecting Alexa"
    fi
    # registered + not yet connected: ~20s grace for self-connect, then kick; re-kick every ~40s if stuck.
    if [ "$reg_at" -ge 0 ] && [ $((i - reg_at)) -ge 4 ] && [ $((i - last_kick)) -ge 8 ]; then
      a shell am force-stop "$FP" >/dev/null 2>&1
      a shell am start -n "$SETUP" >/dev/null 2>&1   # bootstraps the connection + yields to the launcher
      last_kick="$i"
    fi
    sleep 5; i=$((i + 1))
  done
  if [ "$ready" = 1 ]; then
    a shell pm path "$MP" >/dev/null 2>&1 && a shell am start -n "$MP/com.millennium.ui.HeyActivity" >/dev/null 2>&1
    ok "Alexa connected (ReadyState) — say \"Hey Alexa, what's the weather?\""
    printf "  %sOnce linked, you can hide falcon's icon from the launcher — it runs headless.%s\n" "$D" "$N"
  else
    warn "Alexa didn't connect within ~6 min. Check Wi-Fi + that the Amazon account is linked, then re-run './provision.sh --alexa'."
  fi
}

maybe_restore_alexa() {
  # A9-and-below only. A10+ hard-blocks background mic for sideloaded apps, so falcon never
  # gets audio — skip the question entirely on newer Portals (it used to appear, then no-op
  # inside restore_alexa, which was confusing on the Portal Go). Override: ALEXA_FORCE_A10=1.
  local sdk; sdk="$(a shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
  if [ -n "$sdk" ] && [ "$sdk" -ge 29 ] 2>/dev/null && [ "${ALEXA_FORCE_A10:-}" != 1 ]; then
    return 0
  fi
  # RESTORE_ALEXA in config.env forces on/off for unattended runs; blank => ask
  # (only when interactive — a piped/CI run defaults to skipping).
  local want="${RESTORE_ALEXA:-}"
  if [ -z "$want" ]; then
    if [ -t 0 ]; then
      printf "\n%sRestore Amazon Alexa on this Portal?%s Revives the original Alexa app —\n" "$B" "$N"
      printf "  hands-free \"Hey Alexa\", with text, voice and visual answers. %s[y/N]%s " "$B" "$N"
      local ans; read -r ans || ans=""
      case "$ans" in [Yy]*) want=true ;; *) want=false ;; esac
    else
      want=false
    fi
  fi
  [ "$want" = true ] && restore_alexa || true
}

restore_alexa_undo() {
  # Remove only OUR wake-word app. Leave falcon installed on purpose:
  # uninstalling it drops the Amazon registration and mints a new ghost device.
  local MP="${MILLENNIUM_PKG:-com.millennium}"
  if a shell pm path "$MP" >/dev/null 2>&1; then
    step "Removing the hey (millennium) wake-word app"
    a uninstall "$MP" >/dev/null 2>&1 && ok "millennium removed" || warn "Couldn't remove millennium"
    warn "Amazon Alexa (falcon) is left installed on purpose — uninstalling it would register a NEW device with Amazon. Remove it by hand only if you understand that."
  fi
}

# ----- modes -----------------------------------------------------------------
json_escape() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }

enable_fleet() {
  # Turn on the in-app Fleet Agent and record this device for the laptop tool.
  # We can't write the app's private prefs from the shell user, so we hand it a
  # provision.json in its shell-writable external files dir and (re)launch it; the
  # app applies it on startup and writes agent.json (with the generated token)
  # back for us to read. The agent is the persistent WiFi channel; we never switch
  # the device to adb-over-WiFi here (that restarts adbd, killing the shell helpers).
  [ "${ENABLE_FLEET:-true}" = true ] || return
  step "Enabling the WiFi fleet agent"
  local files_dir="/sdcard/Android/data/$PKG/files/fleet"
  local name="${FLEET_NAME:-}"
  if [ -z "$name" ] && [ -t 0 ]; then
    printf "  %sName this Portal for the fleet dashboard (e.g. \"Living Room\"), Enter to skip: %s" "$D" "$N"
    IFS= read -r name || name=""
  fi
  a shell "mkdir -p '$files_dir'" >/dev/null 2>&1
  if [ -n "$name" ]; then
    a shell "echo '{\"enabled\":true,\"name\":\"$(json_escape "$name")\"}' > '$files_dir/provision.json'" >/dev/null 2>&1
  else
    a shell "echo '{\"enabled\":true}' > '$files_dir/provision.json'" >/dev/null 2>&1
  fi
  # Force a fresh process so ImmortalApp.onCreate consumes provision.json.
  a shell "am force-stop $PKG" >/dev/null 2>&1
  a shell "am start -n $HOME_ACTIVITY" >/dev/null 2>&1
  # Poll for the agent manifest the app writes back.
  local json="" try=0
  while [ "$try" -lt 15 ]; do
    json="$(a shell "cat '$files_dir/agent.json' 2>/dev/null" | tr -d '\r')"
    case "$json" in *'"enabled":true'*) break ;; esac
    try=$((try + 1)); sleep 1
  done
  local token; token="$(printf '%s' "$json" | sed -n 's/.*"token":"\([0-9a-f]*\)".*/\1/p')"
  if [ -z "$token" ]; then
    warn "Fleet agent didn't report a token — open Immortal once, then re-run ./provision.sh --fleet"
    return
  fi
  [ -z "$name" ] && name="$(printf '%s' "$json" | sed -n 's/.*"name":"\([^"]*\)".*/\1/p')"
  local port; port="$(printf '%s' "$json" | sed -n 's/.*"port":\([0-9]*\).*/\1/p')"
  [ -n "$port" ] || port="${FLEET_AGENT_PORT:-8723}"

  # Capture the WiFi IP (USB still connected) so the laptop tool's inventory knows
  # where to reach this Portal's agent.
  local ip; ip="$(a shell "ip -f inet addr show wlan0 2>/dev/null" | sed -n 's/.*inet \([0-9.]*\).*/\1/p' | head -1)"

  record_fleet_inventory "$name" "${ip:-}" "$token" "$port"
  ok "Fleet agent enabled${name:+ as \"$name\"}${ip:+ at $ip:$port}"
}

record_fleet_inventory() {
  # One file per device (keyed by serial) under fleet/ — robust to maintain from
  # shell and trivial for the laptop tool to glob. Contains the agent TOKEN, so
  # fleet/ is gitignored.
  local name="$1" ip="$2" token="$3" port="$4"
  local serial; serial="${ANDROID_SERIAL:-$(a get-serialno 2>/dev/null | tr -d '\r')}"
  local model; model="$(a shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
  mkdir -p fleet
  cat > "fleet/${serial}.json" <<EOF
{
  "serial": "$serial",
  "name": "$(json_escape "$name")",
  "model": "$(json_escape "$model")",
  "ip": "$ip",
  "agentPort": $port,
  "token": "$token"
}
EOF
  ok "Recorded fleet/${serial}.json"
}

enable_wifi_adb_now() {
  # ON-DEMAND ONLY — deliberately NOT part of provisioning. Raw adb-over-WiFi is a
  # power-user convenience (remote shell / scrcpy mirroring). Two honest caveats:
  #   * `adb tcpip` restarts adbd, which STOPS shell-started helpers (Shizuku)
  #     until the next USB kit run or a reboot.
  #   * It does NOT survive a reboot (the TCP port needs a root-only prop here).
  # The Fleet AGENT is the persistent channel for managing the device — including
  # file transfer and logcat — so reach for this only when you specifically need
  # a raw adb shell or scrcpy.
  resolve_adb
  wait_for_device
  local ip; ip="$(a shell "ip -f inet addr show wlan0 2>/dev/null" | sed -n 's/.*inet \([0-9.]*\).*/\1/p' | head -1)"
  [ -n "$ip" ] || die "This Portal isn't on WiFi."
  step "Enabling adb-over-WiFi (temporary; pauses Shizuku)"
  a tcpip 5555 >/dev/null 2>&1 || die "adb tcpip 5555 failed."
  ok "adb-over-WiFi live — connect with: adb connect $ip:5555"
  warn "Shizuku is now paused; re-run the kit over USB (or reboot) to restore it."
}

do_provision() {
  printf "%sPortal Provisioner%s\n" "$B" "$N"
  printf "%sThis will modify your Portal: install an app, replace the home screen and screensaver,\nand disable Meta's app-install verifier. Run with --restore to undo. %s\n\n" "$D" "$N"
  resolve_adb
  wait_for_device
  install_client
  start_shizuku
  install_apps
  push_assets
  grant_perms
  apply_system_tweaks
  disable_verifier
  disable_installer_overlay
  disable_ota
  disable_presence
  snapshot_stock
  set_launcher
  set_screensaver
  enable_fleet
  configure_boot_apps
  maybe_restore_alexa
  a shell input keyevent KEYCODE_HOME >/dev/null 2>&1
  printf "\n%s✓ Done. Your Portal is provisioned.%s\n" "$G$B" "$N"
  printf "%sTo undo everything: re-run and choose restore (./provision.sh --restore).%s\n" "$D" "$N"
}

do_restore() {
  printf "%sPortal Restore%s\n\n" "$B" "$N"
  resolve_adb
  wait_for_device
  load_state # pull this device's real stock components (falls back to config)
  step "Restoring system display settings"
  a shell settings delete global policy_control >/dev/null 2>&1
  a shell settings delete secure ui_night_mode >/dev/null 2>&1
  a shell settings delete global hidden_api_policy_pre_p_apps >/dev/null 2>&1
  a shell settings delete global hidden_api_policy_p_apps >/dev/null 2>&1
  a shell settings delete global hidden_api_policy >/dev/null 2>&1
  a shell settings put global development_settings_enabled 0 >/dev/null 2>&1
  ok "System settings restored"
  step "Re-enabling Meta's install verifier"
  a shell pm enable "$VERIFIER_PKG" >/dev/null 2>&1
  a shell settings put global package_verifier_enable 1 >/dev/null 2>&1; ok "Verifier restored"
  step "Re-enabling Meta's installer overlay"
  for ov in $INSTALLER_OVERLAY_PKGS; do
    a shell "cmd overlay list 2>/dev/null" | tr -d '\r' | grep -q "$ov" \
      && a shell "cmd overlay enable --user 0 $ov" >/dev/null 2>&1
  done
  a shell settings delete global immortal_overlay_fix >/dev/null 2>&1; ok "Installer overlay restored"
  step "Re-enabling Meta OS updates"
  for p in $OTA_PACKAGES; do a shell pm enable "$p" >/dev/null 2>&1; done; ok "OS updates restored"
  step "Re-enabling Meta's presence detector"
  a shell pm enable "$PRESENCE_PKG" >/dev/null 2>&1; ok "Presence detector restored"
  step "Removing Immortal's screen-off device admin"
  # Android refuses to let the shell force-remove a non-test device admin, so this
  # usually fails on a real device — don't claim success when it didn't. The active
  # admin also blocks `adb uninstall` until it's deactivated on-device.
  if a shell dpm remove-active-admin "$PKG/.AdminReceiver" 2>&1 | grep -qi "success\|removed"; then
    ok "Device admin removed"
  else
    warn "Couldn't remove the device admin from here (Android blocks shell removal of a non-test admin)."
    printf "  %sTo fully remove Immortal: on the Portal, deactivate it under Settings > device admin\n  apps, then run: adb uninstall %s%s\n" "$D" "$PKG" "$N"
  fi
  a shell cmd notification disallow_listener \
    "$PKG/com.immortal.launcher.MediaNotificationListenerService" >/dev/null 2>&1 || true
  a shell "rm -f /sdcard/Android/data/$PKG/files/boot_apps.txt" >/dev/null 2>&1 || true
  restore_alexa_undo
  step "Restoring stock launcher"
  a shell cmd package set-home-activity "$STOCK_HOME" >/dev/null 2>&1; ok "Home restored ($STOCK_HOME)"
  step "Restoring stock screensaver"
  a shell settings put secure screensaver_components "$STOCK_DREAM" >/dev/null 2>&1
  a shell settings put secure screensaver_default_component "$STOCK_DEFAULT_DREAM" >/dev/null 2>&1
  ok "Screensaver restored"
  a shell input keyevent KEYCODE_HOME >/dev/null 2>&1
  printf "\n%s✓ Stock Portal settings restored.%s\n" "$G$B" "$N"
  printf "%sThe client app is left installed — uninstall with: adb uninstall %s%s\n" "$D" "$PKG" "$N"
}

do_status() {
  resolve_adb; wait_for_device
  step "Current state"
  local pc dm
  pc="$(a shell settings get global policy_control 2>/dev/null | tr -d '\r')"
  dm="$(a shell settings get secure ui_night_mode 2>/dev/null | tr -d '\r')"
  printf "  status bar: %s\n" "$(printf '%s' "$pc" | grep -q 'immersive' && echo 'hidden (immersive)' || echo 'stock')"
  printf "  dark mode:  %s\n" "$([ "$dm" = "2" ] && echo 'on' || echo 'off')"
  printf "  home:       %s\n" "$(a shell 'cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME' 2>/dev/null | awk -F= '/packageName/{print $2; exit}' | tr -d '\r')"
  printf "  screensaver:%s\n" " $(a shell settings get secure screensaver_components 2>/dev/null | tr -d '\r')"
  printf "  verifier:   %s\n" "$(a shell pm list packages -d "$VERIFIER_PKG" 2>/dev/null | tr -d '\r' | grep -q . && echo disabled || echo enabled)"
  printf "  installer dialog: %s\n" "$([ "$(a shell settings get global immortal_overlay_fix 2>/dev/null | tr -d '\r')" = "1" ] && echo 'fixed (overlay disabled)' || echo 'stock')"
  printf "  OS updates: %s\n" "$(a shell pm list packages -d 2>/dev/null | tr -d '\r' | grep -qE 'alohaotasetup|otaui' && echo disabled || echo enabled)"
  printf "  client:     %s\n" "$(a shell pm list packages "$PKG" 2>/dev/null | tr -d '\r' | grep -q . && echo installed || echo 'not installed')"
}

case "${1:-}" in
  --restore|-r) do_restore ;;
  --status|-s)  do_status ;;
  --apps|-a)    resolve_adb; wait_for_device; install_apps ;;
  --overlay-fix) resolve_adb; wait_for_device; disable_installer_overlay ;;
  --shizuku|-z) resolve_adb; wait_for_device; start_shizuku ;;
  --fleet|-f)   resolve_adb; wait_for_device; enable_fleet ;;
  --wifi-adb)   enable_wifi_adb_now ;;
  --alexa|-A)   resolve_adb; wait_for_device; restore_alexa ;;
  --help|-h)    sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//' ;;
  "")           do_provision ;;
  *)            die "Unknown option: $1 (use --restore, --status, or no argument)" ;;
esac
