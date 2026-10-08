# Flutter rewrite: roadmap and handoff

This file is the source of truth for resuming the rewrite. Telling Claude Code "build and go on" means picking up the first unfinished phase below.

## Ground rules (decided with the owner)
- **Scope:**
  - Everything lives in `flutter/`, which is meant to become its own repo.
  - CI is `.github/workflows/flutter-android.yml`.
  - The Capacitor app (`../src`, `../android`) and its workflows are not touched.
- **Spec:** the React app in `../src` defines the behaviour and the Persian wording. Port it faithfully.
- **UX and look:**
  - The UX copies Telegram Android.
  - Colours are Panbeh's own (`lib/theme.dart` mirrors `styles.css`).
  - The app is Persian only, RTL, with Persian digits and Jalali dates.
- **Stack:**
  - Famedly `matrix` SDK plus `flutter_vodozemac`, which is AGPL-3.0; the owner accepted that, see `LICENSE`.
  - Plain `StatefulWidget` and streams, no state-management packages.
  - Navigator 1.0.
- **Package id:** `ir.panbeh.flutter` for now, so it installs side by side with the old app. See README for switching to `ir.panbeh.app`.
- **Quality settings:** add a Settings picker that defaults to the best quality rather than a hardcoded value.
- **Calls:** follow `../CALLS.md`:
  - MatrixRTC with legacy `m.call.member` memberships, `userId:deviceId` LiveKit identities, and `/sfu/get`.
  - Nothing Synapse-only; Conduit, Tuwunel and Continuwuity must work.
- **Push:** no FCM or UnifiedPush. Background sync is a foreground service.
- **Build and verify:**
  - Flutter is installed at `~/flutter/bin`.
  - There is no local Android SDK. Run `flutter analyze` and `flutter test` locally; CI compiles Kotlin and builds the APKs.
- **Delivery:** each phase is a branch `flutter/phase-N` (stacked on the previous one), pushed so CI builds an APK.

## How the work is done
- Shared conventions for agents are in [AGENTS.md](AGENTS.md).
- Each phase is split across a few parallel agents. Each agent owns new files and makes only small hook edits in shared files (`message.dart`, `composer.dart`, `chat.dart`, `chat_list.dart`, `pubspec.yaml`, `AndroidManifest.xml`, `MainActivity.kt`).
- The lead reviews, runs `flutter analyze` and `flutter test`, commits, pushes, and checks CI.

## Status

| Phase | Branch | State |
|---|---|---|
| 1. Foundation: login (password + OAuth/MAS), accounts, theme, chat list, text chat, composer | `flutter/phase-1` | ✅ CI green |
| 2. E2EE: recovery unlock/setup, SAS emoji verification, Element key-file import/export | `flutter/phase-2` | ✅ pushed |
| 3. Rich messages: media, voice, emoji/GIF panel, link previews, reactions, polls, forward, select, pins, seen-by, threads, search, deep links, share target | `flutter/phase-3` | ✅ pushed |
| 4. Rooms & account | — | next |
| 5. Background sync & notifications | — | todo |
| 6. Calls | — | todo |

Nothing has been tested on a device yet. The owner installs each phase's APK and reports problems. Fix those first when resuming.

## Phase 4: rooms and account
Branch `flutter/phase-4` from `flutter/phase-3`. Spec: the inventory sections on room info, new chat, profile and settings, in these sources:
- `src/ui/RoomInfo.tsx`
- `src/ui/Admin.tsx`
- `src/ui/NewChat.tsx`
- `src/ui/Profile.tsx`
- `src/ui/Settings.tsx`
- the `matrix.ts` helpers: `createChat`, `openDM`, `allowCalls`, `setMyAvatar`, `setRoomAvatar`, `addToSpace`, `removeFromSpace`, `withPassword`, `deleteDevices`, `deviceManageUrl`, `accountManageUrl`, `setBlocked`

What to build:
- **Room info:** a Telegram collapsing profile page with the avatar (view and change), name/topic editing, the notification switch, and shared-media tabs (photos and videos, files, links, audio; paged `/messages`). Space children management, the member list with search and role labels, invites, knock requests, the banned list, and leave.
- **Group settings:** who can send, join rule (knock only on room version ≥ 7), history visibility, enable E2EE (one way), and a public address plus directory listing.
- **Moderation:** role change, kick, ban and unban with a reason, but only on users you outrank. Room v12 creators count as infinite power.
- **New chat:** the FAB sheet's four actions. New DM (directory search plus a typed full mxid lookup, reusing an existing DM). New group or space (photo, name, topic, public, E2EE, parent space, invite chips, and the `allowCalls` power fix). Join (directory search, `#alias` or `!id`, and a knock fallback on `M_FORBIDDEN`).
- **Profile sheet:** message, voice and video call (calls are stubs until phase 6), verify (`verifyUser` in `lib/ui/verify.dart`), block/unblock, and admin tools.
- **Settings (full):** fill out `lib/ui/settings.dart`.
  - Every pref in `lib/prefs.dart`, plus `camQuality` and `audioQuality` pickers.
  - Server push rules: the `@room` toggle and keyword chips.
  - Devices: list, verify (`verifyDevice`), sign out, and sign out all others, with UIA.
  - Blocked users, change password, and deactivate account. OAuth accounts open the account-management URL instead.
  - Developer options.
