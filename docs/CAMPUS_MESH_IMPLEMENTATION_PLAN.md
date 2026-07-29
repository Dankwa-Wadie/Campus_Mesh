# Campus Mesh — Revised Implementation Plan

**Scope note:** this repo is not a blank slate. It is already a rebrand of bitchat-android
(`namespace com.campusmesh.android`, `applicationId edu.gctu.campusmesh`, single "first commit").
BLE mesh with multi-hop routing, gossip sync, geohash location channels, Wi-Fi Aware transport
with capability fallback, and the full permission/onboarding flow already exist and work. This
plan builds GCTU-specific features into that structure instead of standing up a parallel
`android_campusmesh/` tree under a different package. Everything below is additive to:

```
app/src/main/java/com/campusmesh/android/
    mesh/        sync/        geohash/      wifi-aware/   onboarding/
    config/      crypto/      noise/        identity/     net/
    service/     services/    ui/           features/     nostr/
    model/       data/        util/ utils/  core/         favorites/  protocol/
```

---

## 1. What already exists (do not rebuild)

| Capability | Existing location |
|---|---|
| BLE peer discovery, GATT client/server, multi-hop packet relay | `mesh/` |
| Gossip-based history/state sync | `sync/GossipSyncManager.kt`, `GCSFilter.kt` |
| Geohash location channels + providers | `geohash/` (`LocationChannelManager.kt`, `FusedLocationProvider.kt`, etc.) |
| Wi-Fi Aware (NAN) transport with support detection | `wifi-aware/WifiAwareController.kt`, `WifiAwareSupport.kt` |
| Runtime permission flow (BLE, location, battery, background location) | `onboarding/` |
| E2E crypto (X25519 + AES-256-GCM via Noise) | `crypto/`, `noise/`, `identity/` |
| Dynamic school config loader | `config/ConfigLoader.kt` |

The manifest already declares `android.hardware.wifi.aware` as `required="false"` — correct,
since NAN chipset support is inconsistent across budget Android devices common on campus. Keep
treating it as an opportunistic fast-path, never a required transport.

---

## 2. Net-new work

### 2.1 Campus config & geofenced mode switching
- Extend `config/ConfigLoader.kt` / `school_config.json` with GCTU campus coordinates:
  - Main Campus (Tesano): `5.5961352, -0.2234766`
  - Abeka Campus (School of IT Business): `5.5995349, -0.2388291`
- New `geohash/SoftGeofenceManager.kt`: wraps `FusedLocationProvider` (already present) with
  Android's `Geofencing` API, radius ~150–250m per campus to absorb GPS drift indoors. Emits an
  `AppMode` change (`MAIN_CAMPUS` / `ABEKA_CAMPUS` / `GENERAL_MESH`) to the existing mesh/UI layer.
- New `data/AppMode.kt` enum + channel presets:
  - On-campus: `#gctu-announcements`, `#computing-cis`, `#engineering`, `#abeka-it-business`
  - Off-campus: `#general-mesh` + auto geohash channels (already supported by `geohash/`)
- Manual override toggle in `SettingsScreen` — geofencing sets a default, never locks the user out
  of switching manually.
- **iOS/PWA caveat, stated explicitly in-product, not just in code comments:** Safari and
  home-screen PWAs cannot receive background geolocation callbacks. On the web client, mode
  detection runs once on page load/foreground (`navigator.geolocation.getCurrentPosition`), not
  as a background push. Native Android gets true background auto-switching; web users get
  check-on-open. Don't advertise these as equivalent in onboarding copy.

### 2.2 Offline campus map (campus mode only)
- New `map/OfflineOsmMapView.kt` + `ui/CampusMapScreen.kt`: bundled OSM tile cache
  (`app/src/main/assets/map_tiles/`) rendering Main Campus and Abeka Campus, with live nearby-peer
  markers. Nothing map-related exists in the repo yet — the only OSM code today is
  `geohash/OpenStreetMapGeocoderProvider.kt`, which does reverse geocoding, not rendering.
  This is genuinely new.
