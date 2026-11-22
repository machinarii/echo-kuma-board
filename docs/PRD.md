# PRD: Kuma Board — Uptime Kuma wall display for Echo Show 5 (2nd gen) on LineageOS

| | |
|---|---|
| **Status** | Draft v0.3 (v0.3 retargets the device to the Echo Show 5 2nd gen; v0.2 added the Catppuccin Macchiato design) |
| **Date** | 2026-11-07 |
| **Working name** | Kuma Board |
| **Target device** | Amazon Echo Show 5, 2nd gen (2021), codename `cronos`, about 5.5-inch 960×480 display, running unofficial LineageOS 18.1 |
| **Backend** | Self-hosted Uptime Kuma 2.x, public status page (e.g. `https://kuma.example.com/status/echo`) |

---

## 1. Summary

An always-on Android app that turns a re-flashed Echo Show 5 (2nd gen) into a dedicated, glanceable status board. Every monitor from one Uptime Kuma status page appears on a single screen with its 24-hour uptime, name and heartbeat bar. No scrolling, no menus, no login.

It replaces the current workaround (a custom CSS file plus a launcher page running in the Silk browser), which breaks when Kuma's markup changes and cannot own the screen, boot or power behaviour.

## 2. Problem

- A typical homelab monitors 10–20 services (internet, VPN, DNS, router, NAS, hypervisor, self-hosted apps, cloud VMs) and wants to see all of them at a glance.
- Kuma's own status page is a scrolling list. On a 960×480 screen it needs scrolling and small text.
- The CSS workaround depends on Kuma's internal class names and on the Silk browser, which has no reliable full screen, kiosk or keep-awake behaviour.
- The stock Echo OS cannot run arbitrary apps, so a native app needs the LineageOS port.
- **The screen is small and wide (2:1).** Fitting 14 monitors legibly on it is the central design problem of this PRD.

## 3. Key findings that shape this PRD

These came out of research on 2026-11-07. Verify each before committing (see §14).

1. **The device runs Android 11, not a current Android.** For the Echo Show 5 2nd gen (`cronos`) the available LineageOS is an unofficial 18.1 port (Android 11, API 30), not an official LineageOS build. "Latest build" therefore means 18.1.
2. **The linked app cannot install on that ROM.** `kirkanos/uptime-kuma-android` requires Android 12 (API 31) or newer. The device is on API 30.
3. **The linked app is the wrong shape anyway.** It is a full management client: 34 monitor-type editor, notification setup, 2FA, push via ntfy or Firebase, Material You theming, widgets. It targets Kuma 2.x, uses Socket.IO with a login, and is GPL-3.0-or-later. A wall display needs none of that.
4. **This device is the better-supported one.** The Echo Show 5 gen 1 and gen 2 and the Echo Show 8 gen 1 share a chip with a known unlock exploit, and the `cronos` has an XDA unlock thread and an XDA ROM thread. A separate community project reports its front camera working on LineageOS 18.1. Community notes say older `amonet` versions left these devices on 1 GB of RAM and `amonet` 2.0.1 or newer exposes the full 2 GB; confirm this for `cronos`. (Earlier drafts of this PRD targeted the Echo Show 8 2nd gen, `athena`. That device uses a different chip, has a newer and thinner unlock and ROM, and was dropped.)
5. **LineageOS makes Tailscale possible.** The earlier blocker (an Echo cannot join a tailnet, so a `*.ts.net` address is unreachable) goes away if the Tailscale Android app can run on this ROM. To be verified.

The two XDA threads for the `cronos` (unlock and ROM) could not be opened during research, so points 1 and 4 come from search excerpts and secondary guides.

## 4. Goals

| # | Goal | Measure |
|---|---|---|
| G1 | All monitors visible on one screen, no scrolling | 100% of monitors up to 20 shown at once on 960×480; beyond 20, automatic paging |
| G2 | Readable at desk distance, status readable from across a room | At the native 960×480 resolution: name ≥ 18 px, uptime numeral ≥ 24 px semibold, elapsed labels ≥ 14 px. Status must be recognisable from colour and marker alone at about 2 m. (Convert px to sp using the density measured on the device.) |
| G3 | Always-on kiosk | Boots straight into the app; screen stays on; recovers from crash and network loss with no touch |
| G4 | No credentials on the device | Works from the public status page; no Kuma login or API key stored |
| G5 | Survives Kuma updates | Depends only on the status page JSON, not on page markup |
| G6 | Runs comfortably on a low-memory device | App memory under 120 MB; cold start under 3 s |
| G7 | Looks like a polished product, not a debug screen | Matches the visual design in §8.6 (Catppuccin Macchiato palette, soft rounded card language) |

