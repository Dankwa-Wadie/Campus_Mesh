# Campus Mesh — UI Redesign Implementation Plan

Status: **all 8 phases implemented.** Design is final (§4); see §7 for the full phase-by-phase
implementation log, including two deliberately deferred follow-ups (avatar-broadcast TLV, channel
settings screen). No Android build environment is available in this workspace, so every change was
verified via brace/paren-balance checks and manual tracing rather than a real compile — a real build
+ on-device pass is recommended before shipping.

Decisions locked in so far (from your answers):
- **Full rebrand.** Drop the black/neon-green "terminal" look entirely. Standard chat-app light/dark
  theme, real typography, avatars. GCTU identity lives in the brand colors themselves now (see
  below), not just a logo accent.
- **Full scope.** Chat list, conversation view, all bottom sheets/dialogs, Settings & Profile, Map,
  and the first-run onboarding/setup wizard all get restyled.
- **Blue and gold — GCTU's actual official colors, not a guess.** Figma Make ran out of credits
  mid-project, so instead of continuing there, the design was authored directly as a self-contained
  HTML mockup (`campus_mesh_design_mockups.html`, in your outputs folder) covering all 7 screens in
  both themes. Confirmed via GCTU's own crest page that blue and gold are the university's real
  identity colors (blue = stability/confidence/intelligence, gold = knowledge/hope/illumination),
  applied the way WhatsApp applies green — as accent, not the whole palette. §4 now reflects the
  exact hex values from that mockup, not visual estimates.

---

## 1. Why this is a bigger change than it sounds

The single biggest gap between Campus Mesh today and WhatsApp/Telegram/Messages isn't color — it's
**navigation model**.

Today: there is no chat list. The app has no `NavHost`/navigation graph at all — `MainActivity`
state-switches between onboarding screens and a single `ChatScreen`, and *within* `ChatScreen`,
switching between the mesh timeline, a channel, or a geohash location is done via header buttons
that open bottom sheets (`LocationChannelsSheet`, `MeshPeerListSheet`), not by navigating to a
different screen. There's one visible conversation at a time, chosen from a sheet.

WhatsApp/Telegram/Messages: the home screen **is** a list of conversations (each row: avatar, name,
last message preview, timestamp, unread badge). Tapping a row pushes a detail screen for that one
conversation. Going back returns to the list.

Getting the "intuitive, familiar" feeling isn't achievable by re-skinning the current screens in new
colors — it requires introducing that list-then-detail structure. That's the main architectural
piece of this plan; the visual system (Figma) is the other half.

---

## 2. Current state audit

| Area | File(s) | Current shape |
|---|---|---|
| Theming | `ui/theme/Theme.kt` | Two hardcoded `ColorScheme`s (black+neon-green dark, white+dark-green light), `ThemePreferenceManager` already supports a dark/light/system toggle — reusable. |
| Typography | `ui/theme/Type.kt` (implied) | Monospace throughout, terminal aesthetic. |
| Top-level screen | `ui/ChatScreen.kt` | One large composable: header + message list + input bar + ~10 sheet/dialog booleans. No concept of "list of chats." |
| Header | `ui/ChatHeader.kt` | Custom `MainHeader`/`ChannelHeader`, icon row (location badge, invite peers, bookmarks, Tor dot, PoW indicator, peer counter). |
| Conversation switching | `ui/LocationChannelsSheet.kt`, `ui/MeshPeerListSheet.kt` | Bottom sheets list mesh/geohash/campus channels and peers; selecting one swaps the active timeline in place. |
| Messages | `ui/MessageComponents.kt`, `ui/media/*` | **Correction (found while starting Phase 1):** text messages have no bubble container at all today — `MessageItem`/`MessageTextWithClickableNicknames` render a flat, left-aligned, IRC-style log line (nickname + timestamp inline, colored text, no background shape, no left/right alignment by sender). Delivery-status ticks and image/audio/file attachment rendering do already exist and are genuinely close to reusable. Building an actual bubble (rounded container, right-aligned + tinted for own messages) is real new work, not a re-skin — moved explicitly into Phase 3 below rather than claimed as already-done. |
| Private chats | `ui/PrivateChatManager.kt`, `ui/ChatUserSheet.kt` | 1:1 DMs exist but are reached via tapping a nickname, not from a persistent list. |
| Onboarding | `onboarding/*Screen.kt`, `ui/SetupWizardScreen.kt` | Sequential full-screen steps, terminal-styled, state-driven from `MainActivity`. |
| Navigation | — | None. No Navigation Compose, no back stack beyond the manual `OnBackPressedCallback` in `MainActivity`. |

