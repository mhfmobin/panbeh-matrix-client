# Panbeh Flutter port: conventions for agents

We're porting "Panbeh", a Persian (RTL-only) Matrix chat client, from React + matrix-js-sdk (`/home/mhfmobin/Projects/panbe-matrix-client/src/`) to Flutter (`/home/mhfmobin/Projects/panbe-matrix-client/flutter/`, Dart package `panbeh`). The TS source is the feature spec: port its behaviour faithfully, including its Persian strings verbatim, and keep the TS comments that explain *why*. The UX must feel like **Telegram for Android** (gestures, layout, animations), using Panbeh's colours.

## Environment
- `export PATH=~/flutter/bin:$PATH` (Flutter 3.47 / Dart 3.13). There is no Android SDK locally: verify with `cd flutter && flutter analyze && flutter test`, and both must be clean before you finish. Don't run `flutter build`.
- Only edit files you're assigned (other agents work in parallel on other files). Don't touch anything outside `flutter/`. Don't commit, don't add packages without saying why in your report (prefer what's in pubspec.yaml).
- Matrix SDK: Famedly `matrix` 14.0.0. Source to read when unsure: `~/.pub-cache/hosted/pub.dev/matrix-14.0.0/lib/` (src/room.dart, src/timeline.dart, src/event.dart, src/client.dart). Don't guess APIs — grep them.
- Watch out: write bidi/zero-width characters as escapes in Dart strings (`'⁨'`, `'‌'`), not literal characters (the analyzer flags them).

## Existing code to use (read these first)
- `lib/main.dart`: App, `Root`/`RootState` (`context.findAncestorStateOfType<RootState>()` → `restart()`, `addAccount()`), `Splash`, `Logo`, `navigator` key.
- `lib/matrix.dart`: global `client` (the running `Client`), `logout()`, `otherAccounts()`, `switchAccount(name)`.
- `lib/theme.dart`: `context.tk` → `Tokens` (accent, bg, panel, hover, text, muted, border, bubbleIn, bubbleOut, outText, outMeta, pill, chatBg, readTick), `mix()`, `Wallpaper` widget (chat background).
- `lib/prefs.dart`: global `prefs` (ChangeNotifier): `prefs.get<T>(key)`, `prefs.set({...})`, `theme`, `accent`, `wallpaper`, `enterSends`, consts `accents`, `themes`, `wallpapers`.
- `lib/logic.dart`: 1:1 port of src/logic.ts (faNum instead of num, TimelineRow instead of Row, baseFolders, joinRules, history, buildRows, dayLabel, listTime, clock, stamp, lastSeen, inFolder, inArchive, isUnread, applyFolderOrder, moveFolder, spaceRooms, byListOrder (items implement `ListOrdered`), normalize, textDir, formatMessage, extractLinks, linkSrc, …). Read it.
- `lib/uri.dart`: parseMatrixLink / parseMatrixHash → `Target`.
- `lib/ui/common.dart`: `Avatar`, `RoomAvatar`, `roomTitle(room)` (USE this for room names, never room.getLocalizedDisplayname), `senderName(ev)`, `me()`, `isGroupChat`, `loadMxc`, `toast`, `copyText`, `confirm`, `alert`, `attempt`, `errText`, `bdi`, `previewText(ev)`, `noticeText(ev)`, `stripReplyFallback`, `formatSize`, `colorFor`, `Section`.

## Style
- Plain StatefulWidgets + `StreamBuilder`/listeners on SDK streams (`client.onSync.stream`, `room.onUpdate.stream`, `client.onTimelineEvent` …). No state-management packages. Navigator 1.0 (`Navigator.push(MaterialPageRoute(...))`).
- Terse, like the existing files: few comments, no boilerplate, no speculative abstractions. One file per screen as assigned. Shared helpers go in the assigned file; if something truly belongs in common.dart, list it in your report instead of editing common.dart.
- RTL is automatic (locale fa). Use `EdgeInsetsDirectional`/`AlignmentDirectional`. Message bubbles: own messages on the RIGHT, others on the LEFT, as in the web app (wrap the timeline row in `Directionality(textDirection: TextDirection.ltr)` and give the text inside its own `textDir()` direction).
- Persian digits everywhere numbers are shown (`faNum`, `faDigits`).

## Report back
A short list of what you built, anything from the TS you skipped (and why), SDK gaps you hit, and anything you need from other files.

## Working alongside other agents
Work on the current phase branch (see ROADMAP.md) and don't commit; the lead commits. Read chat.dart, message.dart and composer.dart before you start.
Several agents work at the same time and some files are SHARED (`message.dart`, `composer.dart`, `chat.dart`, `chat_list.dart`, `pubspec.yaml`, `AndroidManifest.xml`, `MainActivity.kt`):
- Put your code in your OWN new file(s). In shared files make only small hook edits (an import, a call to your widget in a switch/branch, a button), using the Edit tool with small, unique old_string anchors. Never rewrite a shared file with Write. Re-read the region right before editing. If an Edit fails because the file changed, re-read and retry.
- Add packages with `flutter pub add <pkg>` (it merges safely), never by hand-editing pubspec.yaml.
- At the end, `flutter analyze` must be clean for the WHOLE project. If another agent's in-progress file breaks it, wait a minute and retry. Don't fix their code; mention it in your report.