## 5. Non-goals

- Managing monitors, notifications, maintenance windows or status pages (the linked app covers that).
- Push notifications or background alerting through Firebase or ntfy (the ROM has no Google services by default).
- Phone and larger-tablet layouts, widgets, a Play Store release.
- Supporting Kuma 1.x or Kuma versions that lack the public status page endpoints.
- Anything about unlocking or flashing the Echo; this PRD only documents it as a prerequisite.
- Other Echo models. A later phase may add profiles (for example Echo Show 8), but v1 targets the Show 5 2nd gen only.

## 6. Users and use case

**Primary user:** a homelab operator running Kuma at home and in the cloud. The device sits on a desk or shelf and is glanced at throughout the day.

**Key jobs:**
- "Is everything up?" — answered in under 2 seconds by colour and count.
- "Which one is down, and for how long?" — found without touching the screen.
- "Is the board itself still alive?" — answered by a visible freshness indicator.

## 7. Approach options considered

| Option | Summary | Verdict |
|---|---|---|
| **A. New lightweight native app** (recommended) | Kotlin, minSdk 30. Reads the public status page JSON. Kiosk behaviour built in. | Best fit. Small, no login, no dependency on page markup, full control of power and boot. |
| B. Fork `kirkanos/uptime-kuma-android` | Lower minSdk to 30, strip features, add a wall mode. | Poor fit. Large codebase built for API 31+, Material You and Socket.IO. GPL-3.0 would apply to the result. Stripping it costs more than writing A. Reuse its documented API notes (`docs/kuma-api/`) as reference only. |
| C. WebView shell around the existing `echo-grid.html` | Thin app that loads the page full screen. | Fastest to ship, good v0 fallback. Depends on the ROM's WebView version and gives weaker native control. Kept as a spike and a fallback. For the Show 5 it needs `?cols=5` and smaller tile text, since the page was tuned for 1280×800. |
| D. Keep the Silk browser setup | Current CSS plus launcher approach. | Works today without flashing, but the CSS Echo mode was tuned for 1280×800 and must be retuned for 960×480. Keep as the no-risk fallback if the ROM proves unstable. |

**Decision:** build A, using C as a two-day spike to prove the data and the kiosk behaviour before investing in native rendering.

## 8. Functional requirements

### 8.1 Data

| ID | Requirement |
|---|---|
| F1 | Read the monitor list from `GET /api/status-page/<slug>` and heartbeats plus uptime from `GET /api/status-page/heartbeat/<slug>`. |
| F2 | Refresh every 30 s by default (configurable 10–300 s). Use short timeouts so a slow network never blocks the UI. |
| F3 | Flatten all groups into one list, preserving Kuma order. |
| F4 | On fetch failure keep showing the last good data, mark it stale after 2 missed refreshes, and show a clear "offline / last updated hh:mm" indicator. |
| F5 | Treat unknown or missing fields defensively: a missing monitor, empty heartbeat list or missing uptime value renders as a neutral "no data" tile, never a crash. |
| F6 | Heartbeat timestamps are UTC (`YYYY-MM-DD HH:mm:ss.SSS`); convert for display. Status codes: 1 up, 0 down, 2 pending, 3 maintenance. |

> The JSON shape above comes from knowledge of Kuma's own status page, not from a live check. Confirm it against the real instance in the first spike.

### 8.2 Layout (the core of the product)

Geometry used below: 960×480 screen, 16 px outer margin (usable 928×448), 12 px gaps.