Nothing here needs to be thrown away — the mesh/BLE/Wi-Fi-Aware logic, geohash channels, campus
channels, ghost mode, and the chunked file transfer built this session are all independent of the UI
layer and untouched by this plan.

---

## 3. Target structure

1. **Chat list (new home screen).** One row per: joined channel (including the pinned Main Campus
   channel + its sub-channels), active private chat, and a "Mesh" row for the general timeline.
   Each row: avatar/icon, name, last message preview, timestamp, unread badge. Tapping opens a
   conversation detail screen.
2. **Conversation detail (restyled existing `ChatScreen`).** Becomes a "pushed" screen instead of
   the app's permanent root — header with back button, avatar, name/status; message list keeps its
   existing bubble components (re-skinned, not rebuilt); input bar at bottom with attachment
   (generic file), image, and voice-note buttons all visible side by side. (Note: the generic-file
   attachment button already existed as a component but was commented out of the live UI — a real
   functional gap, since it's the only way to trigger the chunked/resumable file transfer built
   earlier for non-image files. Re-enabled already, independent of this redesign.)
3. **New-conversation entry point.** A floating action button or header icon on the chat list for
   "join a channel" / "find nearby people" — replaces today's mode of only reaching those via
   in-conversation sheets.
4. **Sheets restyled, not restructured.** Location/campus channels, peer list, verification, etc.
   keep their current sheet-based interaction model (that part already matches Telegram/WhatsApp
   conventions) but get the new visual system.
5. **Onboarding restyled.** Same screen sequence and logic, new visual system — no flow changes
   needed here, this is presentation-only.
6. **Settings & Profile (new screen).** Reached via a gear icon in the chat-list header. Consolidates
   profile card, nickname editing, avatar picker (new — see below), role toggle, and dark-theme
   toggle into one screen — see the new subsection below for why this doesn't exist today.
   **Ghost Mode does NOT live here** — see item 7.
7. **Map (new screen).** An offline campus map with peer markers, designed in the HTML mockup.
   **Ghost Mode's toggle lives on this screen, not in Settings** (per your review comment): the map
   is the one place its effect — visible vs. hidden — is actually seen, so the control belongs
   there, in place, with no navigation to Settings required.
   **Entry points — resolved.** Two ways in: a map-pin icon in the chat-list header (alongside
   search and new-chat), and a "View on map" row at the top of the Channels & Nearby sheet
   (contextual, since that sheet already shows peer distances like `~40m`).

**Avatars — resolved, now with a customization layer.** The Figma design uses initials-on-colored-
circle avatars (e.g. "KA" on blue, "AO" on purple) — no photo upload, and per your latest review
comment, users should also be able to customize theirs and have that change show up wherever their
avatar appears, including the Map. Recommending: a preset picker (choose a color and/or a small
icon/emoji from a fixed set) rather than open photo upload — same reasoning as ruling out Bitmoji
earlier: cheap to broadcast, no privacy exposure, no trademark risk. Falls back to the deterministic
hash-based color/initials for anyone who hasn't customized.

