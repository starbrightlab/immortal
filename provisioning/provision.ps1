<#
  Copyright (c) Meta Platforms, Inc. and affiliates.
  Licensed under the MIT license found in the LICENSE file in the repo root.

  Windows Portal provisioner. Finds (or downloads) ADB, waits for a connected
  Portal, then installs the client app, pushes photos, grants permissions,
  disables Meta's verifier, and sets the custom launcher + screensaver.

  Usage:
    powershell -ExecutionPolicy Bypass -File provision.ps1            # provision
    powershell -ExecutionPolicy Bypass -File provision.ps1 -Restore   # undo
    powershell -ExecutionPolicy Bypass -File provision.ps1 -Status    # show state
    powershell -ExecutionPolicy Bypass -File provision.ps1 -OverlayFix # fix Gen-1 installer dialog
    powershell -ExecutionPolicy Bypass -File provision.ps1 -Alexa      # restore the original Amazon Alexa app
#>
param([switch]$Restore, [switch]$Status, [switch]$Apps, [switch]$Shizuku, [switch]$OverlayFix, [switch]$Fleet, [switch]$WifiAdb, [switch]$Alexa)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

# Stock Windows PowerShell 5.1 defaults to old TLS and an IE-based HTML parser,
# both of which break HTTPS downloads from Google / GitHub / F-Droid (the kit
# downloads platform-tools and APKs at runtime). Force TLS 1.2 and basic parsing
# so the download steps work on a clean Windows machine.
try {
  [Net.ServicePointManager]::SecurityProtocol =
      [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
} catch {}
$PSDefaultParameterValues['Invoke-WebRequest:UseBasicParsing'] = $true

function Step($m){ Write-Host "==> $m" -ForegroundColor Cyan }
function Ok($m){ Write-Host "  [ok] $m" -ForegroundColor Green }
function Warn($m){ Write-Host "  [!] $m" -ForegroundColor Yellow }
function Die($m){ Write-Host "ERROR: $m" -ForegroundColor Red; exit 1 }

# ----- load config.env -------------------------------------------------------
if (-not (Test-Path config.env)) { Die "config.env not found next to this script." }
$cfg = @{}
Get-Content config.env | ForEach-Object {
  $line = $_.Trim()
  if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
    $i = $line.IndexOf("="); $val = $line.Substring($i+1).Trim().Trim('"').Trim("'")
    $cfg[$line.Substring(0,$i).Trim()] = $val
  }
}

# ----- resolve adb -----------------------------------------------------------
function Resolve-Adb {
  $bundled = Join-Path $ScriptDir "platform-tools\adb.exe"
  if (Test-Path $bundled) { return $bundled }
  $onPath = (Get-Command adb -ErrorAction SilentlyContinue)
  if ($onPath) { return $onPath.Source }
  Step "Android platform-tools (adb) not found - downloading the official package from Google"
  $url = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
  $zip = Join-Path $ScriptDir "platform-tools.zip"
  Invoke-WebRequest -Uri $url -OutFile $zip
  Expand-Archive -Path $zip -DestinationPath $ScriptDir -Force
  Remove-Item $zip
  if (-not (Test-Path $bundled)) { Die "adb missing after download." }
  Ok "platform-tools installed locally"
  return $bundled
}
$ADB = Resolve-Adb
function A { & $ADB @args }

# ----- wait for an authorized device -----------------------------------------
function Wait-Device {
  Step "Looking for your Portal"
  A start-server | Out-Null
  $plug=$false; $auth=$false
  while ($true) {
    # adb refuses every command when more than one device is attached - without
    # this the run dies later at the first install with a bare "Install failed."
    # Offer a numbered pick-list (model + serial) instead.
    $dlist = @(A devices -l | Select-Object -Skip 1 | Where-Object { $_ -match "^\S+\s+device\s" })
    $devs = @($dlist | ForEach-Object { ($_ -split "\s+")[0] })
    if (-not $env:ANDROID_SERIAL -and $devs.Count -gt 1) {
      Warn "More than one device is connected - which one is this setup for?"
      for ($i = 0; $i -lt $dlist.Count; $i++) {
        $m = if ($dlist[$i] -match 'model:(\S+)') { $Matches[1] } else { 'unknown' }
        Write-Host ("    {0}) {1}  ({2})" -f ($i + 1), $m, $devs[$i])
      }
      do {
        $sel = Read-Host "  Number"
      } until ($sel -match '^\d+$' -and [int]$sel -ge 1 -and [int]$sel -le $devs.Count)
      $env:ANDROID_SERIAL = $devs[[int]$sel - 1]
      Ok "Using $($devs[[int]$sel - 1])"
      continue
    }
    if ($env:ANDROID_SERIAL) {
      # Pinned (by the user, or by the prompt above): adb honours ANDROID_SERIAL.
      $state = "$(A get-state 2>$null)".Trim()
      if ($state -ne "device") { $state = "" }
    } elseif ($devs.Count -eq 1) {
      # Pin the one authorized device so a second one appearing mid-run
      # (or a lingering unauthorized entry) can't break later commands.
      $env:ANDROID_SERIAL = $devs[0]
      $state = "device"
    } else {
      $line = (A devices | Select-Object -Skip 1 | Where-Object { $_.Trim() } | Select-Object -First 1)
      $state = if ($line) { ($line -split "\s+")[1] } else { "" }
    }
    switch ($state) {
      "device" {
        $model = "$(A shell getprop ro.product.model)".Trim()
        Ok "Connected: $model"
        if ($model -and $model -notlike "*Portal*") { Warn "This doesn't look like a Portal (model: $model). Continuing." }
        return
      }
      "unauthorized" { if (-not $auth) { Warn "On the Portal screen, tap Allow (check 'Always allow from this computer')."; $auth=$true } }
      "" { if (-not $plug) { Warn "Plug the Portal into this PC via USB-C. On the Portal: Settings > Debug > ADB Enabled."; $plug=$true } }
      default { Warn "Device state: $state - waiting..." }
    }
    Start-Sleep -Seconds 2
  }
}