| ID | Requirement |
|---|---|
| F7 | Tiles fill the whole screen in a grid. Choose columns and rows automatically from the monitor count: among layouts that meet the text-fit minimums in G2 and keep tile aspect ratio between 1.2 and 3.0, pick the one with the largest tile area. |
| F8 | Expected results at 960×480 (verify with a unit test of the layout function): 1–4 monitors → 2×2, 5–6 → 3×2, 7–9 → 3×3, 10–12 → 4×3, 13–15 → 5×3, 16–20 → 5×4. The current 14 monitors give a **5×3 grid with 1 empty cell**, with tiles of about 176×141 px. |
| F9 | Above 20 monitors, page automatically every 15 s (20 per page) with page dots. Never require touch to see all monitors. |
| F10 | Each tile shows: 24 h uptime as a large numeral, a status marker, monitor name (max 2 lines, ellipsised), and a heartbeat bar fitted to the tile width. Elapsed labels (oldest beat on the left, "now" on the right) and the text status chip appear only when the tile is at least 140 px tall. Smaller tiles use the compact variant in §8.6. |
| F11 | Status colours come from the Macchiato tokens in §8.6: Green up, Red down, Yellow pending, Blue maintenance, Overlay0 no data. **Colour is never the only signal**: down tiles also get a stronger border and a ✕ marker, pending a ! marker, maintenance a wrench marker (works for colour-blind viewers and from a distance). |
| F12 | Sort modes: Kuma order (default) or "problems first" (down, then pending, then maintenance, then up). |
| F13 | The header strip is **off** and not offered on this screen by default, because every row of height matters. A single-line "N down" banner (about 28 px) appears automatically while anything is down, and the grid shrinks to make room. |
| F14 | Names and numbers scale with the tile size but never fall below the minimums in G2. If a name cannot fit at the minimum size in two lines (about 34 characters at the 5×3 tile width), ellipsise it. |

### 8.3 Kiosk behaviour

| ID | Requirement |
|---|---|
| F15 | Full-screen immersive mode (hide status and navigation bars). |
| F16 | Keep the screen on while the app is in the foreground. |
| F17 | Offer to act as the device's Home launcher and start on boot, so the board returns after any power loss. |
| F18 | Recover automatically from process death, network loss and Wi-Fi reconnects, with exponential backoff capped at 60 s. |
| F19 | Settings are hidden behind a long press on a corner, so a casual touch cannot change anything. |
| F20 | Scheduled dimming: configurable night window (for example 23:00–07:00) lowers brightness to a set level. While anything is down, brightness returns to normal regardless of schedule. |
| F21 | Screen-burn mitigation: slow pixel shift or subtle layout drift every few minutes. The panel is LCD, so this is low priority but cheap. |

### 8.4 Configuration

| ID | Requirement |
|---|---|
| F22 | Settings: server base URL, status page slug, refresh interval, sort mode, brightness schedule, column override (auto by default). |
| F23 | Provide on-device entry through the on-screen keyboard **and** non-touch provisioning via an adb intent or a JSON file on shared storage, because typing a long URL on a 5-inch screen is painful. |
| F24 | No account, no secrets: the status page is public. If the user later adds an optional Kuma API key for private pages, it must be stored in the Android Keystore. Not in v1. |

### 8.5 Alerts (v1.1, optional)

| ID | Requirement |
|---|---|
| F25 | Optional on-device audible cue when a monitor goes down or recovers (short tone or text-to-speech). Off by default. |

### 8.6 Visual design

**Direction:** the soft, modern dashboard look of the supplied reference (rounded cards, hairline borders, large light numerals, tinted chips, thin vertical bars, a ring gauge), rendered as a dark wall display in the **Catppuccin Macchiato** palette. All colours come from named tokens so another flavour (for example Mocha) can be added later by swapping one table. Because the Show 5 screen is small, some of the reference's decoration is cut back; those cuts are listed below.

#### Palette and roles

| Token | Hex | Role |
|---|---|---|
| Crust | `#181926` | Outer screen margin, night-mode background |
| Mantle | `#1e2030` | Canvas behind the tiles |
| Base | `#24273a` | Tile surface |
| Surface0 | `#363a4f` | Hairline borders, dividers, empty heartbeat slots |
| Surface1 | `#494d64` | Pressed or focus outline, bar track |
| Text | `#cad3f5` | Monitor names, primary numerals |
| Subtext0 | `#a5adcb` | Captions, elapsed labels (use this, not Overlay, for readable muted text) |
| Overlay0 | `#6e738d` | No-data state, disabled |
| Green | `#a6da95` | Up |
| Red | `#ed8796` | Down |
| Yellow | `#eed49f` | Pending |
| Blue | `#8aadf4` | Maintenance, links, focus accent |
| Mauve | `#c6a0f6` | Optional accent (paging dots) |
| Peach | `#f5a97f` | Optional accent for degraded or slow states (future) |

#### How the reference's design language maps to the board