This is a small protocol change, not just UI: today's hash-based avatar needs no network sync
(every device computes the same thing locally), but a user-chosen avatar has to be broadcast so
peers see what was actually picked. Good news — `model/IdentityAnnouncement.kt` (the existing
TLV-encoded identity packet: nickname + Noise key + signing key) already has a forward-compatible
extension point built in: its decoder explicitly skips unknown TLV types rather than failing, which
is exactly how `SIGNING_PUBLIC_KEY` was added after the fact. Adding a 4th TLV (`AVATAR`, small
payload — a color index and/or icon index, not image bytes) fits the same low-risk pattern used for
the chunked file transfer this session: extend an existing, working packet type rather than invent
a new one. Edited on the Settings & Profile screen (next to nickname/role); the Map screen and
everywhere else just displays whatever was broadcast.

**Settings & Profile — a genuinely new screen, confirmed by the design.** Checked the codebase:
there is no unified settings screen today. Nickname editing, role, and theme toggle are scattered
across `AboutSheet.kt` and wherever `ThemePreferenceManager` is currently surfaced (Ghost Mode
itself currently lives in `LocationChannelsSheet.kt`, but per the decision above it's moving to the
new Map screen, not into Settings). The design has one dedicated "Settings & Profile" screen
(reached via a gear icon in the chat-list header) consolidating: profile card (avatar, nickname,
"Connected to Main Campus mesh" status, role badge), editable nickname, Student/Lecturer role
toggle, and Dark Theme toggle. This becomes a target surface alongside chat list / conversation /
sheets / onboarding / map in §3.

**Resolved — Ghost Mode lives only on the Map screen.** The HTML mockup's Channels & Nearby sheet
does not include a Ghost Mode toggle (it has the "View on map" link instead, see §3.7) — single
source of truth, no duplicate controls across three surfaces.

---

## 4. Design system plan (final — from `campus_mesh_design_mockups.html`)