# ----- actions ---------------------------------------------------------------
function Resolve-ReleaseApkUrl {
  # Explicit pin wins; otherwise ask GitHub for the latest release's .apk asset on
  # RELEASE_REPO, so versioned asset names (immortal-<version>.apk) keep working.
  if ($cfg["RELEASE_APK_URL"]) { return $cfg["RELEASE_APK_URL"] }
  if (-not $cfg["RELEASE_REPO"]) { return $null }
  try {
    $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$($cfg["RELEASE_REPO"])/releases/latest" `
      -Headers @{ "User-Agent" = "immortal-provisioner"; "Accept" = "application/vnd.github+json" }
    return ($rel.assets | Where-Object { $_.name -like "*.apk" } | Select-Object -First 1).browser_download_url
  } catch { return $null }
}
function Install-Client {
  $apk = Get-ChildItem -Path $cfg["APK_GLOB"] -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not $apk) {
    $url = Resolve-ReleaseApkUrl
    if (-not $url) { Die "No local APK in apks\ and couldn't resolve a release APK. Drop a signed APK in apks\, or set RELEASE_APK_URL / RELEASE_REPO in config.env." }
    Step "No local APK - downloading the latest Immortal release"
    $dir = Split-Path -Parent $cfg["APK_GLOB"]
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $dest = Join-Path $dir "immortal.apk"
    Invoke-WebRequest -Uri $url -OutFile $dest
    $apk = Get-Item $dest
    Ok "Downloaded $($apk.Name)"
  }
  if (-not $apk) { Die "No client APK found matching '$($cfg["APK_GLOB"])'. Drop your signed APK in apks\." }
  Step "Installing client app ($($apk.Name))"
  A install -r -d $apk.FullName | Out-Null
  Ok "Installed $($cfg["PKG"])"
}
function Start-Shizuku {
  # Shizuku is a privileged broker started once over ADB. We install + start it on
  # EVERY Portal: it's broadly useful (Aurora's silent installs; the future
  # on-device Alexa-restore path that needs the privileged grants) and harmless
  # where unused. The Gen-1 Portal+ especially needs it because its stock installer
  # dialog is broken. The server does NOT survive a reboot; re-run with -Shizuku to restart.
  $SZ = "moe.shizuku.privileged.api"
  $installed = (A shell pm list packages $SZ) -match "package:$SZ"
  if (-not $installed) {
    if ($cfg["SHIZUKU_APK_URL"]) {
      Step "Installing Shizuku (privileged broker - useful on every Portal)"
      $tmp = Join-Path (Split-Path -Parent $cfg["APK_GLOB"]) "shizuku.apk"
      New-Item -ItemType Directory -Force -Path (Split-Path -Parent $tmp) | Out-Null
      try { Invoke-WebRequest $cfg["SHIZUKU_APK_URL"] -OutFile $tmp; A install -r $tmp | Out-Null; Ok "Shizuku installed" }
      catch { Warn "Shizuku install failed - skipping"; Remove-Item $tmp -ErrorAction SilentlyContinue; return }
      Remove-Item $tmp -ErrorAction SilentlyContinue
    } else { return }  # No SHIZUKU_APK_URL configured: nothing to do.
  }
  Step "Starting Shizuku server (for third-party silent installs)"
  # Resolve the version/ABI-specific starter binary at runtime (the install-dir
  # hash and ABI vary per device, so don't hard-code them).
  $apkpath = ("$(A shell pm path $SZ)" -replace 'package:', '').Trim() -split "`n" | Select-Object -First 1
  $apkdir  = "$(A shell "dirname '$apkpath'")".Trim()
  $starter = "$(A shell "ls $apkdir/lib/*/libshizuku.so 2>/dev/null")".Trim() -split "`n" | Select-Object -First 1
  if (-not $starter) { Warn "Couldn't find Shizuku's starter - open the Shizuku app and tap Start once"; return }
  A shell "$starter" | Out-Null
  # "exit with 0" only means the STARTER exited - not that shizuku_server survived
  # (some firmware, e.g. the Gen-1's Android 9, kills it). Verify the real server
  # process; pgrep matches its cmdline and excludes itself (no false self-match).
  for ($i = 0; $i -lt 6; $i++) {
    $up = (A shell 'pgrep -f shizuku_server') -replace "`r", ""
    if ($up -match '\d') { Ok "Shizuku server running"; return }
    Start-Sleep -Seconds 1
  }
  Warn "Shizuku server didn't stay up (some firmware kills it). Open the Shizuku app once and tap Start, or set Aurora Store to its Session installer (it routes through the system installer, usable via the Gen-1 overlay fix)."
}
function Install-Apps {
  # Silent adb-install of configured apps - the reliable path on models whose
  # on-device installer dialog is broken (e.g. Gen-1 Portal+).
  $tmp = Split-Path -Parent $cfg["APK_GLOB"]; New-Item -ItemType Directory -Force -Path $tmp | Out-Null
  foreach ($spec in ($cfg["PREINSTALL_FDROID"] -split "\s+")) {
    if (-not $spec) { continue }
    $id = ($spec -split ":")[0]; $vc = ""
    if ($spec -match ":") { $vc = ($spec -split ":")[1] }
    if (-not $vc) { try { $vc = (Invoke-RestMethod "https://f-droid.org/api/v1/packages/$id").suggestedVersionCode } catch {} }
    if (-not $vc) { Warn "Skipping $id (couldn't resolve a version)"; continue }
    Step "Installing $id"
    $f = Join-Path $tmp "$id.apk"
    try { Invoke-WebRequest "https://f-droid.org/repo/$($id)_$vc.apk" -OutFile $f; A install -r $f | Out-Null; Ok "$id installed" } catch { Warn "$id failed" }
    Remove-Item $f -ErrorAction SilentlyContinue
  }
  foreach ($url in ($cfg["PREINSTALL_APKS"] -split "\s+")) {
    if (-not $url) { continue }
    $name = [System.IO.Path]::GetFileName($url); $f = Join-Path $tmp $name
    Step "Installing $name"
    try { Invoke-WebRequest $url -OutFile $f; A install -r $f | Out-Null; Ok "installed" } catch { Warn "failed" }
    Remove-Item $f -ErrorAction SilentlyContinue
  }
}
function Push-Assets {
  $dir = "/sdcard/Android/data/$($cfg["PKG"])/files"
  A shell mkdir -p $dir | Out-Null
  $imgs = Get-ChildItem -Path $cfg["ASSET_DIR"] -Include *.jpg,*.jpeg,*.png -File -ErrorAction SilentlyContinue
  if (-not $imgs) { Warn "No images in $($cfg["ASSET_DIR"])"; return }
  $first=$true; $n=0
  foreach ($img in $imgs) {
    if ($first) { A push $img.FullName "$dir/frame.jpg" | Out-Null; $first=$false } else { A push $img.FullName "$dir/" | Out-Null }
    $n++
  }
  Ok "Pushed $n image(s) to the photo frame"
}
function Apply-SystemTweaks {
  Step "Applying system display tweaks"
  # Hide the status bar across all apps by default. Swipe from the top still
  # reveals it transiently. Eliminates the white-on-white problem on
  # light-background apps (Aurora, Android Settings, etc.). Android 5.0+.
  A shell settings put global policy_control "immersive.status=*" | Out-Null
  # (Forced dark mode was removed: it was a redundant second attempt at the Gen-1
  # white-on-white installer dialog - Disable-InstallerOverlay is the real fix - and
  # had unaudited side effects on stock UI. The status-bar hide above stays.)
  # Allow apps to call internal Android APIs blocked by the hidden-API blacklist.
  # 1 = warn-only (calls succeed; logcat warning only). Covers pre-P and P+ targets.
  A shell settings put global hidden_api_policy_pre_p_apps 1 | Out-Null
  A shell settings put global hidden_api_policy_p_apps 1 | Out-Null
  A shell settings put global hidden_api_policy 1 | Out-Null
  # Unlock developer options (needed for ADB tooling and the debug menu).
  A shell settings put global development_settings_enabled 1 | Out-Null
  Ok "System tweaks applied"
}
function Grant-Perms {
  Step "Granting permissions"
  foreach ($p in ($cfg["PERMISSIONS"] -split "\s+")) { if ($p) { A shell pm grant $cfg["PKG"] $p | Out-Null } }
  # Self-healing: lets Immortal reaffirm its screensaver settings if reset.
  A shell pm grant $cfg["PKG"] android.permission.WRITE_SECURE_SETTINGS | Out-Null
  # Lets the "Install an APK" browser see downloaded APKs, and the fleet agent
  # read/write /sdcard over WiFi.
  A shell pm grant $cfg["PKG"] android.permission.READ_EXTERNAL_STORAGE | Out-Null
  A shell pm grant $cfg["PKG"] android.permission.WRITE_EXTERNAL_STORAGE | Out-Null
  # Lets the fleet agent's /logcat endpoint read system-wide logs (development perm).
  A shell pm grant $cfg["PKG"] android.permission.READ_LOGS | Out-Null
  # Lets Immortal bring the photo frame back instantly when the system force-wakes
  # the screensaver (~2 min in, a quirk of Meta's power manager) even if another
  # app is in the foreground.
  A shell appops set $cfg["PKG"] SYSTEM_ALERT_WINDOW allow | Out-Null
  # Lets Immortal install apps via the system PackageInstaller (the App Store / self-update
  # path now that the silent-install daemon is gone). The Portal's on-device "install unknown
  # apps" toggle is non-functional, so grant the source op directly here; with the Gen-1
  # installer-overlay fix the confirm dialog is then visible and usable.
  A shell appops set $cfg["PKG"] REQUEST_INSTALL_PACKAGES allow | Out-Null
  # Lets the app switcher (Quick buttons) list recently-used apps via UsageStatsManager -
  # getRecentTasks can't see other apps since Android 5. Harmless when the feature is off.
  A shell appops set $cfg["PKG"] GET_USAGE_STATS allow | Out-Null
  # Device admin (force-lock only): lets Immortal turn the screen off for its idle and
  # overnight sleep features (and the Home Assistant screen control) via lockNow(). Warn
  # rather than swallow a failure - without it, screen-off silently won't work.
  if ((A shell dpm set-active-admin "$($cfg["PKG"])/.AdminReceiver") -match "Success") {
    Ok "Screen-off (device admin) enabled"
  } else {
    Warn "Couldn't enable screen-off device admin - screensaver sleep and the Home Assistant screen control won't work on this device. Re-run setup; if it keeps failing, check Device health in Immortal settings."
  }
  # Lets Immortal read the device's active media sessions (native now-playing) for
  # the screensaver card + header mini-player. Also self-enabled on app launch.
  A shell cmd notification allow_listener "$($cfg["PKG"])/com.immortal.launcher.MediaNotificationListenerService" | Out-Null
  Ok "Permissions granted"
}
function Configure-BootApps {
  # Apps Immortal relaunches after a reboot (for ones with no boot receiver of their
  # own, e.g. the Music Assistant / Sendspin player). Also editable in-app under
  # Settings > Start on boot.
  $dir = "/sdcard/Android/data/$($cfg["PKG"])/files"
  A shell "mkdir -p '$dir'" | Out-Null
  $apps = $cfg["BOOT_APPS"]
  if ($apps) {
    Step "Setting apps to relaunch on boot"
    A shell "printf '%s\n' $apps > '$dir/boot_apps.txt'" | Out-Null
    Ok "Boot-launch apps: $apps"
  } else {
    A shell "rm -f '$dir/boot_apps.txt'" | Out-Null
  }
}
function Disable-Verifier {
  if ($cfg["DISABLE_VERIFIER"] -ne "true") { return }
  Step "Disabling Meta's install verifier (lets the client install other apps on-device)"
  A shell pm disable-user --user 0 $cfg["VERIFIER_PKG"] | Out-Null
  A shell settings put global package_verifier_enable 0 | Out-Null
  Ok "Verifier disabled"
}
function Disable-InstallerOverlay {
  # GEN-1 PORTAL+ ONLY (Android 9). Meta's RRO re-themes framework resources so the
  # stock package-installer dialog renders white-on-white (blank confirm screen,
  # invisible "Install" button bottom-right). Disabling the overlay restores normal
  # styling. Unlike the daemon/Shizuku, overlay state is persisted by the framework
  # and SURVIVES A REBOOT, so a rebooted Gen-1 with the daemon down still has a
  # working stock installer. `cmd overlay` is immediate; no reboot required.
  if ($cfg["DISABLE_INSTALLER_OVERLAY"] -eq "false") { return }
  $sdk = [int]("$(A shell getprop ro.build.version.sdk)".Trim())
  if ($sdk -ge 29) { return }  # newer Portals have a working dialog; no overlay to fix
  Step "Fixing the on-device installer dialog (disabling Meta's white-on-white overlay)"
  $did = $false
  foreach ($ov in ($cfg["INSTALLER_OVERLAY_PKGS"] -split "\s+")) {
    if (-not $ov) { continue }
    if (-not ((A shell "cmd overlay list 2>/dev/null") -match [regex]::Escape($ov))) { continue }
    A shell "cmd overlay disable --user 0 $ov" | Out-Null
    Ok "Disabled $ov"; $did = $true
  }
  if ($did) {
    A shell settings put global immortal_overlay_fix 1 | Out-Null
    Ok "Installer dialog fix applied"
  } else {
    Warn "No installer overlay found to disable (already fixed, or not a Gen-1)"
  }
}
function Disable-Ota {
  # Ask the user (default YES). Blocking OTA stops a future Meta update from undoing this
  # setup, but also forgoes OS/security updates. DISABLE_OTA forces true/false unattended;
  # blank => ask interactively (and default to blocking on a non-interactive run).
  $want = $cfg["DISABLE_OTA"]
  if (-not $want) {
    if ([Environment]::UserInteractive) {
      Write-Host ""
      Write-Host "Block Meta OS updates on this Portal?" -ForegroundColor White
      Write-Host "  A future Meta OTA could undo this setup (re-enable the verifier, replace the"
      Write-Host "  launcher). Blocking prevents that, but also stops OS / security updates."
      $ans = Read-Host "  [Y/n]"
      $want = if ($ans -match '^[Nn]') { "false" } else { "true" }
    } else { $want = "true" }
  }
  if ($want -ne "true") { Warn "Leaving Meta OS updates enabled"; return }
  Step "Disabling Meta OS updates (so a future OTA can't undo this setup)"
  foreach ($p in ($cfg["OTA_PACKAGES"] -split "\s+")) { if ($p) { A shell "pm disable-user --user 0 $p 2>/dev/null" | Out-Null } }
  Ok "OS updates disabled"
}
function Disable-Presence {
  # OFF BY DEFAULT. The system uses presence to choose ambient vs sleep at the
  # screen timeout, and Immortal cooperates with it. Disable only if you want
  # the camera never used at all. Reversible - restore re-enables it.
  if ($cfg["DISABLE_PRESENCE"] -ne "true") { return }
  Step "Disabling Meta's presence detector (camera off; loses empty-room sleep smarts)"
  A shell pm disable-user --user 0 $cfg["PRESENCE_PKG"] | Out-Null
  Ok "Presence detector disabled"
}
function Set-Launcher {
  if ($cfg["SET_LAUNCHER"] -ne "true") { return }
  Step "Setting custom home launcher"
  A shell cmd package set-home-activity $cfg["HOME_ACTIVITY"] | Out-Null
  Ok "Home = $($cfg["HOME_ACTIVITY"])"
}
function Set-Screensaver {
  if ($cfg["SET_SCREENSAVER"] -ne "true") { return }
  Step "Setting custom screensaver (active + dock/idle default)"
  A shell settings put secure screensaver_components $cfg["DREAM_SERVICE"] | Out-Null
  A shell settings put secure screensaver_default_component $cfg["DREAM_SERVICE"] | Out-Null
  A shell settings put secure screensaver_enabled 1 | Out-Null
  A shell settings put secure screensaver_activate_on_dock 1 | Out-Null
  A shell settings put secure screensaver_activate_on_sleep 1 | Out-Null
  Ok "Screensaver = $($cfg["DREAM_SERVICE"])"
}

# ----- Alexa restore (the "hey" free tier) -----------------------------------
# Mirrors provision.sh's restore_alexa: reconstruct our patched+signed falcon from
# the PUBLIC stock APK via our binary diff, install it, apply the privileged grants,
# then install the wake-word app. Optional, opt-in, NON-FATAL (a failure here never
# aborts the rest of the provision - we Warn + return, never Die).
#
# WINDOWS CAVEAT: unlike macOS, Windows ships no `bspatch`. So either install one
# and point BSPATCH_EXE at it, or set FALCON_PATCHED_LOCAL to a prebuilt APK. With
# neither, the falcon step is skipped with guidance (the rest still runs).
function Get-Sha256($path) { (Get-FileHash -Algorithm SHA256 -Path $path).Hash.ToLower() }

function Restore-Alexa {
  $fp = if ($cfg["FALCON_PKG"]) { $cfg["FALCON_PKG"] } else { "com.amazon.alexa.multimodal.falcon" }
  $sim = "$fp/com.amazon.alexa.multimodal.falcon.SIMActivity"
  # SETUP entry: fresh device -> falcon OOBE -> Amazon CBL linking code; linked -> bootstraps the
  # connection and yields to the launcher. (SIMActivity only ever shows the debug client.)
  $setup = "$fp/com.amazon.alexa.multimodal.LaunchActivity"
  $work = Join-Path $ScriptDir "alexa"
  New-Item -ItemType Directory -Force -Path $work | Out-Null
  Step "Restoring Amazon Alexa (reviving the original falcon client)"

  # Gate: Android 10+ Portals (e.g. the Portal Go) HARD-BLOCK background mic capture for
  # sideloaded apps - even a foregroundServiceType=microphone FGS is silenced - so falcon never
  # receives audio during the wake/button handoff (verified on a clean provision). Alexa revival
  # is supported on A9-and-below ONLY for now; the launcher itself still installs fine on A10.
  # Override with ALEXA_FORCE_A10=1 to attempt anyway (for testing other A10 families).
  $sdk = 0; try { $sdk = [int]("$(A shell getprop ro.build.version.sdk)".Trim()) } catch {}
  if ($sdk -ge 29 -and $env:ALEXA_FORCE_A10 -ne "1") {
    Warn "Skipping Alexa - not supported on this Portal yet (Android 10+, SDK $sdk blocks background mic for sideloaded apps)."
    Write-Host "  The launcher is installed and working; only the Alexa restore is skipped." -ForegroundColor DarkGray
    Write-Host "  (A10 support is in research. To attempt it anyway for testing: set ALEXA_FORCE_A10=1 then re-run with -Alexa.)" -ForegroundColor DarkGray
    return
  }

  # 1. Obtain the patched+signed falcon APK - a local build if given, else
  #    reconstruct it byte-identically from the public stock APK + our diff.
  $patched = $null
  if ($cfg["FALCON_PATCHED_LOCAL"] -and (Test-Path $cfg["FALCON_PATCHED_LOCAL"])) {
    $patched = $cfg["FALCON_PATCHED_LOCAL"]; Ok "Using local patched falcon ($(Split-Path -Leaf $patched))"
  } elseif ($cfg["FALCON_PATCHED_URL"]) {
    # PRIMARY path: download the patched falcon directly. No bspatch (Windows ships none), no flaky
    # firmware-dump fetch, no stock+diff checksum drift - just one cross-platform download.
    Step "Downloading Alexa (falcon) - about 115 MB"
    $patchedOut = Join-Path $work "falcon-patched.apk"
    try { Invoke-WebRequest $cfg["FALCON_PATCHED_URL"] -OutFile $patchedOut }
    catch { Warn "falcon download failed - check your connection, then re-run with -Alexa."; return }
    $patched = $patchedOut
    if ($cfg["FALCON_RESULT_SHA256"] -and (Get-Sha256 $patched) -ne $cfg["FALCON_RESULT_SHA256"]) {
      Warn "falcon download looks corrupted (checksum mismatch). Delete the 'alexa' folder next to this script, then re-run with -Alexa."; return
    }
    Ok "Downloaded + verified"
  } else {
    # FALLBACK (only when FALCON_PATCHED_URL is blank): reconstruct from public stock + our diff.
    # Needs bspatch, which Windows does not ship - set BSPATCH_EXE or FALCON_PATCHED_LOCAL instead.
    $bspatch = $null
    if ($cfg["BSPATCH_EXE"] -and (Test-Path $cfg["BSPATCH_EXE"])) { $bspatch = $cfg["BSPATCH_EXE"] }
    elseif (Get-Command bspatch -ErrorAction SilentlyContinue) { $bspatch = (Get-Command bspatch).Source }
    if (-not $bspatch) { Warn "bspatch not found (Windows ships none). Install it + set BSPATCH_EXE in config.env, or set FALCON_PATCHED_LOCAL to a prebuilt APK. Skipping Alexa."; return }
    $stock = if ($cfg["FALCON_STOCK_LOCAL"]) { $cfg["FALCON_STOCK_LOCAL"] } else { Join-Path $work "stock-falcon.apk" }
    if (-not (Test-Path $stock) -or ($cfg["FALCON_STOCK_SHA256"] -and (Get-Sha256 $stock) -ne $cfg["FALCON_STOCK_SHA256"])) {
      if (-not $cfg["FALCON_STOCK_URL"]) { Warn "No FALCON_STOCK_URL configured - skipping Alexa."; return }
      Step "Downloading stock falcon (~120 MB) from the public firmware dump"
      try { Invoke-WebRequest $cfg["FALCON_STOCK_URL"] -OutFile (Join-Path $work "stock-falcon.apk") } catch { Warn "Stock download failed - skipping Alexa."; return }
      $stock = Join-Path $work "stock-falcon.apk"
    }
    if ($cfg["FALCON_STOCK_SHA256"] -and (Get-Sha256 $stock) -ne $cfg["FALCON_STOCK_SHA256"]) { Warn "Stock falcon checksum mismatch - skipping Alexa."; return }
    $diff = if ($cfg["FALCON_BSDIFF_LOCAL"]) { $cfg["FALCON_BSDIFF_LOCAL"] } else { Join-Path $work "falcon.bsdiff" }
    if (-not (Test-Path $diff)) {
      if (-not $cfg["FALCON_BSDIFF_URL"]) { Warn "No falcon patch (FALCON_BSDIFF_URL/LOCAL) - skipping Alexa."; return }
      Step "Downloading the falcon patch"
      try { Invoke-WebRequest $cfg["FALCON_BSDIFF_URL"] -OutFile (Join-Path $work "falcon.bsdiff") } catch { Warn "Patch download failed - skipping Alexa."; return }
      $diff = Join-Path $work "falcon.bsdiff"
    }
    Step "Reconstructing the patched falcon (bspatch)"
    $patchedOut = Join-Path $work "falcon-patched.apk"
    & $bspatch $stock $patchedOut $diff
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $patchedOut)) { Warn "bspatch failed - skipping Alexa."; return }
    $patched = $patchedOut
    if ($cfg["FALCON_RESULT_SHA256"] -and (Get-Sha256 $patched) -ne $cfg["FALCON_RESULT_SHA256"]) { Warn "Reconstructed falcon checksum mismatch (bad stock or diff) - skipping Alexa."; return }
    Ok "Reconstructed + verified byte-identical"
  }

  # 2. Install falcon - `install -r` ONLY. NEVER uninstall: wiping falcon's data
  #    drops its Amazon registration and the next launch mints a NEW ghost device.
  Step "Installing falcon"
  $out = (A install -r $patched 2>&1 | Out-String)
  if ($out -match "Success") { Ok "falcon installed" }
  elseif ($out -match "INSTALL_FAILED_VERSION_DOWNGRADE") { Ok "falcon already current" }
  elseif ($out -match "(?i)duplicate") { Warn "Duplicate-permission conflict with com.amazon.dee.app - needs the coexistence build. Skipping."; return }
  elseif ("$(A shell pm path $fp)".Trim()) { Warn "install -r reported an issue but falcon is present; continuing" }
  else { Warn "falcon install failed - skipping Alexa."; return }

  # 3. Provision falcon (privileged grants; persist across reboot).
  Step "Provisioning falcon"
  A shell pm grant $fp android.permission.READ_PHONE_STATE | Out-Null
  A shell pm grant $fp android.permission.INTERACT_ACROSS_USERS | Out-Null
  A shell pm grant $fp android.permission.RECORD_AUDIO | Out-Null
  A shell settings put secure user_setup_complete 1 | Out-Null
  A shell appops set $fp SYSTEM_ALERT_WINDOW allow | Out-Null
  Ok "falcon provisioned"

  # 3b. Surface the Amazon sign-in now (right after install) so a fresh Portal shows the linking
  #     code immediately and the user can register while the rest of setup runs. Clear logcat so the
  #     ReadyState we watch for below is from this launch onward. (No-op for already-linked Portals.)
  Step "Opening Alexa to link your Amazon account"
  A logcat -c | Out-Null
  A shell dumpsys deviceidle whitelist +$fp | Out-Null   # skip the "run in background?" prompt -> straight to the code
  A shell am start -n $setup | Out-Null
  Warn "If this Portal isn't linked yet, an Amazon sign-in is now on screen - go to amazon.com/code and enter the code shown (you can do this while the rest of setup runs)."

  # 4. millennium = the "hey" wake-word app (drives falcon hands-free).
  $mp = if ($cfg["MILLENNIUM_PKG"]) { $cfg["MILLENNIUM_PKG"] } else { "com.millennium" }
  $mapk = $null
  if ($cfg["MILLENNIUM_APK_LOCAL"] -and (Test-Path $cfg["MILLENNIUM_APK_LOCAL"])) { $mapk = $cfg["MILLENNIUM_APK_LOCAL"] }
  elseif ($cfg["MILLENNIUM_APK_URL"]) {
    Step "Downloading the hey (millennium) app"
    try { Invoke-WebRequest $cfg["MILLENNIUM_APK_URL"] -OutFile (Join-Path $work "millennium.apk"); $mapk = Join-Path $work "millennium.apk" }
    catch { Warn "millennium download failed (Alexa text/voice still works; wake word needs it)" }
  }
  if ($mapk -and (Test-Path $mapk)) {
    Step "Installing hey (millennium)"
    A install -r $mapk | Out-Null; Ok "millennium installed"
  }
  if ("$(A shell pm path $mp)".Trim()) { A shell pm grant $mp android.permission.RECORD_AUDIO | Out-Null }

  # 5. Wait for ReadyState - EVENT-DRIVEN, not on a timer. falcon logs `AccountRegisteredCondition:
  # isMet` the instant the Amazon account is linked, and never before, so it's a precise "sign-in just
  # completed" signal that can't fire mid-sign-in. Already-linked Portals self-connect (we just see
  # `in ReadyState`); a fresh one parks in IgnoreWhileDisconnectedState after `isMet`, so we give it a
  # short grace then force-stop+relaunch to kick it (re-kicking if stuck). We only kick AFTER `isMet`,
  # so an in-progress sign-in is never interrupted. The cap is just a safety net. See provision.sh.
  Step "Waiting for Alexa to connect"
  Write-Host "  Finish any on-screen Amazon sign-in (amazon.com/code) - this connects automatically once you do..." -ForegroundColor DarkGray
  $ready = $false; $regAt = -1; $lastKick = -100
  for ($i = 0; $i -lt 72; $i++) {                     # ~6 min safety cap (72 x 5s)
    $log = A logcat -d
    if ($log | Select-String "in ReadyState" -Quiet) { $ready = $true; break }
    if ($regAt -lt 0 -and ($log | Select-String "AccountRegisteredCondition: isMet" -Quiet)) {
      $regAt = $i; Step "Amazon account linked - connecting Alexa"
    }
    # registered + not yet connected: ~20s grace for self-connect, then kick; re-kick every ~40s if stuck.
    if ($regAt -ge 0 -and ($i - $regAt) -ge 4 -and ($i - $lastKick) -ge 8) {
      A shell am force-stop $fp | Out-Null
      A shell am start -n $setup | Out-Null   # bootstraps the connection + yields to the launcher
      $lastKick = $i
    }
    Start-Sleep -Seconds 5
  }
  if ($ready) {
    if ("$(A shell pm path $mp)".Trim()) { A shell am start -n "$mp/com.millennium.ui.HeyActivity" | Out-Null }
    Ok "Alexa connected (ReadyState) - say 'Hey Alexa, what's the weather?'"
    Write-Host "  Once linked, you can hide falcon's icon from the launcher - it runs headless." -ForegroundColor DarkGray
  } else {
    Warn "Alexa didn't connect within ~6 min. Check Wi-Fi + that the Amazon account is linked, then re-run with -Alexa."
  }
}

function Maybe-Restore-Alexa {
  # A9-and-below only. A10+ hard-blocks background mic for sideloaded apps, so falcon never
  # gets audio - skip the question entirely on newer Portals (it used to appear then no-op
  # inside Restore-Alexa, confusing on the Portal Go). Override: ALEXA_FORCE_A10=1.
  $sdk = 0; try { $sdk = [int]("$(A shell getprop ro.build.version.sdk)".Trim()) } catch {}
  if ($sdk -ge 29 -and $env:ALEXA_FORCE_A10 -ne "1") { return }
  # RESTORE_ALEXA in config.env forces on/off for unattended runs; blank => ask
  # (only when interactive - a non-interactive run defaults to skipping).
  $want = $cfg["RESTORE_ALEXA"]
  if (-not $want) {
    if ([Environment]::UserInteractive) {
      Write-Host ""
      Write-Host "Restore Amazon Alexa on this Portal?" -ForegroundColor White
      Write-Host "  Revives the original Alexa app - hands-free 'Hey Alexa', with text, voice and visual answers."
      $ans = Read-Host "  [y/N]"
      $want = if ($ans -match '^[Yy]') { "true" } else { "false" }
    } else { $want = "false" }
  }
  if ($want -eq "true") { Restore-Alexa }
}

function Restore-Alexa-Undo {
  # Remove only OUR wake-word app. Leave falcon installed on purpose:
  # uninstalling it drops the Amazon registration and mints a new ghost device.
  $mp = if ($cfg["MILLENNIUM_PKG"]) { $cfg["MILLENNIUM_PKG"] } else { "com.millennium" }
  if ("$(A shell pm path $mp)".Trim()) {
    Step "Removing the hey (millennium) wake-word app"
    A uninstall $mp | Out-Null; Ok "millennium removed"
    Warn "Amazon Alexa (falcon) is left installed on purpose - uninstalling it would register a NEW device with Amazon. Remove it by hand only if you understand that."
  }
}

# ----- per-device stock snapshot (model-agnostic restore) --------------------
# Written on the device the first time we provision, so restore works on any
# Portal model and from any computer. config.env STOCK_* are only fallbacks.
$StateFile = "/sdcard/immortal_restore.env"

function Snapshot-Stock {
  if ("$(A shell "[ -f $StateFile ] && echo yes")".Trim() -eq "yes") { return }
  $pkg = $cfg["PKG"]
  # NB: not "$home" - that's PowerShell's read-only $HOME automatic variable.
  $stockHome = ("$(A shell 'cmd package query-activities --components -a android.intent.action.MAIN -c android.intent.category.HOME')" -split "`n" |
    ForEach-Object { $_.Trim() } |
    Where-Object { $_ -match '^[A-Za-z0-9_.]+/' -and $_ -notmatch "^$pkg/" -and $_ -notmatch '^android/' -and $_ -notmatch '^com\.android\.settings/' } |
    Select-Object -First 1)
  if (-not $stockHome) { $stockHome = $cfg["STOCK_HOME"] }
  $dream  = "$(A shell settings get secure screensaver_components)".Trim()
  $ddream = "$(A shell settings get secure screensaver_default_component)".Trim()
  if (-not $dream  -or $dream  -eq "null" -or $dream  -like "$pkg/*") { $dream  = $cfg["STOCK_DREAM"] }
  if (-not $ddream -or $ddream -eq "null" -or $ddream -like "$pkg/*") { $ddream = $cfg["STOCK_DEFAULT_DREAM"] }
  "STOCK_HOME=$stockHome`nSTOCK_DREAM=$dream`nSTOCK_DEFAULT_DREAM=$ddream`n" | A shell "cat > $StateFile" | Out-Null
  Ok "Saved this device's stock launcher/screensaver for restore"
}

function Load-State {
  if ("$(A shell "[ -f $StateFile ] && echo yes")".Trim() -ne "yes") {
    Warn "No saved snapshot on device - using config.env fallbacks for restore"; return
  }
  foreach ($line in ((A shell cat $StateFile) -split "`n")) {
    if ($line.Trim() -match '^(STOCK_HOME|STOCK_DREAM|STOCK_DEFAULT_DREAM)=(.+)$') {
      $cfg[$Matches[1]] = $Matches[2].Trim()
    }
  }
}

function Enable-Fleet {
  # See provision.sh enable_fleet() for the rationale: hand the app a provision.json
  # in its shell-writable external dir, (re)launch it, read agent.json back for the
  # token. The agent is the persistent WiFi channel; we never switch the device to
  # raw adb-over-WiFi here (that restarts adbd, killing the shell helpers).
  if ($cfg["ENABLE_FLEET"] -ne "true") { return }
  Step "Enabling the WiFi fleet agent"
  $pkg = $cfg["PKG"]
  $filesDir = "/sdcard/Android/data/$pkg/files/fleet"
  $name = $cfg["FLEET_NAME"]
  if (-not $name) { $name = Read-Host "  Name this Portal for the fleet dashboard (e.g. Living Room), Enter to skip" }
  A shell "mkdir -p '$filesDir'" | Out-Null
  if ($name) {
    $esc = ($name -replace '\\','\\') -replace '"','\"'
    A shell "echo '{\""enabled\"":true,\""name\"":\""$esc\""}' > '$filesDir/provision.json'" | Out-Null
  } else {
    A shell "echo '{\""enabled\"":true}' > '$filesDir/provision.json'" | Out-Null
  }
  A shell "am force-stop $pkg" | Out-Null
  A shell "am start -n $($cfg["HOME_ACTIVITY"])" | Out-Null
  $json = ""
  for ($i = 0; $i -lt 15; $i++) {
    $json = ("$(A shell "cat '$filesDir/agent.json' 2>/dev/null")" -replace "`r","")
    if ($json -match '"enabled":true') { break }
    Start-Sleep -Seconds 1
  }
  $token = if ($json -match '"token":"([0-9a-f]+)"') { $Matches[1] } else { "" }
  if (-not $token) { Warn "Fleet agent didn't report a token - open Immortal once, then re-run provision.ps1 -Fleet"; return }
  if (-not $name -and $json -match '"name":"([^"]*)"') { $name = $Matches[1] }
  $port = if ($json -match '"port":([0-9]+)') { $Matches[1] } elseif ($cfg["FLEET_AGENT_PORT"]) { $cfg["FLEET_AGENT_PORT"] } else { "8723" }

  $ip = ""
  if ("$(A shell "ip -f inet addr show wlan0 2>/dev/null")" -match 'inet (\d+\.\d+\.\d+\.\d+)') { $ip = $Matches[1] }
  Record-FleetInventory $name $ip $token $port
  $suffix = ""
  if ($name) { $suffix += " as `"$name`"" }
  if ($ip) { $suffix += " at $($ip):$port" }
  Ok "Fleet agent enabled$suffix"
}

function Record-FleetInventory($name, $ip, $token, $port) {
  # One file per device (keyed by serial) under fleet/ - contains the agent TOKEN,
  # so fleet/ is gitignored.
  $serial = if ($env:ANDROID_SERIAL) { $env:ANDROID_SERIAL } else { "$(A get-serialno)".Trim() }
  $model = "$(A shell getprop ro.product.model)".Trim()
  New-Item -ItemType Directory -Force -Path "fleet" | Out-Null
  $en = ($name -replace '\\','\\') -replace '"','\"'
  $em = ($model -replace '\\','\\') -replace '"','\"'
  $json = @"
{
  "serial": "$serial",
  "name": "$en",
  "model": "$em",
  "ip": "$ip",
  "agentPort": $port,
  "token": "$token"
}
"@
  Set-Content -Path "fleet/$serial.json" -Value $json -Encoding UTF8
  Ok "Recorded fleet/$serial.json"
}

function Enable-WifiAdbNow {
  # ON-DEMAND ONLY (not part of provisioning). Raw adb-over-WiFi for power-user
  # shell / scrcpy. Caveats: `adb tcpip` restarts adbd, pausing Shizuku until the
  # next USB run or reboot, and it doesn't survive a reboot. The Fleet AGENT is the
  # persistent channel (incl. files + logcat).
  $ip = ""
  if ("$(A shell "ip -f inet addr show wlan0 2>/dev/null")" -match 'inet (\d+\.\d+\.\d+\.\d+)') { $ip = $Matches[1] }
  if (-not $ip) { Die "This Portal isn't on WiFi." }
  Step "Enabling adb-over-WiFi (temporary; pauses Shizuku)"
  A tcpip 5555 *> $null
  if ($?) {
    Ok "adb-over-WiFi live - connect with: adb connect $($ip):5555"
    Warn "Shizuku is now paused; re-run the kit over USB (or reboot) to restore it."
  } else { Die "adb tcpip 5555 failed." }
}

# ----- modes -----------------------------------------------------------------
if ($Fleet) {
  Wait-Device
  Enable-Fleet
  exit 0
}

if ($WifiAdb) {
  Wait-Device
  Enable-WifiAdbNow
  exit 0
}

if ($Shizuku) {
  Wait-Device
  Start-Shizuku
  exit 0
}

if ($OverlayFix) {
  Wait-Device
  Disable-InstallerOverlay
  exit 0
}

if ($Alexa) {
  Wait-Device
  Restore-Alexa
  exit 0
}

if ($Apps) {
  Wait-Device
  Install-Apps
  exit 0
}

if ($Status) {
  Wait-Device
  Step "Current state"
  # Current HOME launcher (PR #1, @tgnm): the result used to be piped to
  # Out-Null so the line never printed.
  $homePkg = ("$(A shell 'cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME')" -split "`n" |
    Select-String 'packageName=' | Select-Object -First 1) -replace '.*packageName=', ''
  Write-Host "  home:        $("$homePkg".Trim())"
  $pc = "$(A shell settings get global policy_control)".Trim()
  $dm = "$(A shell settings get secure ui_night_mode)".Trim()
  Write-Host "  status bar:  $(if ($pc -like '*immersive*') {'hidden (immersive)'} else {'stock'})"
  Write-Host "  dark mode:   $(if ($dm -eq '2') {'on'} else {'off'})"
  Write-Host "  screensaver: $("$(A shell settings get secure screensaver_components)".Trim())"
  $disabled = "$(A shell pm list packages -d $cfg["VERIFIER_PKG"])".Trim()
  Write-Host "  verifier:    $(if ($disabled) {'disabled'} else {'enabled'})"
  $ovfix = "$(A shell settings get global immortal_overlay_fix)".Trim()
  Write-Host "  installer dialog: $(if ($ovfix -eq '1') {'fixed (overlay disabled)'} else {'stock'})"
  $ota = (A shell pm list packages -d | Select-String "alohaotasetup|otaui")
  Write-Host "  OS updates:  $(if ($ota) {'disabled'} else {'enabled'})"
  $client = "$(A shell pm list packages $cfg["PKG"])".Trim()
  Write-Host "  client:      $(if ($client) {'installed'} else {'not installed'})"
  exit 0
}

if ($Restore) {
  Write-Host "Portal Restore`n"
  Wait-Device
  Load-State
  Step "Restoring system display settings"
  A shell settings delete global policy_control | Out-Null
  A shell settings delete secure ui_night_mode | Out-Null
  A shell settings delete global hidden_api_policy_pre_p_apps | Out-Null
  A shell settings delete global hidden_api_policy_p_apps | Out-Null
  A shell settings delete global hidden_api_policy | Out-Null
  A shell settings put global development_settings_enabled 0 | Out-Null
  Ok "System settings restored"
  Step "Re-enabling Meta's install verifier"
  A shell pm enable $cfg["VERIFIER_PKG"] | Out-Null
  A shell settings put global package_verifier_enable 1 | Out-Null; Ok "Verifier restored"
  Step "Re-enabling Meta's installer overlay"
  foreach ($ov in ($cfg["INSTALLER_OVERLAY_PKGS"] -split "\s+")) {
    if (-not $ov) { continue }
    if ((A shell "cmd overlay list 2>/dev/null") -match [regex]::Escape($ov)) {
      A shell "cmd overlay enable --user 0 $ov" | Out-Null
    }
  }
  A shell settings delete global immortal_overlay_fix | Out-Null; Ok "Installer overlay restored"
  Step "Re-enabling Meta OS updates"
  foreach ($p in ($cfg["OTA_PACKAGES"] -split "\s+")) { if ($p) { A shell "pm enable $p 2>/dev/null" | Out-Null } }
  Ok "OS updates restored"
  Step "Re-enabling Meta's presence detector"
  A shell pm enable $cfg["PRESENCE_PKG"] | Out-Null
  Ok "Presence detector restored"
  Step "Removing Immortal's screen-off device admin"
  # Android refuses to let the shell force-remove a non-test device admin, so this
  # usually fails on a real device - don't claim success when it didn't. The active
  # admin also blocks `adb uninstall` until it's deactivated on-device.
  $admOut = "$(A shell dpm remove-active-admin "$($cfg["PKG"])/.AdminReceiver" 2>&1)"
  if ($admOut -match "success|removed") { Ok "Device admin removed" }
  else {
    Warn "Couldn't remove the device admin from here (Android blocks shell removal of a non-test admin)."
    Write-Host "  To fully remove Immortal: on the Portal, deactivate it under Settings > device admin apps, then run: adb uninstall $($cfg["PKG"])" -ForegroundColor DarkGray
  }
  A shell cmd notification disallow_listener "$($cfg["PKG"])/com.immortal.launcher.MediaNotificationListenerService" | Out-Null
  A shell "rm -f /sdcard/Android/data/$($cfg["PKG"])/files/boot_apps.txt" | Out-Null
  Restore-Alexa-Undo
  Step "Restoring stock launcher"
  A shell cmd package set-home-activity $cfg["STOCK_HOME"] | Out-Null; Ok "Home restored ($($cfg["STOCK_HOME"]))"
  Step "Restoring stock screensaver"
  A shell settings put secure screensaver_components $cfg["STOCK_DREAM"] | Out-Null
  A shell settings put secure screensaver_default_component $cfg["STOCK_DEFAULT_DREAM"] | Out-Null
  Ok "Screensaver restored"
  A shell input keyevent KEYCODE_HOME | Out-Null
  Write-Host "`n[ok] Stock Portal settings restored." -ForegroundColor Green
  exit 0
}

Write-Host "Portal Provisioner" -ForegroundColor White
Write-Host "This will modify your Portal: install an app, replace the home screen and screensaver," -ForegroundColor DarkGray
Write-Host "and disable Meta's app-install verifier. Run with -Restore to undo.`n" -ForegroundColor DarkGray
Wait-Device
Install-Client
Start-Shizuku
Install-Apps
Push-Assets
Grant-Perms
Apply-SystemTweaks
Disable-Verifier
Disable-InstallerOverlay
Disable-Ota
Disable-Presence
Snapshot-Stock
Set-Launcher
Set-Screensaver
Enable-Fleet
Configure-BootApps
Maybe-Restore-Alexa
A shell input keyevent KEYCODE_HOME | Out-Null
Write-Host "`n[ok] Done. Your Portal is provisioned." -ForegroundColor Green
Write-Host "To undo: run provision.ps1 -Restore" -ForegroundColor DarkGray