- Gated to `AppMode.MAIN_CAMPUS` / `AppMode.ABEKA_CAMPUS` only. The original plan also rendered a
  live map off-campus, centered on the user's current location anywhere in town; cutting that —
  off-campus / `GENERAL_MESH` mode shows no map screen at all. Avoids bundling tiles for
  arbitrary off-campus areas and avoids broadcasting peer location data once someone's left
  campus, which is also the safer privacy default.
- Driven by the same `AppMode` state as channel switching (2.1) — one mode signal gates both the
  channel list and the map, not two separate location concepts.
- **Peer markers as emoji avatars (Snap Map–style):** each peer picks an emoji in
  `identity/`-backed profile settings; `CampusMapScreen` renders that emoji at their last-known
  on-campus position instead of a generic dot. The emoji choice travels in the existing identity
  packet (`identity/`), no new payload type — just a field.
- **Ghost mode:** a per-user toggle (`SettingsScreen`, persisted alongside the existing
  `BackgroundLocationPreferenceManager` prefs) that stops the device from broadcasting its
  position into the map layer at all — the peer simply doesn't render on anyone's map, same as
  Snapchat's ghost mode. This is a broadcast-suppression switch on the sender side, not a
  visibility filter on the viewer side: when ghosted, the location update is never sent onto the
  mesh, so there's nothing for other clients to withhold or leak. Ghost mode only affects the map
  layer — it doesn't hide the user from chat/channels, and it's independent from campus-mode
  geofencing (a ghosted user can still be in `MAIN_CAMPUS` mode and see the map, just not appear
  on it). Default is **off** (visible) but the toggle should be surfaced during onboarding, not
  buried in settings, given it's a safety-relevant control.

### 2.3 Local web gateway (the actual new subsystem — nothing like this exists yet)
- `server/LocalWebGatewayServer.kt`: embedded Ktor HTTP + WebSocket server bridging the PWA to
  the existing mesh stack (reuses `mesh/MeshService.kt` / `UnifiedMeshService.kt` message bus —
  do not fork a second protocol implementation).
- `server/MDnsRegistrar.kt`: advertises `_campusmesh._tcp.local` on port 8080 via NSD
  (`NsdManager`). iOS Safari resolves `.local` hostnames fine at the OS level (Bonjour ships with
  iOS/macOS) — this part of the original plan holds up.
- `app/src/main/assets/web_pwa/`: `index.html`, `manifest.json`, `crypto.js` (Web Crypto API
  X25519/AES-GCM, matching the native `noise/` implementation byte-for-byte so cross-client
  messages decrypt), `app.js`, `style.css`.

### 2.4 QR onboarding

Dropped the printed-flyer scenario from the original plan — it needed a fixed, IT-managed access
point with permanent credentials, which is an infrastructure dependency outside the app's control
and not worth carrying as a v1 requirement. Onboarding is QR-based through the app only:

- **In-app "Invite Nearby Peers" modal:** use `WifiManager.startLocalOnlyHotspot()` at scan time.
  Its SSID/password are regenerated per session by the OS (API 26+, no supported way around
  this) — that's fine here because the QR is generated live and scanned immediately, so the
  credentials in the code are always current. `HotspotQrDialog.kt` renders it, closes on
  tap/swipe.

### 2.5 GitHub OTA + P2P epidemic update distribution
- `ota/GithubReleaseChecker.kt`: polls `/repos/{owner}/{repo}/releases/latest`, compares
  `versionCode`.
