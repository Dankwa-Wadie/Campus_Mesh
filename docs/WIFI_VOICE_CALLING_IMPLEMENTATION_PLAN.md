# Wi-Fi Aware Voice (and future Video) Calling — Implementation Plan

Status: **Phase 0 and Phase 1 MVP implemented, pivoted to half-duplex (push-to-talk).** Android-only. No iOS, no web/PWA, no QR pairing, no Wi-Fi Direct, no WebRTC.

## Implementation notes (what actually shipped)

- Phase 0: `NoisePayloadType.CALL_SIGNAL`, `MeshDelegate.didReceiveCallSignal`/`MeshService.sendCallSignal`
  wired through `MessageHandler` → `MeshCore`/`BluetoothMeshService` → `UnifiedMeshService` → `ChatViewModel`,
  plus `WifiAwareConnectionTracker.peerNetworks` storing the negotiated `Network` per peer (populated in both
  the server `onAvailable` and client `onCapabilitiesChanged` callbacks in `WifiAwareMeshService`), and the
  `FOREGROUND_SERVICE_MICROPHONE`/`MODIFY_AUDIO_SETTINGS` manifest permissions.
- Phase 1: new `com.campusmesh.android.call` package - `CallSignalingManager` (process-wide singleton state
  machine, OFFER/RINGING/ACCEPT/REJECT/BUSY/END over the existing Noise private-message pipe, with each
  side's local UDP port exchanged in the OFFER/ACCEPT payloads), `CallAudioSession` (binds a
  `DatagramSocket` to the peer's stored Wi-Fi Aware `Network`), `AudioCaptureThread`/`AudioPlaybackThread`
  (raw 16kHz mono PCM, 20ms frames, `VOICE_COMMUNICATION` source, no jitter buffer yet - see Phase 2),
  `CallForegroundService` (its own `foregroundServiceType="microphone"` service, independent of
  `MeshForegroundService`), and `CallOverlay` (mounted once at the MainActivity root so incoming/active
  calls are visible over any screen). The "Call" button lives in a new `PrivateChatHeader` in
  `ChatHeader.kt`, shown only when `WifiAwareController.connectedPeers` contains that peer. Call
  start/end is appended to that peer's private chat as a system message via
  `CallSignalingManager.setOnCallEndedListener`.
- **Pivot: half-duplex (push-to-talk) instead of full-duplex.** Real-device testing found that
  simultaneous bidirectional UDP audio streaming over a single Wi-Fi Aware NAN data path is
  unreliable - both sides transmitting and receiving at once contends for the same Wi-Fi radio and
  garbles audio in both directions. Rather than chase full duplex over a link that doesn't
  reliably support it, calls are now walkie-talkie style: only one side's `AudioCaptureThread` runs
  at a time, gated by a hold-to-talk button.
  - `CallSignalingManager` gained `SIG_TALK_START`/`SIG_TALK_END` signal bytes, a `TalkFloor`
    enum (`IDLE`/`LOCAL_TALKING`/`REMOTE_TALKING`) exposed as `talkFloor: StateFlow<TalkFloor>`,
    and `beginTalking()`/`endTalking()` driven by the UI's press/release. Floor control is
    advisory only (no cross-device mutex) - if both sides press at once, both simply talk over
    each other briefly, same as a real two-way radio; there's no arbitration beyond the UI
    disabling the local button while `REMOTE_TALKING`.
  - `CallAudioSession.startStreaming()` now only starts the `AudioPlaybackThread`, which runs for
    the entire call on both sides (idle - no packets - when nobody's transmitting). The
    `AudioCaptureThread` is a separate lifecycle via new `startTransmitting()`/`stopTransmitting()`,
    created/destroyed per talk-button press rather than for the whole call.
  - `CallOverlay`'s `ConnectedCallBar` gained a large hold-to-talk mic button (press-and-hold via
    `detectTapGestures`/`tryAwaitRelease`) below the existing name/duration/hang-up bar, with a
    status line ("Hold to talk" / "Talking…" / "$peerName is talking…") reflecting `talkFloor`.
  - The OFFER/RINGING/ACCEPT/REJECT/BUSY/END call-setup handshake is completely unchanged - this
    pivot only affects what happens after a call reaches `Connected`.
- Not yet done: Phase 2 (jitter buffer, AEC/NS effect toggling, Opus, packet-loss concealment, missed-call
  notifications) and Phases 3/4 (group calls, video) remain as originally planned below. A hard
  cross-device talk-floor mutex (rather than the current advisory-only approach) could also be
  added later if overlap turns out to be a real problem in practice.

## 1. Decision summary

| Question | Decision | Why |
|---|---|---|
| P2P transport | **Wi-Fi Aware (NAN)** — already built in this codebase | `WifiAwareMeshService`/`WifiAwareController`/`WifiAwareConnectionTracker` already do discovery, data-path negotiation, and give us a live `Network` object per connected peer. No new radio-negotiation system needed. |
| Signaling | Reuse the **existing encrypted private-message pipe** (Noise, `NOISE_ENCRYPTED` packets) | Call offer/accept/reject/end are just a few bytes; riding the pipe that already does E2E encryption, delivery, and peer routing means zero new crypto/routing code. |
| Media transport | **New UDP `DatagramSocket` bound to the same Wi-Fi Aware `Network`** already negotiated for that peer | Same physical link the text mesh already uses over Wi-Fi Aware; TCP (what text messaging uses) is wrong for live audio (head-of-line blocking), so audio gets its own unreliable/low-latency UDP path. |
| Codec (MVP) | **Raw 16 kHz mono 16-bit PCM**, no compression | Wi-Fi Aware bandwidth (10–250 Mbps) makes compression unnecessary for correctness — it's a battery/CPU optimization, not a blocker. Avoids NDK/Opus bring-up for the MVP. |
| Codec (later) | Opus via Android's built-in `MediaCodec` (API 29+) | ~10x bandwidth cut once basic calling is proven and battery profiling justifies the extra complexity. |
| WebRTC / ICE / STUN / TURN | **Not used** | ICE solves NAT traversal over the public internet. There is no internet here — Wi-Fi Aware already hands us a direct local L3 link, so ICE would be solving a problem we don't have. |
| Multi-hop calls | **Not supported** — calls are 1-hop only | Confirmed by your own research: live audio degrades badly past one hop. If two peers aren't in direct Wi-Fi Aware range, the call UI simply won't offer a call button for that peer (same as it wouldn't for a peer only reachable via BLE relay). |
| iOS / Web / QR | **Dropped entirely**, per your instruction | Android-only project from here on. |

## 2. What already exists (audited from the current codebase)

This is the biggest reason this plan is achievable in a reasonable timeframe — the hard 20% (radio discovery + P2P link negotiation) is already done and battle-tested for text:

- `wifi-aware/WifiAwareSupport.kt` — capability detection (API level, `FEATURE_WIFI_AWARE`, runtime availability).
- `wifi-aware/WifiAwareController.kt` — lifecycle: start/stop, permission checks (`NEARBY_WIFI_DEVICES`, location services), auto-restart on radio state changes, debug surfacing.
- `wifi-aware/WifiAwareMeshService.kt` (~1600 lines) — publish/subscribe discovery, `WifiAwareNetworkSpecifier` data-path negotiation, TCP client/server role assignment with automatic role-reversal on failure, Noise handshake kickoff once connected, periodic reconnection maintenance.
- `wifi-aware/WifiAwareConnectionTracker.kt` — per-peer connection state, `NetworkCallback` registry, socket map.
- `wifi-aware/SyncedSocket.kt` — framed TCP protocol (4-byte length prefix) used for the text/file mesh protocol over the negotiated Aware link.
- `protocol/BinaryProtocol.kt` `MessageType` enum and `model/NoiseEncrypted.kt` `NoisePayloadType` enum — both already extensible (see Phase 0).
- `RECORD_AUDIO` permission already declared and already used for async voice notes (`VoiceRecordButton`, `AudioWaveformExtractor`) — not real-time, but confirms the recording permission/pipeline groundwork exists.

**One real gap found:** the `Network` object handed to `NetworkCallback.onAvailable()`/`onCapabilitiesChanged()` in `WifiAwareMeshService` is used transiently to create the TCP socket, then never stored. Calling needs it again later (to bind a UDP socket on the same link), so Phase 0 adds a `peerNetworks` map to `WifiAwareConnectionTracker`.

## 3. Architecture

```
User taps "Call" on a peer (only shown if that peer has a live Wi-Fi Aware connection)
        │
        ▼
CallSignalingManager sends CALL_SIGNAL/OFFER over the existing
Noise-encrypted private-message pipe (same path as a normal DM)
        │
        ▼
Callee's phone shows IncomingCallScreen (ringtone + vibration,
via existing NotificationManager patterns) — Accept / Decline
        │  (Accept)
        ▼
CallSignalingManager sends CALL_SIGNAL/ACCEPT + a UDP port number
        │
        ▼
Both sides: CallAudioSession opens a DatagramSocket, binds it to
network.bindSocket() using the SAME Wi-Fi Aware Network already
stored in WifiAwareConnectionTracker for that peer
        │
        ▼
AudioCaptureThread (AudioRecord, VOICE_COMMUNICATION source)
  → 20ms PCM frames → [seq][timestamp][payload] → socket.send()
AudioPlaybackThread: socket.receive() → small jitter ring buffer
  (drop late frames, no retransmission) → AudioTrack playback
        │
        ▼
Either side hangs up → CALL_SIGNAL/END over signaling pipe →
UDP socket closed → foreground service notification cleared →
system message "📞 Voice call · 2:14" appended to that chat thread
```

No new discovery system, no new pairing UI, no new encryption — this sits entirely on top of what's already running.

## 4. Phased plan

### Phase 0 — Foundational plumbing (no user-visible feature)
- `protocol/BinaryProtocol.kt`: add `CALL_SIGNAL(0x30u)` to `MessageType`. *(Actually not needed if signaling rides `NoisePayloadType` inside an existing `NOISE_ENCRYPTED` packet — see next line. Keep this only if we decide signaling needs its own top-level packet type instead of a Noise sub-type; default plan uses the Noise sub-type route, matching how `FILE_TRANSFER` already piggybacks on `NoisePayloadType.FILE_TRANSFER` for private sends.)*
- `model/NoiseEncrypted.kt`: add `CALL_SIGNAL(0x30u)` to `NoisePayloadType`.
- `wifi-aware/WifiAwareConnectionTracker.kt`: add `peerNetworks: ConcurrentHashMap<String, Network>`, populated in `WifiAwareMeshService`'s existing client (`onCapabilitiesChanged`) and server (`onAvailable`) callbacks right where `SyncedSocket` is created today.
- `AndroidManifest.xml`: add `FOREGROUND_SERVICE_MICROPHONE` permission; extend the foreground service's `android:foregroundServiceType` to include `microphone`; add `MODIFY_AUDIO_SETTINGS` (needed for reliable speakerphone toggling on some OEMs).
- Confirm target/compile SDK covers `foregroundServiceType="microphone"` (API 34 requirement) without breaking the existing `connectedDevice|dataSync|location` service.

### Phase 1 — MVP 1:1 voice call
New package `com.campusmesh.android.call`:

- **`CallSignalingManager`** — builds/parses `CALL_SIGNAL` Noise payloads (`OFFER`, `RINGING`, `ACCEPT` [+ UDP port], `REJECT`, `BUSY`, `END`); exposes `StateFlow<CallState>` (`Idle`, `Outgoing(peerId)`, `IncomingRinging(peerId)`, `Connected(peerId, startedAt)`, `Ended(reason)`). Hooks into the same private-message send/receive path `ChatViewModel`/`BluetoothMeshService`/`WifiAwareMeshService` already use.
- **`CallAudioSession`** — on `ACCEPT`, opens a `DatagramSocket`, calls `network.bindSocket(socket)` using the peer's `Network` from `WifiAwareConnectionTracker.peerNetworks`, starts capture + playback threads.
- **`AudioCaptureThread`** — `AudioRecord` (16kHz mono, `MediaRecorder.AudioSource.VOICE_COMMUNICATION` for hardware AEC/NS where available) → 20ms frames → `[4B seq][8B timestamp][payload]` → `socket.send()`.
- **`AudioPlaybackThread`** — `socket.receive()` → fixed-depth jitter ring buffer keyed by sequence number (drop late/out-of-order beyond the window, no retransmit — matches how real-time voice should degrade) → `AudioTrack` (`STREAM_VOICE_CALL`).
- **`CallForegroundService`** — keeps capture alive with the screen off/app backgrounded; ongoing-call notification with a hang-up action. Either extends `MeshForegroundService` or runs alongside it (needs its own `foregroundServiceType="microphone"`).
- **UI**: `IncomingCallScreen` (ringtone/vibration via existing `NotificationManager`, Accept/Decline), `InCallScreen` (duration, mute, speaker toggle, hang up). A "Call" affordance added to the private-chat header, enabled only when `WifiAwareController.running.value && connectionTracker.isConnected(peerId)` — otherwise hidden/disabled, no dead buttons.
- Call start/end appended to that chat's message list as a system message (`"📞 Voice call · 2:14"`), reusing `messageManager.addPrivateMessage` — no new history UI needed.

### Phase 2 — Reliability & polish
- On `NetworkCallback.onLost()` mid-call: attempt one silent re-bind using the existing 15s reconnection-maintenance loop before surfacing "call dropped" to the UI.
- `AcousticEchoCanceler` / `NoiseSuppressor` audio effects where `isAvailable()` on-device.
- Basic packet-loss concealment (repeat/fade last frame) instead of dead silence on drops.
- Opus via `MediaCodec` behind a capability check, PCM fallback on devices/API levels without it.
- Missed-call notification consistent with existing DM-notification patterns.

### Phase 3 (stretch, lower priority) — Group calls
Needs a star topology (one peer relays audio to N others over N simultaneous Wi-Fi Aware data paths) since multi-hop live audio isn't viable. Meaningfully harder than 1:1 — defer until 1:1 calling is solid and actually used.

### Phase 4 (stretch) — Video calls
`Camera2` capture → hardware `MediaCodec` H.264 encoder → same UDP transport (separate port or a type byte muxed into the existing frame header) → `MediaCodec` H.264 decoder → `SurfaceView`. Bandwidth is a non-issue over Aware; the work is UI (self-view PiP, camera flip, orientation) and encoder/decoder lifecycle. Sequence after voice calling is proven reliable.

## 5. Explicitly out of scope (per current instructions)

- iOS / Multipeer Connectivity.
- Web / PWA gateway (consistent with the earlier APK-only distribution decision).
- QR-code pairing — unnecessary anyway; calls only target peers already discovered via the existing mesh, same as private chat today.
- Wi-Fi Direct — not building a second P2P negotiation system alongside the one that already exists and works.
- WebRTC/ICE/STUN/TURN.

## 6. Risks to keep in view

- **Device/OEM variance**: Wi-Fi Aware hardware support isn't universal. `WifiAwareSupport` already detects and degrades gracefully (falls back to BLE-only text mesh) — calling will just be unavailable where Aware is, same as the text relay already is today. **Test on real GCTU-representative Android phones, not just emulators** — most emulator images have no Wi-Fi Aware hardware at all.
- **Range**: calling only works within direct Wi-Fi Aware range (~30–100m outdoors, less indoors). This is a correct, honest scope, not a bug.
- **Battery**: continuous mic capture + foreground service for the call's duration is expected and normal for a calling feature; no special mitigation needed beyond tearing everything down promptly on hangup (already the plan).

## 7. Suggested order of work

1. Phase 0 (plumbing) — low risk, no user-facing change, easy to verify in isolation.
2. Phase 1 MVP — get one real 1:1 call working end-to-end between two physical Android devices before touching polish.
3. Phase 2 — once Phase 1 is confirmed working reliably on real hardware.
4. Phase 3/4 — revisit only after Phase 1/2 are solid.