Figma Make ran out of credits partway through, so the final design was authored directly as a
self-contained HTML mockup instead (all 7 screens, both themes, live theme/screen switcher). Colors
below are the exact hex values used in that file, not estimates — confirmed against GCTU's real
brand identity (blue and gold, per the university's own crest page).

**Color — light theme**
- Background: `#F5F7FA` · Surface (cards/rows): `#FFFFFF` · Border: `#E5E9F0`
- Primary/accent blue (header bar, buttons, links, app icon): `#17458F`, darker shade `#0E2F63`
- Gold accent (badges, role/campus pills, avatar ring, CTA buttons on blue): `#D8A73D`, with light
  tint `#FBEFD1` and dark text-on-gold `#8A6314`
- Outgoing message bubble: light blue tint `#DCE7FA`, primary text color
- Incoming message bubble: white (`#FFFFFF`) with a 1px `#E5E9F0` border
- Unread badges — two-tone: gold `#D8A73D` on channel rows, blue `#17458F` on DM rows
- Role/campus pill: `#FBEFD1` background, `#8A6314` text
- Outlined "Join" pill button: `#17458F` border and text, transparent fill
- Text: primary `#1A2233`, secondary `#6B7280`, muted `#9AA3B2`

**Color — dark theme**
- Background: `#0B1526` · Surface (cards/rows): `#13203A` · Border: `#22314F`
- Primary/accent blue (brighter for dark-bg contrast): `#3E7BEA`, darker shade `#0B1E3D`
- Gold accent: `#E3B655`, light tint `#3A2D12`, text-on-gold `#F2C766`
- Outgoing bubble: `#1B355E` · Incoming bubble: `#182741`
- Text: primary `#EEF1F6`, secondary `#A7B0C0`, muted `#6E7890`
- Avatar palette (both themes, same hues, adjusted for contrast): blue `#3A6FD1`/`#4C7EE0`, purple
  `#8B5CF6`/`#9B7BF0`, orange `#E08A2C`/`#E39C4C`, teal `#2AA198`/`#38B3A2`, pink `#DB5C93`/`#E374A6`

**Typography**
- Headings ("Campus Mesh" splash title, screen titles): bold, rounded sans-serif — `Poppins` /
  `Segoe UI Rounded` / `Nunito` stack, falling back to `system-ui`. Recommend bundling Poppins as an
  actual font resource (small, free, matches the mockup) rather than approximating with Inter.
- Body/message text: regular-weight, same family stack, smaller sizes (~12–13.5px in the mockup).
- Timestamps/metadata: 9.5–11px, muted/secondary color, same family.

**Confirmed structural patterns (validates §1 and §3 directly)**
- Chat list → tap row → conversation detail is exactly the pushed-screen pattern this plan assumed.
- The pinned "Main Campus" channel + `#gctu-announcements` / `#computing-cis` / `#engineering`
  sub-channels + "Nearby" joinable channels all appear together in one "Channels & Nearby"
  **sheet** (not a separate screen), now with a "View on map" link at the top — confirms §3.4's
  "sheets restyled, not restructured" call was right.
- Conversation input bar: attachment (paperclip), camera, text field, mic — left-to-right, matches
  the already-reinstated `FilePickerButton` + existing image/voice send affordances exactly.
- A file-transfer message bubble is shown mid-send ("Lab_Assignment_Sem2... · 4.2 MB · Sending
  60%" with a circular progress ring) — this is a design-level acknowledgment of exactly the
  chunked transfer progress UI built earlier this session (`TransferProgressManager` +
  `DeliveryStatus.PartiallyDelivered`); no new component needed, just re-skin the existing one.
  Voice notes render as a play button + waveform + duration, matching existing audio bubble UI.
- Delivery ticks (checkmarks) appear on sent messages, confirming the existing delivery-status
  system carries over as-is, just re-colored.
- The app's mesh/radio-waves logo icon `(())` is used consistently as brand mark and as the "Mesh"
  row icon in the chat list — worth adopting as the one recognizable icon for anything
  mesh-related, replacing generic Bluetooth icons currently used ad hoc.

**Component library to build once, reuse everywhere**
- `ColorScheme` (light + dark) replacing `Theme.kt`'s two hardcoded schemes, mapped to Material3
  roles so every existing `MaterialTheme.colorScheme.*` reference across ~40 files updates without
  a hunt-and-replace.
- Typography scale replacing the monospace-everywhere `Type.kt`.
- Initials avatar component (deterministic color from nickname/peerID hash).
- Chat list row, re-skinned message bubble (text/image/audio/file-progress variants), app bar,
  two-tone unread badge, role/campus pill, outlined "Join" pill button.

---

## 5. Phased implementation

1. **Foundation** — new `ColorScheme` + typography + core components (avatar, chat-list row,
   restyled bubble, app bar), built and visually verified in isolation before touching real screens.
2. **Chat list screen** — new home screen, sourced from existing `joinedChannels`/`privateChats`/
   mesh state (no backend changes), navigates to conversation detail.
3. **Conversation detail** — restyle `ChatScreen`'s header/input bar/bubbles with the new system,
   convert it into a "pushed" screen reached from the chat list.
4. **Sheets & dialogs** — restyle location/campus/peer/verification sheets.
5. **Settings & Profile screen (new)** — build the consolidated screen described in §3.6, wiring
   up existing but scattered logic (nickname edit, role, `ThemePreferenceManager`) into one place
   instead of writing new state management. Also where the new avatar picker gets built, plus the
   small `IdentityAnnouncement` TLV extension (§3) needed to broadcast the chosen avatar to peers.
   Ghost Mode is intentionally NOT part of this phase.
6. **Map screen (new)** — build once the Figma design for it exists; wires up the existing
   `GhostModePreferenceManager`/`AppStateStore.isGhostMode` toggle logic (already built this
   session) directly on the map, plus peer markers sourced from existing mesh/geohash peer state
   and rendered using each peer's broadcast avatar (color/icon) from the TLV extension in phase 5.
7. **Onboarding** — restyle onboarding + setup wizard screens.
8. **Polish + regression pass** — verify nothing from this session (chunked file transfer progress
   UI, Ghost Mode toggle, pinned campus channels, invite-peers QR) broke visually or functionally
   under the new theme.

Each phase is independently buildable/reviewable rather than one giant diff.

---

## 6. Slash commands in the new design

Campus Mesh inherited an IRC-style `/` command bar with autocomplete (`ui/CommandProcessor.kt`).
Checked what each command actually does against the existing tap UI before deciding anything:

| Command | Already has tap UI? | Plan |
|---|---|---|
| `/msg`, `/hug`, `/slap`, `/block`, `/unblock` | **Yes** — tapping a user's name opens `ChatUserSheet`, which already has Private Message / Slap / Hug / Block rows that call these commands internally. | No new work — already fully redundant with tap UI. |
| `/w` (who's online) | **Yes** — peer list sheet and geohash participant counts already show this. | No new work needed. |
| `/channels` | **Yes** — the new chat list screen (§3.1) *is* this view, more visibly. | Superseded by the redesign itself. |
| `/join` (create/join a channel by name) | **No** — this is a real gap. There's no button today for joining an arbitrary channel by name; only the fixed campus preset list has tap UI. | Add a "+ New channel" entry point on the chat list: name field + optional password. |
| `/clear` | **No** button today. | Add a "Clear chat" item to a conversation's overflow menu — standard WhatsApp/Telegram pattern. |
| `/pass`, `/transfer` (channel password / ownership handoff, admin-only) | **No** UI today. | Keep the command logic; build a proper channel settings screen for these in a **later phase**, not part of the initial redesign — not blocking Phase 1–6 above. |
| `/save` | **No** UI today. | Not addressed by this plan; low-priority, revisit later if needed. |

**Decision on the command bar itself:** the visible "/" input and autocomplete dropdown gets removed
from the redesigned UI — typing "/" won't show a suggestion list anymore, since that's an IRC/Discord
pattern that doesn't fit a WhatsApp/Telegram-style app. The underlying command parsing
(`CommandProcessor.processCommand`) stays working as an **undocumented shortcut** — if someone who
knows a command types it (e.g. `/join #dorms`), it still executes. Nothing about this is advertised
anywhere in the new UI; it's a fallback for muscle memory, not a feature to design around.

---

## 7. Status

Approved. Design is final (§4), all open questions resolved (avatars, Ghost Mode placement, Map
entry points). Implementation started on the 8-phase order in §5.

**Phase 1 (foundation) — done:**
- `ui/theme/Theme.kt`: both `ColorScheme`s replaced with the real blue/gold values from §4, mapped
  to Material3 roles (`primaryContainer`/`secondaryContainer` carry the bubble-tint and pill-tint
  colors so downstream screens can reference them directly instead of hardcoding hex again).
- `ui/theme/Typography.kt` + a sweep of 178 hardcoded `FontFamily.Monospace` references across 28
  files: replaced with `FontFamily.SansSerif` everywhere user-facing. `ui/debug/*` intentionally
  left on monospace — it's developer-only tooling and monospace still helps align technical
  fields (peer IDs, hex fingerprints) there; not part of the rebrand's scope.
- New reusable components, isolated and ready for reuse in later phases:
  `core/ui/component/avatar/InitialsAvatar.kt` (deterministic color + initials from a
  nickname/peerID seed, `overrideColor` param already wired for when the avatar-customization TLV
  from §3 lands) and `core/ui/component/badge/UnreadBadge.kt` (two-tone gold/blue badge).
- **Descoped from Phase 1, moved to Phase 3:** chat-list row and message bubble were originally
  listed as Phase 1 deliverables, but building them in isolation right now would mean guessing at
  data shapes Phase 2/3 haven't settled yet (and, per the correction above, the bubble is new
  structural work on `MessageComponents.kt`, not an isolated component). Building them alongside
  the screens that actually use them is lower-risk.

**Phase 2 (chat list screen) — done:**
- **Architecture decision:** §1 characterized the navigation gap in terms of "no `NavHost`,"
  but actually adding the Navigation Compose library as a new Gradle dependency I can't
  verify resolves without a compiler was more risk than the goal required. Built the same
  list-then-detail UX instead with one new orthogonal `showChatList: StateFlow<Boolean>` on
  `ChatState`/`ChatViewModel` (`openConversation()` / `returnToChatList()`), and one additive
  branch in the existing `handleBackPressed()` — the three pre-existing exit-channel/
  exit-private-chat back-press cases are completely untouched, so within-conversation back
  behavior has zero regression risk. `MainActivity`'s `COMPLETE` branch now switches between
  the new `ChatListScreen` and the existing `ChatScreen` based on that flag.
- `ui/ChatListScreen.kt`: pinned Main Campus block (building icon, pin, its 3 fixed sub-channels
  from `MainCampusGeohash.SUB_CHANNELS`) at the top, then every other joined channel, private
  chat, and the general Mesh row merged into one recency-sorted list — all sourced from existing
  `ChatViewModel` StateFlows (`joinedChannels`, `channelMessages`, `privateChats`,
  `unreadChannelMessages`, `unreadPrivateMessages`, `connectedPeers`), no backend changes.
  Uses `InitialsAvatar` and `UnreadBadge` from Phase 1. Tapping a row calls the existing
  `switchToChannel`/`startPrivateChat`/`selectLocationChannel` functions unchanged, then
  `openConversation()`.
- New-conversation entry point (§3.3): the pencil icon in the chat-list header opens the existing
  `LocationChannelsSheet` ("Channels & Nearby") rather than a new screen — that sheet already is
  the join-a-channel / see-nearby experience, just reached from a new place.
- **Known gaps, deliberately deferred rather than half-built:** the search icon in the header is a
  placeholder (no chat search exists anywhere in the app yet, not just here); there's no
  settings-gear or map-pin icon in the header yet since Settings & Profile (Phase 5) and Map
  (Phase 6) aren't built as real screens yet — both get wired in once those phases land.

**Phase 3 (conversation detail) — done:**
- `ui/MessageComponents.kt`'s `MessageItem`: wrapped the existing (unrestructured) inner content in
  a new outer aligned `Row`/`Box` bubble — own messages right-aligned on `primaryContainer`, others
  left-aligned on `surfaceVariant`, with an asymmetric corner radius (WhatsApp-style tail corner).
  All the pre-existing nickname-highlighting/link-preview/delivery-status logic inside stayed
  untouched; this was purely a wrapping change, not a rewrite.
- `ui/InputComponents.kt`: gave the message text field a rounded pill background, and fixed a real
  latent bug found while touching this file — `colorScheme.background == Color.Black` was being
  used in three places as a stale proxy for "is dark theme," which silently broke the moment
  Phase 1 changed the dark background off pure black. Replaced all three with direct
  `colorScheme.primary`/`onPrimary` references.
- `ui/ChatHeader.kt`: inspected, deliberately **not** restructured. Flipping the header background
  to solid blue would make the brand title and nickname editor text (both `colorScheme.primary`)
  invisible against it — a real contrast bug I can't fix blind without a compiler to check text
  color at the same time. Scoped out rather than risking it.

**Phase 4 (sheets & dialogs) — done:**
- `SecurityVerificationSheet.kt`, `LocationNotesSheet.kt`, `VerificationSheet.kt`: each had a
  leftover `if (isDark) Color.Green else Color(0xFF008000)` accent from the old terminal-green
  branding, unrelated to the stale-black-check bug from Phase 3 but the same class of "old theme
  hardcoded past the color-system rewrite." Replaced all three with `MaterialTheme.colorScheme.primary`.
- `ChatUIUtils.kt`'s `getRSSIColor()` checked and left alone — its green/yellow/orange gradient is
  legitimate signal-strength semantics, not branding.

**Phase 5 (Settings & Profile screen) — done:**
- `ui/theme/AvatarPreferenceManager.kt` (new): local-only avatar color override, same
  SharedPreferences + StateFlow pattern as `ThemePreferenceManager`. Explicitly scoped down from
  the original plan — broadcasting the choice to peers needs the `IdentityAnnouncement` TLV
  extension described in §3, which is real protocol work I didn't attempt without a compiler to
  verify it; tracked as a follow-up, not silently dropped. Right now this changes what *your own*
  device shows for your avatar everywhere it renders locally (chat list, this screen, the Map).
- `core/ui/component/avatar/InitialsAvatar.kt`: `AvatarPalette` made public, added
  `avatarColorForIndex()` helper.
- `ui/SettingsScreen.kt` (new): profile card (avatar with gold ring, nickname, "Connected to Main
  Campus mesh" status, role pill), editable nickname wired to the existing `viewModel.setNickname()`
  (which already persists and re-announces over the mesh — no new logic needed), avatar picker
  wired to `AvatarPreferenceManager`, Student/Lecturer toggle wired to `viewModel.setRole()`, and a
  Dark Theme switch wired to the existing `ThemePreferenceManager`. **No Ghost Mode control** —
  confirmed placement decision, it lives only on the Map screen (Phase 6).
- Navigation: added `showSettings: StateFlow<Boolean>` to `ChatState`/`ChatViewModel`
  (`openSettings()`/`closeSettings()`), same orthogonal-flag pattern as `showChatList`, checked
  first in `handleBackPressed()` and with top priority in `MainActivity`'s screen switch. Wired the
  chat-list header's settings-gear icon to `openSettings()`.

**Phase 6 (Map screen) — done:**
- `ui/MapScreen.kt` (new): an illustrated, schematic view of Main Campus, not a real GPS map —
  there is no lat/lon data anywhere in this app by design, so marker positions are a deterministic
  hash of each person's ID (stable across restarts, not tied to physical location). Markers are
  built from the union of `connectedPeers` (direct mesh peers) and `geohashPeople` (Main Campus
  geohash participants), deduped by ID — both already-existing `ChatViewModel` state, no backend
  changes. Tapping a marker opens a card with a "Message" action that calls the existing
  `startPrivateChat()` + `openConversation()`.
- **Ghost Mode moved here from `LocationChannelsSheet.kt`**, per the already-resolved review
  decision: it's wired directly to the existing `GhostModePreferenceManager`/
  `AppStateStore.isGhostMode` (built in an earlier session), and toggling it hides your own "You"
  marker plus shows a small "Ghost Mode on" pill — the one place its effect is actually visible.
  `LocationChannelsSheet.kt`'s old ghost-mode toggle row was replaced with a "View on map" row
  (only rendered when the sheet is given an `onOpenMap` callback), matching the mockup's resolved
  design.
- **Descoped from this phase:** peer markers use the same deterministic-hash avatar color as
  everywhere else in the app, not each peer's broadcast avatar choice — that depends on the
  `IdentityAnnouncement` TLV extension noted as a Phase 5 follow-up, not yet built.
- Navigation: added `showMap: StateFlow<Boolean>` to `ChatState`/`ChatViewModel`
  (`openMap()`/`closeMap()`), same pattern as `showSettings`, same back-press/priority treatment.
  Entry points: the map-pin icon in the chat-list header, and the new "View on map" row in the
  Channels & Nearby sheet (wired from both places the sheet is used — the chat list and the
  in-conversation header).

**Phase 7 (onboarding restyle) — done:**
- Spot-checked `onboarding/*` (already covered by Phase 1's `ColorScheme`/`FontFamily` sweep) and
  `SetupWizardScreen.kt` (confirmed still the 2-step welcome → profile flow, no drift).
- Found and fixed two real leftover-brand bugs: `LocationCheckScreen.kt` and
  `BluetoothCheckScreen.kt` both had `Color(0xFF00C851)` — explicitly commented "App's main green
  color" — used as the dominant icon tint and primary CTA button color on their permission-request
  screens, plus a matching green loading-spinner. All replaced with `colorScheme.primary`. Left the
  small "privacy assurance" checkmark green and the generic "Bluetooth blue" spinner alone — those
  are universal semantic colors (trust/security, Bluetooth-brand association), not old-theme
  leftovers, same reasoning as `ChatUIUtils.getRSSIColor()`.

**Phase 8 (polish + regression pass) — done:**
- Ran a repo-wide sweep for the old terminal-green brand hex values (`0xFF00C851`, `0xFF00FF7F`,
  `0xFF00FF00`, `Color.Green`) outside `ui/debug/*` (intentionally out of scope, same as the Phase 1
  monospace sweep) and triaged each hit as either a genuine leftover-brand bug or a legitimate
  semantic color (status dots, RSSI, traffic-light indicators) worth keeping:
  - **Fixed:** `ChatHeader.kt`'s "Invite Nearby Peers" icon (green → primary) and bookmark-active
    icon (green → gold, matching the design system's gold-for-highlighted-state convention);
    `ChatScreen.kt`'s scroll-to-bottom FAB border/icon (green → primary); `media/FileSendingAnimation.kt`'s
    file icon (green → primary) **and** its filename text, which was hardcoded `Color.White` — a real
    contrast bug once this renders inside the new bubble system's `primaryContainer`/`surfaceVariant`
    backgrounds instead of the old solid-black terminal background; `media/WaveformViews.kt`'s voice
    waveform (green → primary while sending, gold while playing back) and its live-recording variant;
    `media/RealtimeScrollingWaveform.kt`'s recording waveform (call site now passes `colorScheme.primary`
    explicitly); `InputComponents.kt`'s slash-command inline highlight (bright-green-on-dark-gray →
    theme primary, passed in from the composable since `VisualTransformation.filter()` isn't a
    `@Composable` context); `LinkPreviewPill.kt`'s link-preview pill, which had both the leftover
    "iOS-style green" text/border color **and** the same stale `background == Color.Black`-style
    dark-mode heuristic already fixed once in `InputComponents.kt` during Phase 3 — replaced with
    direct `colorScheme` roles, which are correct in both themes without any heuristic.
  - **Also rebuilt:** `media/FileSendingAnimation.kt`'s file-transfer progress indicator — was a
    literal ASCII `[####------] 60%` bracket string in "Matrix green" monospace, replaced with a
    real Material `LinearProgressIndicator` + percentage label, matching the mockup's circular-progress
    file bubble described in §4.
  - **Left alone (legitimate semantic color, not old-brand):** `ChatUIUtils.getRSSIColor()`'s
    signal-strength gradient; `ChatHeader.kt`'s Tor-status traffic-light dot, mesh/geohash people-count
    colors, and `#mesh`/`#geohash` badge colors (all paired green/blue distinctions used consistently
    across the header, would look inconsistent if only some were changed); `InputComponents.kt`'s
    `MentionVisualTransformation` (orange, not green — a distinct, intentional accent for @mentions).
  - `ui/debug/*` (`DebugSettingsSheet.kt`, `MeshGraph.kt`) deliberately left untouched — developer-only
    tooling, same scope decision as the Phase 1 monospace sweep.
- Verified Ghost Mode, pinned campus channels, and invite-peers QR are all still wired correctly
  after the chat-list/Settings/Map navigation changes — none of their underlying logic changed,
  only where their controls live and what color they render in.
- Structural sanity check: brace/paren balance verified across all 29 files touched this session
  (redesign work plus this polish pass) — all balanced.

All 8 phases are now implemented. Two follow-ups remain, tracked but deliberately not attempted in
this pass (both noted in earlier sections): the `IdentityAnnouncement` `AVATAR` TLV extension needed
to broadcast a user's chosen avatar color to peers (currently local-only), and a `/pass`/`/transfer`
channel settings screen (§6) for channel-owner-only commands.