| Reference element | Kuma Board treatment on the 960×480 screen |
|---|---|
| Rounded cards with soft borders | Tiles with a 14 px radius (reduced from the large-screen 20), 1 px Surface0 border, 12 px gaps, 16 px screen margin. No hard edges anywhere. |
| White cards on a light grey canvas | Base tiles on a Mantle canvas inside a Crust margin. Depth comes from lightness steps, not shadows, which look muddy on dark LCDs. |
| Large light numerals with a small muted caption | The 24 h uptime is the hero numeral (semibold, tabular figures). The monitor name sits above or beside it in Text. The muted caption line is **dropped** on this screen to save height. |
| Thin vertical accent bar on list rows | A 4 px rounded accent bar on the tile's left edge in the status colour. |
| Tinted chips ("Pending", "Claimed") with soft fill and border | Status chip: status colour text on a 16% fill of the same colour with a 1 px border at about 40%. Chips read "Up", "Down", "Pending", "Maint." Shown only on tiles at least 140 px tall; smaller tiles show the marker icon alone. |
| Thin vertical bars in the performance chart | Heartbeat bars are thin, rounded (2 px) vertical bars with a 1 px gap. Failing beats use Red, pending use Yellow, empty slots use Surface0. |
| Orange highlight over a selected range | The newest 5 beats get full brightness and the older ones 70% opacity, so the present stands out. |
| Ring gauge with a centred "Health Score" chip | **Dropped** on this screen: a ring would cost a full row of tiles. Overall health is shown only by the automatic "N down" banner. |
| Soft icon buttons in rounded squares | Status markers (✕, !, wrench) sit in 22 px rounded-square chips tinted with the status colour. |
| Large, light page title ("Overview") | **Dropped** on this screen (no header). |
| Subtle gradient under the area chart | Off. It costs rendering time on a low-power device and adds nothing at this tile size. |

#### Tile specification

Reference tile: 176×141 px (5×3 grid, 13–15 monitors). Sizes below are native pixels at 960×480; implement as sp/dp scaled from the measured density.

| Part | Standard tile (height ≥ 140 px) | Compact tile (height < 140 px, for example 5×4 at 176×103 px) |
|---|---|---|
| Surface | Base fill, Surface0 1 px border, 14 px radius | Same |
| Padding | 12 px | 10 px |
| Accent bar | 4 px wide, 10 px inset top and bottom, status colour | Same |
| Name | 18 px, medium, Text, 2 lines max, ellipsised | 18 px, 2 lines max, placed to the right of the numeral |
| Uptime numeral | 24–28 px, semibold, tabular figures, Text (status colour only when below 95%) | 24 px, left of the name |
| Status marker / chip | Chip with text, beside the numeral | Marker icon only |
| Heartbeat bar | 14 px tall, fitted to tile width | 12 px tall |
| Elapsed labels | 14 px, Subtext0, under the bar | Hidden |
| **Down state** | Tile fill gets a 12% Red tint, border becomes Red at about 55%, accent bar Red, ✕ marker visible. Name stays Text for contrast. | Same |
| **Pending state** | Same recipe in Yellow with a ! marker. | Same |
| **Maintenance state** | Same recipe in Blue with a wrench marker. | Same |
| **No data** | Overlay0 accent, "--" numeral, no tint. | Same |
| **Stale data** | Whole grid dims to 70% and a small "Updated hh:mm" chip appears in a corner. | Same |

Height budget check for the standard tile: padding 12 + name 44 + gap 4 + numeral row 30 + gap 6 + bar 14 + labels 14 + padding 12 is about 136 px, which fits in 141 px with little spare. Treat this as the tightest layout and test it with the real monitor names first.

#### Typography