- `ota/P2pApkBroadcaster.kt`: broadcasts `AppUpdateNotice` over the existing mesh transport
  (reuse `mesh/` packet types — add a new packet type, don't build a second radio stack), streams
  the APK in chunks over whichever transport is live (BLE for control messages, Wi-Fi
  Direct/Aware for the bulk transfer given BLE's throughput ceiling).
- `ota/ApkInstallerManager.kt`: verify **both** SHA-256 of the reassembled file **and** the APK's
  actual signing certificate (`PackageManager.getPackageArchiveInfo` + signature comparison
  against the currently-installed app's signer) before invoking the install intent via
  `FileProvider`. Hash-only verification lets a compromised mesh peer redistribute a validly-hashed
  but re-signed malicious build; this was missing from the original plan and is the one
  security-critical addition here.

---

## 3. Onboarding flows

Reviewed against a proposed onboarding writeup — most of it holds up and one piece
(`AccountTransferManager.kt`) turned out to already be built. Corrected the parts that either
contradicted earlier decisions in this doc or don't match what the platform/codebase actually
supports.

### 3.1 iPhone / Safari (PWA)
- Scan the in-app "Invite Nearby Peers" QR (2.4) from a nearby Android host device. No
  printed-poster path in v1 — that's the Scenario 2 flow dropped in 2.4 for needing a fixed,
  IT-managed access point. A writeup step that has the iPhone scan a "printed poster" reintroduces
  it; dropped that line for consistency with 2.4. Say the word if posters should come back — that
  reopens the fixed-AP infrastructure question, not just an app change.
- iPhone joins the ephemeral `startLocalOnlyHotspot()` network, Safari resolves
  `campusmesh.local:8080` via Bonjour, `LocalWebGatewayServer` (2.3) serves the PWA.
- Browser generates an Ed25519 keypair client-side and stores it locally. One real risk: Safari
  can evict site storage (including `localStorage`/`IndexedDB`) for pages that haven't been
  opened in ~7 days, unless the page has been added to the home screen, which iOS treats as a
  persistent, not ephemeral, context. Push "Add to Home Screen" immediately after key generation,
  not as an optional afterthought — otherwise a student who doesn't install right away can lose
  their identity silently with no recovery path (the export/backup flow in 3.4 is native-only).
- "Add to Home Screen" — standard PWA installability via `manifest.json` (2.3).

### 3.2 Android native (APK)
- First install, online: normal browser download of the GitHub release APK — no change needed.
- First install, **offline**, from a nearby peer: this can't work the way "stream the APK over
  BLE/Wi-Fi Aware from a nearby phone" implies, because a device with zero Campus Mesh installed
  has no mesh code running to receive that stream — there's nothing on the receiving end to speak
  the protocol yet. The bootstrap has to go through the same path as the iPhone flow: join the
  peer's hotspot, hit `campusmesh.local:8080`, and download the `.apk` as a plain file served by
  `LocalWebGatewayServer`, then the OS's normal "install unknown apps" permission prompt. The
  BLE/Wi-Fi Aware P2P broadcaster in 2.5 (`P2pApkBroadcaster`) is for **updating devices that
  already have the app** — it's not a bootstrap mechanism for a fresh install.
- Setup wizard: campus selection feeds `AppMode` (2.1). Identity generation goes through the
  existing `identity/SecureIdentityStateManager.kt`, which stores keys in
  `EncryptedSharedPreferences` wrapped by an Android Keystore master key — worth being precise
  that this is hardware-backed *encrypted storage*, not a non-exportable TEE-resident keypair.
  That's actually the correct design: true TEE-locked, non-exportable keys would make the identity
  migration flow in 3.4 impossible, since there'd be nothing to export.
- Lands on the main timeline with campus channels + offline map (2.1, 2.2), gated by the selected
  `AppMode`.

### 3.3 Staff verification — lightweight v1, not full attestation
A writeup step has an admin device signing a live Role Attestation Certificate during onboarding.
That's exactly the subsystem marked deferred below (`RoleAttestationManager`, revocation ledger,
M-of-N multi-sig) — building it into onboarding now would undo that call, and the primitives it
needs don't exist in the codebase yet. Proposed v1 substitute that gets the same visible outcome
without the new crypto subsystem: a signed allowlist of verified staff public keys shipped inside
`school_config.json` and distributed through the same GitHub release/OTA channel as app updates
(IT/admin maintains it out of band, e.g. a spreadsheet → signed JSON push). A device checks a
peer's pubkey against the allowlist to render `[GCTU Official • Verified]` in
`#gctu-announcements`. No on-device certificate issuance, no revocation ledger — revocation is
just "drop them from the next config push." This is a small extension of `config/ConfigLoader.kt`
(2.1), not a new subsystem. Live attestation ceremonies stay deferred until there's a concrete
impersonation incident to justify the added complexity.

### 3.4 Identity migration / new device
- Mostly already built. `crypto/AccountTransferManager.kt` exists today and already generates a
  rolling, time-boxed, AES-GCM–encrypted migration QR (`generateMigrationPackage`, 60-second
  window) delivered via the same gateway-IP URL pattern as 2.3 — this matches the "rolling QR
  transfer" idea closely enough that it's closer to "ship as-is" than "build."
- One correction worth making in code, not just this doc: `AccountTransferManager` currently uses
  a 16-byte AES key (AES-128-GCM), not AES-256. Recommend bumping `KEY_LENGTH_BYTE` to 32 for
  consistency with the AES-256-GCM used elsewhere for message encryption (table in section 1).
  Flagging it here rather than changing it silently, since it's a crypto parameter change outside
  the scope of this doc pass.
- This flow is single-identity **replacement** — moving to a new phone. A **concurrent** secondary
  device (e.g., a tablet used alongside the phone, each with its own independently revocable
  sub-identity) is a materially different feature: it needs the same certificate/revocation
  infrastructure as staff attestation (3.3), so it should be deferred alongside it rather than
  folded into v1's "I got a new phone" migration, which is already working.

---

## 4. Deferred out of v1

Role Attestation Certificates, a persistent revocation ledger, and M-of-N multi-sig threshold
validation (`security/RoleAttestationManager.kt`, `RevocationLedgerStore.kt`,
`MultiSigThresholdValidator.kt` in the original plan) are a substantial new crypto subsystem with
no existing analog in the codebase. Recommend shipping v1 on the identity model already working
in `crypto/` / `noise/` / `identity/`, and revisiting attestation only if a specific abuse case
shows up in practice (e.g., impersonation of lecturer/admin roles in `#gctu-announcements`).
Building it up front is speculative scope against a codebase that doesn't have the primitives yet.

---

## 5. Sequencing

1. Campus config + `SoftGeofenceManager` + channel presets, plus the staff verified-badge
   allowlist (3.3) — all extend `config/ConfigLoader.kt`/`geohash/`/`onboarding/`, no new
   subsystems.
2. Offline campus map, gated to `AppMode.MAIN_CAMPUS` / `AppMode.ABEKA_CAMPUS` — depends on the
   `AppMode` state from step 1.
3. `LocalWebGatewayServer` + `MDnsRegistrar` + PWA bundle + `HotspotQrDialog` — also unblocks the
   offline first-install path for both iPhone and Android (3.1, 3.2).
4. OTA checker + P2P APK broadcaster + installer with signature verification.
5. AES-256 bump for `AccountTransferManager` (3.4) — small, isolated crypto change.
6. Role attestation / multi-sig / concurrent sub-device certs — only if a concrete need emerges
   post-launch.

---

## 6. Verification plan

- `./gradlew assembleDebug` — confirm the additions compile against the existing module graph
  (already succeeds today per the checked-in `app-universal-release.apk`).
- Unit tests: `SoftGeofenceManagerTest` (mode transitions at campus boundaries),
  `ApkInstallerManagerTest` (rejects hash-valid/signature-mismatched APKs), ghost-mode test
  confirming a ghosted device never emits a location update packet onto the mesh (not just that
  the UI hides it).
- Manual device tests:
  - "Invite Nearby Peers": tap it, scan from a second Android phone and an iPhone, confirm both
    land on `campusmesh.local` in their default browser.
  - Geofence: walk a test device across the Main Campus boundary, confirm mode auto-switches;
    confirm the same walk on a PWA session only updates on foreground/reload, not live.
  - Map: confirm `CampusMapScreen` is reachable in `MAIN_CAMPUS`/`ABEKA_CAMPUS` mode and that no
    map entry point is shown/reachable once the device is in `GENERAL_MESH` (off-campus) mode.
  - Ghost mode: toggle it on for one test peer, confirm its emoji marker disappears from a second
    device's map in real time, and confirm re-toggling off makes it reappear.
  - OTA: intentionally re-sign a test APK with a different key but matching hash payload,
    confirm `ApkInstallerManager` rejects it.
  - Bootstrap install: with a factory-reset Android device (Campus Mesh not installed, no
    internet), join a host's "Invite Nearby Peers" hotspot and confirm the `.apk` downloads via
    `campusmesh.local:8080` rather than relying on the P2P mesh broadcaster.
  - Staff badge: add a test pubkey to the signed allowlist, push it through the OTA config
    channel, confirm `[GCTU Official • Verified]` appears on that peer's messages in
    `#gctu-announcements` without any on-device certificate exchange.