- **Wire the remaining «به‌زودی» toasts** (search `به‌زودی` in `lib/`).

## Phase 5: background sync and notifications
Branch `flutter/phase-5`. Spec:
- `../ANDROID.md`
- `../android/app/src/main/java/ir/panbeh/app/{SyncService,Notifier,BootReceiver,CallReceiver,PanbehPlugin}.java`
- `src/notify.ts`
- `src/native.ts`

What to build:
- **One engine.** `App.kt` (an `Application`) keeps one `FlutterEngine` in `FlutterEngineCache`. `MainActivity` overrides `provideFlutterEngine`/`shouldDestroyEngineWithHost=false`, and `SyncService` keeps the process and that engine alive. There is only ever one Dart client.
- **SyncService in Kotlin**, ported without the WebView parts:
  - Foreground type `remoteMessaging` and the status texts.
  - Real-time mode with a partial wake lock, or interval mode using `AlarmManager.setAndAllowWhileIdle` (1/2/5/10/15/30/60 min).
  - Connectivity callback, a 60 s kick to Dart, and boot restart.
- **Notifier and channels**, ported line for line: `messages`, `messages_quiet`, `service`, `calls`; one notification per room with the avatar; group summary; cancel on read.
- **Dart side:** `lib/native.dart` (the MethodChannel) and `lib/notify.ts` → `lib/notify.dart`.
  - Notifications are gated by push rules (`client.pushruleEvaluator`) and the DM/group switches; highlights always notify.
  - No notification for the open, visible chat.
  - De-dupe, and clear when our own receipt arrives.
- **Sync health:** a hung `/sync` older than 50 s is abandoned on a kick.
- **Battery:** battery-exemption and full-screen-intent buttons in Settings → اعلان‌ها.
- **Presence:** "away" (unavailable) while backgrounded; `shareLastSeen` off means offline.
- **Permissions:** request `POST_NOTIFICATIONS` on first start.

## Phase 6: calls
Branch `flutter/phase-6`. Spec:
- `src/call.ts`
- `src/ui/Call.tsx`
- `../CALLS.md`
- `isRing`, `rtcOutcome` and `legacyOutcome` in `lib/logic.dart`

What to build:
- **Check the SDK first.** See whether the Famedly SDK's `VoIP` + `GroupCallSession` + `LiveKitBackend` interoperate with Element Call and the existing `lk-jwt-service /sfu/get` (key exchange `io.element.call.encryption_keys`). If they don't, port `call.ts`'s membership handling to Dart.
- **Transport:** discovery via `/rtc/transports`, then well-known `org.matrix.msc4143.rtc_foci`. The token is fetched with an OpenID token.
- **Media:** `livekit_client` with E2EE in encrypted rooms. Quality comes from prefs.
- **Ringing:** ring via `m.rtc.notification` (ring in DMs, notification in groups), decline via `m.rtc.decline`, and a 90 s timeout. Show the CallStyle notification with a full-screen intent, from the phase-5 Notifier.
- **Call screen:** grid with up to 9 tiles, 1:1 PiP, mute, camera, flip, speaker/audio routes, raise hand, reactions and the people list. Minimizing gives a call bar; system PiP; proximity sensor.
- **Permissions:** the `allowCalls` prompt.
- **Legacy calls:** `m.call.*` behind developer options.
- **Timeline:** call lines in the timeline (`noticeText` in `lib/ui/common.dart` has a ponytail note).

## Known gaps to revisit
Grep for `ponytail:` in `lib/` to find them. The main ones:
- The timeline has no scroll anchoring when new messages arrive while scrolled up.
- The chat-list preview uses only the SDK's `room.lastEvent`.
- Uploads show a stage label but no percentage (the SDK has no upload progress).
- The pending OAuth login is kept in memory only.
- Key-file PBKDF2 is pure Dart in an isolate (about 5 s on a desktop).
- Threads:
  - `ThreadPage` builds its own `MsgActions` (reply, edit and jump only). Reactions, select, forward, pin and seen-by aren't wired inside a thread yet.
  - GIFs sent from the emoji panel inside a thread go to the main timeline, because `EmojiPanel`/`sendGif` take no `threadId`.
- Uploads: the SDK can't abort a running upload, so ✕ on an in-flight upload redacts the message once it exists. Queued files get no bubble until their turn.
- Share target:
  - Cancelling the «ارسال به…» picker drops the share.
  - Copies in `cacheDir/share` are never cleaned.
- Links: a link to the chat you're already in pushes a second `ChatPage`.
- Mentions: a mention pill opens the DM prompt, since there is no profile screen until phase 4.
- Voice: NowPlaying in the chat list opens the chat but doesn't jump to the message (use `ChatPage(eventId:)`).
