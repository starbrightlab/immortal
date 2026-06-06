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
#>
param([switch]$Restore, [switch]$Status, [switch]$Apps, [switch]$Installd, [switch]$Shizuku)

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
    $line = (A devices | Select-Object -Skip 1 | Where-Object { $_.Trim() } | Select-Object -First 1)
    $state = if ($line) { ($line -split "\s+")[1] } else { "" }
    switch ($state) {
      "device" {
        $model = (A shell getprop ro.product.model).Trim()
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
function Install-Client {
  $apk = Get-ChildItem -Path $cfg["APK_GLOB"] -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not $apk -and $cfg["RELEASE_APK_URL"]) {
    Step "No local APK - downloading the latest Immortal release"
    $dir = Split-Path -Parent $cfg["APK_GLOB"]
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $dest = Join-Path $dir "immortal.apk"
    Invoke-WebRequest -Uri $cfg["RELEASE_APK_URL"] -OutFile $dest
    $apk = Get-Item $dest
    Ok "Downloaded $($apk.Name)"
  }
  if (-not $apk) { Die "No client APK found matching '$($cfg["APK_GLOB"])'. Drop your signed APK in apks\." }
  Step "Installing client app ($($apk.Name))"
  A install -r $apk.FullName | Out-Null
  Ok "Installed $($cfg["PKG"])"
}
function Start-Installd {
  if (-not (Test-Path "installd.sh")) { Warn "installd.sh missing - skipping silent-install daemon"; return }
  Step "Starting the silent-install daemon"
  A push installd.sh /data/local/tmp/installd.sh | Out-Null
  A shell "chmod 755 /data/local/tmp/installd.sh" | Out-Null
  A shell 'me=$$; for d in /proc/[0-9]*; do p=${d#/proc/}; [ "$p" = "$me" ] && continue; c=$(cat "$d/cmdline" 2>/dev/null | tr "\0" " "); case "$c" in *installd.sh*) kill "$p" 2>/dev/null;; esac; done' | Out-Null
  A shell "setsid sh /data/local/tmp/installd.sh /sdcard/Android/data/$($cfg['PKG'])/files/installq >/dev/null 2>&1 &" | Out-Null
  Start-Sleep -Seconds 2
  $hb = (A shell "cat /sdcard/Android/data/$($cfg['PKG'])/files/installq/.heartbeat 2>/dev/null")
  if ($hb -match '[0-9]') { Ok "Silent-install daemon running" } else { Warn "Daemon didn't report a heartbeat (the store will fall back to the system installer)" }
}
function Start-Shizuku {
  # Lets third-party stores (Aurora's Shizuku installer) install silently on the
  # Gen-1 Portal+, whose stock installer is broken. Auto-installs Shizuku only on
  # Gen-1 (API < 29) where it's needed; on newer Portals it's skipped. If Shizuku
  # is already installed on any model, its server is started below. Like our own
  # daemon, the server does NOT survive a reboot; re-run with -Shizuku to restart.
  $SZ = "moe.shizuku.privileged.api"
  $installed = (A shell pm list packages $SZ) -match "package:$SZ"
  $sdk = [int]((A shell getprop ro.build.version.sdk).Trim())
  if (-not $installed) {
    if ($cfg["SHIZUKU_APK_URL"] -and $sdk -lt 29) {
      Step "Installing Shizuku (enables Aurora Store etc. on this Gen-1 Portal)"
      $tmp = Join-Path (Split-Path -Parent $cfg["APK_GLOB"]) "shizuku.apk"
      New-Item -ItemType Directory -Force -Path (Split-Path -Parent $tmp) | Out-Null
      try { Invoke-WebRequest $cfg["SHIZUKU_APK_URL"] -OutFile $tmp; A install -r $tmp | Out-Null; Ok "Shizuku installed" }
      catch { Warn "Shizuku install failed - skipping"; Remove-Item $tmp -ErrorAction SilentlyContinue; return }
      Remove-Item $tmp -ErrorAction SilentlyContinue
    } else { return }  # Not installed and not a Gen-1 (or no URL): nothing to do.
  }
  Step "Starting Shizuku server (for third-party silent installs)"
  # Resolve the version/ABI-specific starter binary at runtime (the install-dir
  # hash and ABI vary per device, so don't hard-code them).
  $apkpath = ((A shell pm path $SZ) -replace 'package:', '').Trim() -split "`n" | Select-Object -First 1
  $apkdir  = (A shell "dirname '$apkpath'").Trim()
  $starter = (A shell "ls $apkdir/lib/*/libshizuku.so 2>/dev/null").Trim() -split "`n" | Select-Object -First 1
  if (-not $starter) { Warn "Couldn't find Shizuku's starter - open the Shizuku app and tap Start once"; return }
  if ((A shell "$starter") -match 'exit with 0') { Ok "Shizuku server running" }
  else { Warn "Shizuku didn't confirm startup - open the Shizuku app to check its status" }
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
function Grant-Perms {
  Step "Granting permissions"
  foreach ($p in ($cfg["PERMISSIONS"] -split "\s+")) { if ($p) { A shell pm grant $cfg["PKG"] $p | Out-Null } }
  # Self-healing: lets Immortal reaffirm its screensaver settings if reset.
  A shell pm grant $cfg["PKG"] android.permission.WRITE_SECURE_SETTINGS | Out-Null
  # Lets the "Install an APK" browser see downloaded APKs without a prompt.
  A shell pm grant $cfg["PKG"] android.permission.READ_EXTERNAL_STORAGE | Out-Null
  # Lets Immortal bring the photo frame back instantly when the system force-wakes
  # the screensaver (~2 min in, a quirk of Meta's power manager) even if another
  # app is in the foreground.
  A shell appops set $cfg["PKG"] SYSTEM_ALERT_WINDOW allow | Out-Null
  Ok "Permissions granted"
}
function Disable-Verifier {
  if ($cfg["DISABLE_VERIFIER"] -ne "true") { return }
  Step "Disabling Meta's install verifier (lets the client install other apps on-device)"
  A shell pm disable-user --user 0 $cfg["VERIFIER_PKG"] | Out-Null
  A shell settings put global package_verifier_enable 0 | Out-Null
  Ok "Verifier disabled"
}
function Disable-Ota {
  if ($cfg["DISABLE_OTA"] -ne "true") { return }
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

# ----- per-device stock snapshot (model-agnostic restore) --------------------
# Written on the device the first time we provision, so restore works on any
# Portal model and from any computer. config.env STOCK_* are only fallbacks.
$StateFile = "/sdcard/immortal_restore.env"

function Snapshot-Stock {
  if ("$(A shell "[ -f $StateFile ] && echo yes")".Trim() -eq "yes") { return }
  $pkg = $cfg["PKG"]
  $stockHome = ((A shell 'cmd package query-activities --components -a android.intent.action.MAIN -c android.intent.category.HOME') -split "`n" |
    ForEach-Object { $_.Trim() } |
    Where-Object { $_ -match '^[A-Za-z0-9_.]+/' -and $_ -notmatch "^$pkg/" -and $_ -notmatch '^android/' -and $_ -notmatch '^com\.android\.settings/' } |
    Select-Object -First 1)
  if (-not $stockHome) { $stockHome = $cfg["STOCK_HOME"] }
  $dream  = (A shell settings get secure screensaver_components).Trim()
  $ddream = (A shell settings get secure screensaver_default_component).Trim()
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

# ----- modes -----------------------------------------------------------------
if ($Installd) {
  Wait-Device
  Start-Installd
  exit 0
}

if ($Shizuku) {
  Wait-Device
  Start-Shizuku
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
  $homePkg = ((A shell 'cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME') -split "`n" |
    Select-String 'packageName=' | Select-Object -First 1) -replace '.*packageName=', ''
  Write-Host "  home:        $("$homePkg".Trim())"
  Write-Host "  screensaver: $((A shell settings get secure screensaver_components).Trim())"
  $disabled = (A shell pm list packages -d $cfg["VERIFIER_PKG"]).Trim()
  Write-Host "  verifier:    $(if ($disabled) {'disabled'} else {'enabled'})"
  $ota = (A shell pm list packages -d | Select-String "alohaotasetup")
  Write-Host "  OS updates:  $(if ($ota) {'disabled'} else {'enabled'})"
  $client = (A shell pm list packages $cfg["PKG"]).Trim()
  Write-Host "  client:      $(if ($client) {'installed'} else {'not installed'})"
  exit 0
}

if ($Restore) {
  Write-Host "Portal Restore`n"
  Wait-Device
  Load-State
  Step "Re-enabling Meta's install verifier"
  A shell pm enable $cfg["VERIFIER_PKG"] | Out-Null
  A shell settings put global package_verifier_enable 1 | Out-Null; Ok "Verifier restored"
  Step "Re-enabling Meta OS updates"
  foreach ($p in ($cfg["OTA_PACKAGES"] -split "\s+")) { if ($p) { A shell "pm enable $p 2>/dev/null" | Out-Null } }
  Ok "OS updates restored"
  Step "Re-enabling Meta's presence detector"
  A shell pm enable $cfg["PRESENCE_PKG"] | Out-Null
  Ok "Presence detector restored"
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
Start-Installd
Start-Shizuku
Install-Apps
Push-Assets
Grant-Perms
Disable-Verifier
Disable-Ota
Disable-Presence
Snapshot-Stock
Set-Launcher
Set-Screensaver
A shell input keyevent KEYCODE_HOME | Out-Null
Write-Host "`n[ok] Done. Your Portal is provisioned." -ForegroundColor Green
Write-Host "To undo: run provision.ps1 -Restore" -ForegroundColor DarkGray