A bundled geometric sans with tabular figures (candidates: Plus Jakarta Sans or Manrope; confirm the licence allows bundling, and that the font's numerals are tabular). Weights: 500 for names, 600 for numerals. The font is bundled in the APK because the device may have no internet at first boot and the ROM's system fonts are limited. On a 5-inch screen, check that the chosen weight stays crisp at 18 px.

#### Display rules

| Rule | Detail |
|---|---|
| Contrast | Body text at least 4.5:1 against its tile surface; markers, chips and bars at least 3:1. Verify every token pair with a contrast checker before release. |
| No pure white or black | Text is `#cad3f5`, the darkest surface is Crust. This reduces glare on a screen that is always on. |
| Night window | During the dimming schedule, switch the canvas to Crust, lower brightness, and reduce accent saturation by about 30%. Return to full when anything is down. |
| Motion | No decorative animation. The only motion is a short fade when a tile changes state and a slow shift for burn-in mitigation. |
| Icons | Simple outlined icons at 2 px stroke to match the reference's line style. |

## 9. Non-functional requirements

| Area | Requirement |
|---|---|
| **Compatibility** | minSdk 30 (Android 11), targetSdk set per sideload needs. No dependency on Google Play services, Material You, or APIs above 30 without a fallback. |
| **Performance** | Cold start under 3 s; refresh and redraw under 200 ms for 20 tiles; steady-state memory under 120 MB (the device may have as little as 1 GB of RAM). |
| **Battery/thermal** | Mains powered, but avoid constant animation; redraw only when data changes. |
| **Reliability** | 7 days continuous run without a restart or visible stall in soak testing. |
| **Security** | HTTPS only by default (cleartext allowed only for explicit private-network URLs). No analytics, no update checks, no third-party network calls. |
| **Privacy** | The only network traffic is to the user's own Kuma address. |
| **Accessibility** | High contrast, markers in addition to colour, minimum text sizes in G2. |
| **Licence** | New code under a permissive licence the author chooses. Do not copy code from the GPL-3.0 linked repo unless the project is GPL too. |

## 10. Technical approach

- **Stack:** Kotlin, a single-activity app. Jetpack Compose Canvas or a custom `View` for tiles and bars (keep memory low; on 1 GB devices prefer the custom `View`). OkHttp plus kotlinx.serialization for the two endpoints. No Socket.IO in v1.
- **Rendering:** compute the grid from the window size; draw heartbeat bars on a canvas rather than one view per beat.
- **Layout function:** a pure function from (monitor count, screen size, minimums) to (columns, rows, tile size), unit tested against the table in F8.
- **Refresh:** a coroutine loop, with a lifecycle-aware scope so it stops when the screen is off and resumes on wake.
- **Kiosk:** `FLAG_KEEP_SCREEN_ON`, immersive sticky mode, `HOME` and `LAUNCHER` intent filters, a boot receiver, and a watchdog that restarts the activity if frames stop updating.
- **Reference material:** the linked repo's `docs/kuma-api/` is a useful description of Kuma's API surface for later phases.
- **Distribution:** a signed APK installed with `adb install`. No store.

## 11. Dependencies and risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **14 monitors are too small to read on a 5.5-inch screen** | Medium | High | Standard tile height budget in §8.6; test with real names in the spike; fall back to compact tiles, shorter names or paging; consider a larger device later. |
| Unofficial ROM is buggy or abandoned | Medium | High | Keep the Silk-browser setup as the fallback; pin a known-good ROM build; keep the stock restore path documented. |
| Flashing bricks the device | Medium | High | Done outside this project; follow the unlock thread exactly; confirm TWRP boots and a recovery path exists before further steps. |
| Bootloader/firmware version constraints block unlock | Low–Medium | High | Check the device's current firmware against the unlock thread before flashing. |
| Limited RAM (1–2 GB) makes a heavy UI stutter | Medium | Medium | Memory budget in §9; native canvas drawing; soak test. |
| Status page JSON shape differs from what is assumed | Medium | Medium | First spike validates against the live instance; defensive parsing (F5). |
| Kuma changes the endpoints in a future major version | Low–Medium | Medium | Isolate the data layer; contract test in CI against a Kuma container. |
| Echo cannot reach the Kuma address | Medium | High | Install Tailscale on the device (verify it runs on this ROM), or use a LAN address. |
| WebView on the ROM is outdated (only matters for option C) | Medium | Low | Native app avoids it. |

## 12. Milestones

| Phase | Scope | Exit criteria |
|---|---|---|
| **0. Feasibility spike (2–3 days)** | Confirm the ROM is installable and stable on the device; measure RAM and display density; confirm Tailscale (or LAN) reaches Kuma; confirm the two JSON endpoints and their fields; run `echo-grid.html?cols=5` in a WebView shell and judge legibility. | Written go/no-go. Real JSON samples saved. Density and RAM recorded. |
| **1. MVP (1–2 weeks)** | Native app: data layer, auto grid, tiles, bars, status markers, kiosk mode, stale indicator, adb provisioning. | 14 monitors on one screen at 960×480; runs 24 h unattended. |
| **2. Hardening (1 week)** | Settings UI, problems-first sort, dimming schedule, paging for >20, soak test, crash recovery. | 7-day soak passes; all F-requirements verified. |
| **3. Optional** | Audible cue, optional authenticated mode, Socket.IO live updates, themes, profiles for larger Echo models. | Per-feature decision. |

## 13. Acceptance criteria (MVP)

1. With the 14 monitors in the current screenshot, all 14 tiles are visible with no scrolling, in a 5×3 grid with 1 empty cell, at 960×480.
2. Names, numerals and labels meet the minimum sizes in G2, and a long name such as "Apps - Orchard CRM (vm2)" is readable (two lines, at most a short ellipsis).
3. Each tile's uptime numeral, name and bar match the Kuma status page for the same instant.
4. A monitor going down turns its tile red with a ✕ marker within one refresh interval, and the "N down" banner appears without hiding any tile.
5. Unplugging the router for 5 minutes shows the stale indicator, then fully recovers with no touch once the network returns.
6. Power-cycling the device returns to the board with no interaction.
7. A Kuma update that renames CSS classes has no effect on the app.
8. Memory stays under 120 MB after 24 hours.
9. Every colour in the app comes from the token table in §8.6, and the Down, Pending and Maintenance tiles match the recipes in the tile specification.
10. All text and marker colours pass the contrast targets in §8.6, checked with a contrast tool.

## 14. Open questions

1. **ROM readiness:** is the `cronos` LineageOS 18.1 build stable enough for always-on use (Wi-Fi reliability, touch, display, sleep behaviour)? Which `amonet` version and ROM build are required?
2. **Device facts:** actual RAM (1 GB or 2 GB), display density (to convert px to sp/dp) and WebView version on that ROM are unconfirmed.
3. **Legibility:** are 18 px names and 24 px numerals comfortable at the distance where the device will sit? If not, which fallback: shorter names, paging, or a larger screen?
4. **Tailscale:** does the Tailscale Android app run on this ROM without Google services (for example via an F-Droid build), or is a LAN address the better choice?
5. **Endpoint shape:** do `uptimeList` keys and `heartbeatList` entries match §8.1 on the live Kuma 2.x instance?
6. **Scope of future alerts:** is an audible cue wanted at all, given the device is a speaker?
7. **Naming/licence:** final product name and licence.
8. **Font:** which bundled font (and does its licence allow bundling in an APK)?
9. **Trend arrows:** the reference shows ▲/▼ percentage changes. The status page endpoints give only a 24 h uptime value, so no real trend is available. The PRD deliberately omits fake deltas; decide whether to add a computed one (for example, change since the previous refresh), though the small screen leaves little room.

## 15. Sources

- Linked repo, README summary: https://github.com/kirkanos/uptime-kuma-android (Android 12+, Kuma 2.x, Kotlin/Compose, GPL-3.0-or-later, Socket.IO)
- XDA, unofficial LineageOS 18.1 for the Echo Show 5 (2021) `cronos` (supplied by the user; the page could not be opened during research): https://xdaforums.com/t/rom-unofficial-11-cronos-lineageos-18-1-for-the-amazon-echo-show-5-2021.4772598/
- XDA, unlock/root/TWRP/unbrick thread for the Echo Show 5 2nd gen `cronos` (supplied by the user; could not be opened): https://xdaforums.com/t/unlock-root-twrp-unbrick-amazon-echo-show-5-2nd-gen-2021-cronos.4772596/
- Home Assistant jailbreak guide covering Echo Show 5 gen 1 and 2 and Show 8 gen 1 (unlock and LineageOS 18.1 flow): https://www.derekseaman.com/2025/11/home-assistant-hacking-your-echo-show-5-and-8.html
- Community camera and Bluetooth project for LineageOS 18.1 on the Echo Show 5 (1st and 2nd gen) and Show 8 (1st gen), including the `amonet` 2.0.1 RAM note: https://github.com/jxlarrea/lineageos-echo-show-camera
- XDA discussion on why the 2nd-gen Echo Show 8 differs (different chip): https://xdaforums.com/t/echo-show-2nd-generation-possible-to-unlock-similar-to-echo-show-8-1st-gen.4771627/
- Hackaday, Echo Show jailbreak (Jan 2026): https://hackaday.com/2026/01/02/jailbreaking-the-amazon-echo-show/
