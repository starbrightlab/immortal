# First-gen Portals (Android 9)

Immortal runs on the original **Portal+ (Gen-1)** and the **Portal TV** too — launcher,
screensaver, and app store all work. There's one quirk worth knowing up front, because it's
a quirk of that hardware's older software, not a fault in Immortal.

## The broken installer dialog — and the fix

**The first-gen Portal's built-in Android installer is broken.** It opens a confirm dialog
rendered white-on-white, with the "Install" button invisible in the bottom-right corner, so
apps can't be installed the normal way.

The cause is one of Meta's display overlays (`com.facebook.aloha.rro.niu.android`) re-theming
the dialog — a great community discovery by
[u/keremimo](https://www.reddit.com/user/keremimo/) on r/FacebookPortal.

The [provisioning kit](provisioning.md) **disables that overlay** (`cmd overlay disable`),
which brings the normal, readable dialog back. Crucially, the framework remembers this across
reboots (it's stored in `/data/system/overlays.xml`), so once the kit has run, the App Store,
"Install with Immortal," and sideloading all install through the standard Android dialog — no
cable and no re-setup needed.

!!! note "Why there's no on-device 'install unknown apps' toggle to flip"
    The Portal's on-device "install unknown apps" setting is non-functional, so the kit grants
    Immortal the install-source permission directly over ADB instead.

### Opting out of the overlay fix

Set `DISABLE_INSTALLER_OVERLAY=false` in `config.env` to leave the stock dialog untouched —
but on a Gen-1 Portal the installer dialog then stays unreadable, so installs won't work until
you re-enable the fix. (On newer Portals the overlay isn't present, so this setting has no
effect there.) Everything else works across reboots regardless: your home screen, screensaver,
and every app you've already installed.

## Portal TV

The **Portal TV** is the same generation (Android 9), so the same install mechanics apply.
It has no touchscreen, but Immortal is fully driveable with the TV remote — see
[Portal TV](features/portal-tv.md).

## Play-Store apps via Aurora

[Aurora Store](https://auroraoss.com) installs Play-Store apps (Spotify, etc.) without a
Google account. On the Gen-1 Portal+, Aurora's default "Session" and "Native" installer modes
hit the broken stock dialog. Two paths work:

### Recommended: Aurora's Shizuku installer

The provisioning kit installs **Shizuku** (a privileged broker) and starts its server, so
Aurora can install silently through it — including split APKs, with no dialog (verified
end-to-end installing Spotify).

1. Install **Aurora Store** from the Immortal App Store.
2. In **Aurora → Settings → Installation → Installation method**, choose **Shizuku installer**.
   The first install prompts "Allow Aurora Store to access Shizuku?" — tap **Allow all the time**.

Shizuku's server doesn't survive a reboot; the kit restarts it on its next run, or run
`./provision.sh --shizuku` (`provision.ps1 -Shizuku`).

### Simpler alternative: skip Shizuku

Shizuku's server can fail to stay up on some Gen-1 firmware (Android 9 may kill it right after
launch — the kit detects this and tells you instead of falsely reporting success). With the
installer-overlay fix applied, Aurora's **Session** installer routes through the now-readable
system dialog. Set **Aurora → Settings → Installation → Installation method → Session**, and
grant Aurora permission to install apps once:

```bash
adb shell appops set com.aurora.store REQUEST_INSTALL_PACKAGES allow
```

After that, Session installs work with the standard dialog — no Shizuku needed. For apps that
ship as split APKs, the Shizuku path above is the more reliable one.
