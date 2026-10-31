# Tools

`ToolsActivity` — a **Tools** tile on the home grid opens a single screen that gathers the small
utilities Immortal ships. Some open their own full-screen Activity; the lighter ones open as an
in-page overlay hosted on the Tools screen itself.

Everything here is **keyless and on-device** — no accounts, and the couple of tools that reach the
network use free, keyless public endpoints.

## What's on the Tools screen

| Tool | What it does |
| --- | --- |
| **Cameras** | Full-screen live view of a saved **RTSP** camera feed, played through Android's native video stack (no extra libraries). Add and name cameras with their RTSP URL; tap one to view. (`CameraViewerActivity`) |
| **Countdowns** | Days until birthdays and events, shown as chips on the home screen (`🎂 Birthday · 12 days`). Each event has a label, emoji, and date; a year of `0` repeats every year, otherwise it's a one-off. (`CountdownSettingsActivity`) |
| **Lamp** | Turns the panel into a full-screen warm-white light — a nightlight, reading light, or fill light. Brightness and warmth sliders, plus a red night-light mode; the screen is forced bright and kept awake. (`LampActivity`) |
| **Bedtime story** | Public-domain children's tales in large, calm text, read aloud with Android's text-to-speech. No network. (`BedtimeStoryActivity`) |
| **Intercom** | Talk to another Portal on your Wi-Fi — a push-to-talk intercom (or a one-way baby monitor), streamed directly between devices with no server. Needs microphone permission the first time. (`IntercomActivity`) |
| **Timers** | Kitchen timers with a live countdown. |
| **Leave a note** | A sticky note or a quick voice memo, left on the device for the next person. |
| **Converter** | Units and currency (ECB rates via Frankfurter; Bank of England rates via [allratestoday.com](https://allratestoday.com) when Frankfurter is unreachable). |
| **ISS passes** | When the International Space Station next flies over your location. |
| **Aurora outlook** | The northern-lights chance for your location. |
| **Speed test** | Check your internet speed (via Cloudflare). |

## Where things are configured

- **Cameras** and **Countdowns** keep their saved list on the device (add/remove from the tool
  itself). Countdowns you add appear as chips on the home screen as well.
- The remaining tools have no persistent settings — you open them, use them, and leave.

These are separate from the [Settings](launcher.md#settings) screen: the Tools screen is a *launcher*
for utilities, not a settings surface.
