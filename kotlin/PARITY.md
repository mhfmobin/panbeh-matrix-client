# Kotlin rewrite: feature-parity checklist

The Kotlin app lives in `kotlin/` and is **Android only**. App id: `ir.panbeh.app.kotlin`. OAuth redirect: `ir.panbeh.app.kotlin:/oauth` with `client_uri` `https://kotlin.app.panbeh.ir/`. The UI stays Persian (RTL, `fa-IR` digits and Jalali dates); this file is in English. Every exact string below (event types, MSC numbers, account-data keys, pref keys/defaults, thresholds) is the spec: keep it byte-for-byte so the Kotlin app interoperates with the TypeScript app on the same account. Items marked "verify" were not confirmable from the TS source (constants lived in matrix-js-sdk).

Cross-cutting: UI strings Persian only; digits/dates via `fa-IR` (Persian digits, Jalali; `امروز`/`دیروز`); bidi isolation with U+2068/2069; text direction chosen by the first letter (`textDir`); search folding (`normalize`): ي→ی, ك→ک, ZWNJ removed, lowercase; font Vazirmatn.

## Auth / Accounts
- [ ] Multi-account: several sessions `[{baseUrl, userId, deviceId, accessToken, refreshToken?, oauthClientId?}]`, one active; add account, switch account (one client runs at a time)
- [ ] Per-account storage isolation (sync DB, crypto store, search index, drafts, profile cache, folder keys)
- [ ] Server field with `https://` added when scheme missing (`normalizeServer`); last server remembered; check after 700 ms debounce
- [ ] `loginMode()`: "oauth" if auth metadata is available, else password (via `/versions`)
- [ ] Password login: `m.login.password` with identifier `m.id.user`; device name "Panbeh Android"; `M_FORBIDDEN` at login shown as bad credentials
- [ ] OAuth 2.0 / MAS: dynamic client registration once per issuer+redirect (cache per issuer/redirect), authorization-code grant with PKCE, response mode `query`, pending state persisted across process death, then `whoami`
- [ ] OAuth redirect `ir.panbeh.app.kotlin:/oauth`, `client_uri` `https://kotlin.app.panbeh.ir/`
- [ ] Refresh-token rotation re-saves the session; error text `ورود ناتمام ماند` when login didn't finish
- [ ] OAuth account-management deep links: `org.matrix.device_delete`, `org.matrix.session_end`, `org.matrix.sessions_list`, `org.matrix.devices_list`, `org.matrix.profile`, `org.matrix.account_deactivate`
- [ ] Logout: remove account + its drafts/profile/search data, revoke token, clear stores (3 s cap), clear crypto stores, clear media cache, cancel native notifications, stop background service if last account
- [ ] Forced logout when the session is invalidated (`SessionLoggedOut` / `M_UNKNOWN_TOKEN`)
- [ ] `logoutAll()` for "forgot app-lock PIN"
- [ ] UIA helper `withPassword`: try without auth, on 401 with `flows` retry with `m.login.password` + session, else ask for the password
- [ ] Delete one/multiple devices (UIA)
- [ ] Presence privacy pref `shareLastSeen`: off sets presence offline; backgrounded/headless sync is `unavailable`
- [ ] App phases: boot / login / ready / error (error screen: retry + logout); cancel button while adding an account
- [ ] "Connecting..." banner on sync Error/Reconnecting; crypto banner (unlock vs setup)

## Sync / Storage
- [ ] Persistent sync store, lazy-loaded members, initial sync limit 30, thread support
- [ ] Network kick: `kick(newNetwork)`: retry immediately; otherwise abort incremental `/sync` requests older than 50 s (`STALL_MS`), or all of them if the network changed
- [ ] Headless/background sync filter: presence `not_types:["*"]`, ephemeral `not_types:["m.typing"]`, unread thread notifications on
- [ ] `firstSyncDone` gate: no notifications before the first sync completes
- [ ] History paging `/messages` with filter `types:[m.room.message, m.room.encrypted]`; drop the filter after one refusal (Conduit workaround)
- [ ] `loadEvent()` fetch + decrypt a single event, cached
- [ ] Media cache: persistent, encrypted attachments stored still encrypted, FIFO eviction capped by `cacheMB` (default 1024; options 250 / 500 / 1024 / 2048 / 5120), files over 100 MB never cached; cache size / clear / trim
- [ ] Local search index (per account): decrypted `m.room.message` events, edits applied (author only), redactions applied, writes batched once per second, text = body minus reply fallback plus filename; backfill by paging history; normalized linear substring scan
- [ ] Pref `searchIndex` default true (off drops the index)
- [ ] Presence lookup cached 60 s, combined with the live sync flag
- [ ] Room tags `m.favourite` (pinned) and `u.archived`
- [ ] Marked unread (MSC2867): room account data `m.marked_unread`; clear legacy `com.famedly.marked_unread` too; `markRead` sends a read receipt on the newest event then clears the mark
- [ ] `leaveAndForget`: leave, wait for membership change, forget, persist store; on `M_FORBIDDEN` leave, forget and wipe the store
- [ ] Dev net log: per-endpoint request/byte counts every 60 s to logcat tag `PanbehSync`

## Room list
- [ ] Visible rooms: joined + invited, replaced (tombstoned) rooms hidden
- [ ] Folder tabs: `همه` (all), `خوانده‌نشده` (unread), `شخصی` (DMs), `گروه‌ها` (groups), plus one per top-level joined space; per-folder unread badge
- [ ] Space folders: `spaceRooms` follows sub-spaces cycle-safely; children from `m.space.child` events with `via`; space invites listed like chats
- [ ] Folder order drag-sortable (first tab fixed); synced in account data `app.panbeh.folder_order` `{order}`; last folder remembered per user
- [ ] Swipe between folders
- [ ] Archive pseudo-folder; an unmuted archived chat with unread messages reappears in the main list (`inArchive`)
- [ ] List order (`byListOrder`): invites, then pinned, then newest
- [ ] Row: avatar (DM falls back to peer photo, groups never borrow a member's), online dot for DMs, muted icon, Jalali time, preview with sender prefix (`شما` for me), call lines, typing indicator
- [ ] Badges: unread count, `@` for mentions, `!` for invites, dot for marked unread, pin icon
- [ ] Long-press menu: pin, archive, mark read/unread, mute/unmute, delete & leave
- [ ] Search: name filter; with 2 or more characters also global message results (server search + loaded encrypted rooms + local index); Enter opens the first hit
- [ ] Quick switcher to any chat, archived included
- [ ] Skeleton loading rows
- [ ] Calls tab (see Calls)
- [ ] New chat FAB menu: new DM (user directory search + direct profile lookup for a full `@user:server`), new group, new space, join
- [ ] New group/space form: name, topic, avatar, public toggle with alias suggestion (`aliasLocalpart`; public rooms unencrypted by default), E2EE toggle (groups only), parent space picker, "space members join without invite" (join rule `restricted`, only when room version supports it per capabilities), invitees
- [ ] Join: public directory search or join by `#alias` / `!id`; on `M_FORBIDDEN` offer to knock (`joinOrKnock`)
- [ ] `openDM`: reuse an existing joined DM, else create encrypted DM (preset `trusted_private_chat`, `is_direct`) and update `m.direct`
- [ ] `createChat` presets `private_chat` / `public_chat`; spaces get `creation_content.type = m.space`
- [ ] `allowCalls`: set power level for `org.matrix.msc3401.call.member` to 0
- [ ] Space links maintained: `m.space.child` `{via}` and `m.space.parent` `{via, canonical}`
- [ ] `isDirect` / `dmPeer` / `findDM` based on `m.direct` and DM inviter
- [ ] Invite screen: accept/decline; accepting a DM invite updates `m.direct`

## Timeline / Messages
- [ ] Rows (`buildRows`): day separators, same-sender bursts (5 min, same day) with first/last flags, albums, unread divider `پیام‌های خوانده‌نشده`, state-event notices
- [ ] Unread divider placed once on open from `m.fully_read`, the read receipt, or my own last event; it splits bursts and albums
- [ ] Album grouping: tag `app.panbeh.album {id,n,i}`, or heuristic: uncaptioned photos/videos from the same sender within 10 s; max 10 per album
- [ ] Open at the read point; if older than loaded, open a history window around it (`/context`)
- [ ] Jumps to replies, pins, search results and media (window that extends both ways and joins the live timeline)
- [ ] Older pages held back until decrypted so the view doesn't jump
- [ ] Floating date pill; jump-to-bottom button with unread badge; follow-output rules; entry animation for new rows
- [ ] Receipts: read receipt + `m.fully_read` markers sent only while "attentive" (visible, focused, unlocked, input within last 60 s)
- [ ] Pending uploads as bubbles with progress ring, cancel, retry
- [ ] Bubble: sender name coloured by hash of the user id (`colorFor`), forwarded-from line (`app.panbeh.forwarded {sender,name}`), reply quote (fetched if not loaded, tap jumps), edited tag, pinned icon, time
- [ ] Ticks: clock while sending, single tick sent, double tick read (from receipts, `seenBy`); in DMs the title shows when it was seen
- [ ] Mention highlight from push rules (`tweaks.highlight`); failed sends: resend / cancel
- [ ] Plain text linkified (`LINK_SRC`: http(s), `www.`, bare domains with known TLDs, `matrix:`)
- [ ] HTML (`org.matrix.custom.html`) sanitized; `<img>` allowed only for custom emoji; matrix.to links become user/room pills with avatars; other matrix.to / `matrix:` links open in-app; spoilers (`data-mx-spoiler`) reveal on tap; code blocks have a copy button
- [ ] Jumbo emoji for messages of 1-3 emoji
- [ ] `m.emote`; `m.image` and `m.sticker` (blurhash placeholder, `thumbnail_file`, or server thumbnail 640)
- [ ] GIFs: video with `fi.mau.gif`, or image with `image/gif`
- [ ] `m.video`: poster frame, play badge, duration
- [ ] Round video note (`app.panbeh.video_note`): muted loop; tap plays with sound and progress ring
- [ ] `m.audio` voice player; `m.file` file row
- [ ] `m.location`: `geo:` URI parsed (`m.location` / `org.matrix.msc3488.location` or `geo_uri`) with "OpenStreetMap" and "Google Maps" links
- [ ] Polls rendering (see Composer); decryption-failure and "decrypting..." placeholders (failure points to Settings)
- [ ] Auto-download per kind (photos, videos, files, gifs, voice), split DM/group and metered/Wi-Fi with size caps (`shouldAutoDl`); not-yet-downloaded media shows blurhash + size + download button with progress and cancel
- [ ] Quick reactions 👍 ❤️ 😂 😮 😢 🔥 plus the full picker; reply; forward; copy link; thread; seen-by; pin/unpin (if allowed); edit (own messages, editable msgtypes); delete
- [ ] Long-press menu (450 ms): quick reactions, reply, copy, copy link (matrix.to with event + via), forward, reply in thread, add sticker to my pack, save/remove GIF, pin, edit, seen-by, reactions list, multi-select, report, delete
- [ ] Swipe left to reply with haptics
- [ ] Report: `reportEvent` score -100 with optional reason
- [ ] Reactions: annotation chips with counts; toggling mine redacts; custom emoji reaction key is the mxc plus `com.beeper.reaction.shortcode`; long-press chip copies the emoji to my pack; in threads the reaction goes into the thread
- [ ] Delete = redact (all items of an album), confirm dialog, shrink animation
- [ ] Copy text strips the reply fallback; media copies only the caption
- [ ] Room header: avatar, name; subtitle shows typing names (up to 2 + "N others"), presence/last seen for DMs, else member count; call buttons; search
- [ ] Multi-select bar: copy (formatted `sender, [stamp]:\ntext`), forward, delete
- [ ] Threads: thread panel with its own composer; threads created on demand
- [ ] Load full member list on open (`loadMembersIfNeeded`)
- [ ] Room info sheet: photo view/change/remove; name/topic edit (only changed fields); canonical alias
- [ ] Mute row: on / 1 h / 8 h / 1 day / 1 week / forever / until a date
- [ ] Shared media (tabs media / files / links / audio via `mediaKind`, `contentLinks`; 10 automatic history pages then a "search older" button; jump to message)
- [ ] Room link + QR (`matrixToLink` with `viaServers`, top 3 servers by member count), hint per join rule
- [ ] Room emoji packs; group settings when `canManage`
- [ ] Space contents: joined children; non-joined via `getRoomHierarchy` (name, count, "suggested"); join; add existing room; new group in this space; remove from space
- [ ] Knock requests; member list with search, role labels, last seen; invite member; banned list; technical details; leave
- [ ] SeenBy (receipts with timestamps) and ReactedBy (tabs per emoji)
- [ ] Pinned bar (`m.room.pinned_events`): tap cycles older pins and jumps; list sheet allows unpin
- [ ] Forward: multi-room (rooms in parallel, messages in order), polls excluded; `forwardContent` strips relations, `m.new_content` and reply fallback, sets `m.mentions: {}`, keeps author via `app.panbeh.forwarded`
- [ ] Link preview: first web link in `m.text` / `m.notice` / `m.emote` (not matrix.to, not in code) via `getUrlPreview` (`og:title`, `og:description`, `og:image`, `og:site_name`); gated by `previews` pref
- [ ] In-room search: unencrypted = server search (`order_by` recent, room filter; empty filter for Conduit) merged with loaded events; encrypted = local index + loaded events with "search older" (`backfill`/`searchOlder`); highlighted match (`matchRange`)
- [ ] Notice texts for: create, name, topic, avatar, canonical alias, server ACL, guest access, encryption, pins; membership join/invite/knock/leave (withdraw, decline, kick, unban, reject knock, revoke invite)/ban with reason; power-level changes (`levelChanges`/`roleLabel`), join rules, history visibility, tombstone; call lines; in-room verification request (`m.key.verification.request`); profile-only member changes hidden
- [ ] `previewText`: emoji-prefixed one-line previews for lists and replies
- [ ] Tombstoned room: composer becomes "go to new group" (join via upgrader's server), notice if I can't post; link back to predecessor room

## Composer
- [ ] Drafts per room+thread, per user; shared text is appended to the draft; shared files open the send dialog
- [ ] Reply: `m.relates_to.m.in_reply_to`, replied-to sender added to `m.mentions`
- [ ] Edit: `m.replace` + `m.new_content` + `* ` body; only newly added mentions are pinged; media caption edit replaces whole content keeping the file
- [ ] Mentions: `@` autocomplete over joined members plus `@room` when allowed (`mayTriggerNotifOfType`); typed `@Name` / `@user:server` detected; sent as matrix.to links with `m.mentions {user_ids, room}`
- [ ] Custom emoji `:code:` autocomplete; sent as `<img data-mx-emoticon src=mxc>`
- [ ] Markdown subset (`formatMessage`): fenced and inline code, `[label](url)`, `> ` quotes, `**bold**`, `~~strike~~`, `||spoiler||`, `*em*` / `_em_` (word-boundary safe); no HTML when nothing was formatted
- [ ] Typing notifications: `sendTyping` timeout 5 s, throttled to once per 3 s
- [ ] Send key pref `enterSends` (default true)
- [ ] Attach menu: file/photo, poll, location
- [ ] Location: `m.location` with `geo:lat,lon;u=acc`, asset type self, body contains an OSM link
- [ ] Send-files dialog: thumbnails, caption (MSC2530: body = caption, `filename` = name), "send as file" (no compression), "send as GIF" (video gets `fi.mau.gif`, `fi.mau.loop`, `fi.mau.autoplay`, `fi.mau.hide_controls`, `fi.mau.no_audio`)
- [ ] Albums up to 10 tagged `app.panbeh.album` (reply on the first item, caption on the last)
- [ ] Upload queue: sequential, progress, abort, retry
- [ ] Image recompression to 1280 px JPEG quality 0.82 (only if smaller; not gif/svg); `fitSize` never upscales
- [ ] Image info w/h + blurhash `xyz.amorgan.blurhash` (MSC2448); video info w/h/duration plus 800 px poster JPEG as `thumbnail_file` (encrypted) or `thumbnail_url`
- [ ] Encrypted rooms: encrypt attachment and send `file`; otherwise send `url`
- [ ] Successfully sent GIF auto-saved to saved GIFs
- [ ] Voice recording: hold mic, slide up 60 px to lock, slide sideways 100 px to cancel, under 700 ms is a slip; tap toggles voice/video (pref `recordMode`, default voice)
- [ ] Voice content: Opus at `audioQuality` bitrate (24/48/96 kbps), `m.audio` with `info.duration`, `org.matrix.msc1767.audio {duration, waveform}` (0..1024, 40 bars via `downsample`), `org.matrix.msc3245.voice {}`; locked mode has a preview player
- [ ] Video note: front camera, centre-square crop at 384/480/640 px (pref `noteQuality`, default 640), 1-2.5 Mbps, max 60 s, H.264 mp4 preferred; flip camera mid-recording; content `app.panbeh.video_note: true`
- [ ] Shared audio player: one player app-wide (survives scrolling/chat switch), speeds 1, 1.5, 2, 0.5 (persisted `panbeh.voiceSpeed`), seekable waveform, auto-plays next `m.audio`, "NowPlaying" bar jumps to message
- [ ] Polls (MSC3381) with unstable types `org.matrix.msc3381.poll.start` / `.response` / `.end`; disclosed/undisclosed, single/multi choice, 2-20 answers; edit only before any vote (`m.replace`); optimistic votes; empty selection = spoiled vote retracting; `tallyPoll` (latest vote wins, unknown answers spoil, extras past max truncated); percentages and winner highlight; ended by author or anyone allowed to redact; end fallback text "The poll has ended"
- [ ] Scheduled messages (MSC4140 delayed events): feature check for `org.matrix.msc4140`; Jalali day picker (31 days) + hour + 5-minute step; main timeline only; encrypted rooms encrypt locally at schedule time and keep plaintext in a local per-user store keyed by delay_id
- [ ] Scheduled list reads `scheduled` or `delayed_events`; filter non-state `m.room.message` / `m.room.encrypted` / `org.matrix.msc3381.poll.start`; actions send now, cancel, edit (reschedule then cancel old)
- [ ] Delay-too-large errors (`M_MAX_DELAY_EXCEEDED`, `ORG.MATRIX.MSC4140_DELAY_TOO_LARGE`, `org.matrix.msc4140.max_delay`) shown in Persian
- [ ] Emoji panel: emojibase compact + CLDR `fa` annotations (Persian names/search), categories, recents (`panbeh.recentEmoji`, 24), no skin tones, custom pack tabs, sticker tab, saved-GIF tab
- [ ] Stickers sent as `m.sticker {body, url, info}`
- [ ] Saved GIFs: account data `app.panbeh.gifs {gifs:[...]}`, max 100, newest first, deduplicated by url
- [ ] Custom emoji/stickers (MSC2545 `im.ponies.*`): personal pack `im.ponies.user_emotes`; room packs state `im.ponies.room_emotes` keyed by stateKey; globally enabled packs `im.ponies.emote_rooms {rooms:{roomId:{stateKey:{}}}}`; usage resolves image -> pack -> both; caps 200 emoji / 120 stickers
- [ ] Pack upload: stills padded to at most 2:1, shrunk to 128 px (emoji) / 512 px (stickers), saved WebP; animated GIF/APNG/WebP passed through if within 2:1 and under 1 MB (emoji) / 2 MB (stickers); uploaded unencrypted even for encrypted rooms
- [ ] Shortcodes: Latin or Persian letters, digits, `_`, `-`, ZWNJ, max 32 chars; deduplicated `code_2`, `code_3`...
- [ ] Pack management: add/rename/delete, take others' emoji into mine, room packs need state-send permission, global switch per room pack

## Media
- [ ] `mediaUrl`: authenticated media (token refresh on OAuth), plain fetch when byte progress is needed with 401 fallback, encrypted attachments decrypted on use, in-session blob cache plus persistent cache, `prefetch`, `isCached`
- [ ] Gallery viewer over images/videos in the loaded timeline: zoom (pinch, double-tap, pan), swipe to navigate (RTL order), download, "show in chat", caption, opening animation from thumbnail
- [ ] Custom video player: seek, speed, mute, fullscreen, auto-hiding controls
- [ ] Avatar photo viewer with save
- [ ] Save file: MediaStore Downloads, or "Save as" (SAF) picker when `askSave` is on; remember saved location (`panbeh.saved` mxc -> content URI) so a second tap opens the saved copy; missing file re-downloads; "no app" error
- [ ] `formatSize` with Persian units
- [ ] Maps: OSM link and Google Maps link for locations

## Crypto
- [ ] `recoveryState()`: ok / unlock / setup (cross-signing ready + backup key, or default secret-storage key exists)
- [ ] Unlock with a recovery key or passphrase, then check key, bootstrap cross-signing, load backup key from secret storage, enable key backup, restore backup in the background
- [ ] `setupRecovery(password?)`: new cross-signing, secret storage and key backup; recovery key shown once with copy button
- [ ] OAuth accounts: cross-signing reset via MSC4312 approval (`m.oauth` or `org.matrix.cross_signing_reset` URL in UIA params, opened in browser, then confirmed)
- [ ] Encryption card; "verify with another device" (own-user verification request)
- [ ] Device list: verified badge from cross-signing status, last seen ts/IP, verify another device, sign out one/all others (UIA password) or account-management page for OAuth
- [ ] Verification methods `m.sas.v1`, `m.qr_code.show.v1`, `m.qr_code.scan.v1`, `m.reciprocate.v1`; one verification at a time; incoming requests handled
- [ ] QR: show ours when the other side can scan; scan theirs (frame starts with "MATRIX"); reciprocate confirm
- [ ] SAS with Persian names for all 64 emoji (`EMOJI_FA`) or decimal fallback; auto-start SAS when neither side supports QR; Persian cancel reasons for `m.mismatched_sas`, `m.user`, `m.timeout`, `m.accepted`
- [ ] User trust badge (cross-signing); verify a user via `requestVerificationDM` in the DM (created if needed)
- [ ] Key export/import in Element's Megolm format (`ir.panbeh.core.keyfile`: armor `-----BEGIN MEGOLM SESSION DATA-----`, `0x01 | salt16 | iv16 (bit 63 cleared) | rounds u32 BE | AES-256-CTR | HMAC-SHA256`, PBKDF2-SHA-512 512 bits split AES/HMAC, 500000 rounds); default file name `element-keys.txt`; import shows progress
- [ ] Megolm session export JSON passes to/from the SDK (`exportRoomKeysAsJson` / `importRoomKeysAsJson` equivalents)

## Calls
- [ ] MatrixRTC + LiveKit for groups and DMs; SFU discovery (`ourTransport`): MSC4143 `/rtc/transports`, else `.well-known` `org.matrix.msc4143.rtc_foci` (LiveKit entry); join on the SFU of the oldest existing membership
- [ ] JWT: POST `{livekit_service_url}/sfu/get` with `{room, openid_token, device_id}`; identity `@user:server:DEVICE`
- [ ] Membership event `org.matrix.msc3401.call.member` with `memberId` `user:device`, `livekit_alias` = roomId, `callIntent` audio/video
- [ ] Ring: `notification_type` `ring` in DMs, `notification` in groups (MSC4075 `org.matrix.msc4075.rtc.notification`, verify), `m.call.intent`
- [ ] E2EE: per-participant keys from the membership session feed LiveKit key provider (ratchet window 0, keyring 256); enabled when the room is encrypted
- [ ] LiveKit `adaptiveStream`, `dynacast`; presets from prefs: camera 360/540/720/1080, screen share 720p15 / 1080p15 / 1080p30 (`screenQuality` default `1080hi`), audio bitrate low/normal/high
- [ ] DM: no answer within 90 s hangs up; the other side leaving ends the call
- [ ] Raised hand: `m.reaction` with 🖐️ on my membership event; lowered by redaction
- [ ] Call reactions: `io.element.call.reaction {m.relates_to (reference to membership), emoji, name}` with names thumbsup, clapping, party, laugh, heart, ok, lightbulb, thumbsdown, generic
- [ ] Low-data mode (`lowData`: off / auto / on): lowest video layer; auto on after 5 s of poor quality, off after 15 s fine; chip to turn off for the current call
- [ ] Legacy 1:1 `m.call.invite` / `m.call.answer` / `m.call.hangup` / `m.call.reject` / `m.call.select_answer` / `m.call.candidates`: receiving off by default (`legacyCalls`); while off an incoming legacy call produces a notice; outgoing used when no SFU or picked manually (dev options)
- [ ] Legacy: wait for TURN, inject ICE servers, cold-start from replayed invite and candidates, glare handling, 30 s "stuck" watchdog ICE report, reject = `m.call.reject` for v1 else `m.call.hangup`
- [ ] Ringing logic (`isRing`, `isGroupCallAlert`, `isLegacyRing`, `isVideoOffer`): no ring when muted, ignored, already in that call, or from me; group call start gives a quiet "started a group call" notice
- [ ] Call waiting: double beep every 3 s plus banner (answer ends the current call); missed-call notices
- [ ] Declines: `org.matrix.msc4310.rtc.decline` (verify) as a reference to the ring; a decline from another of my devices stops my ring
- [ ] Ringtone synthesized (no sound file) or system ringtone via the `calls` channel
- [ ] Call screen: grid up to 9 tiles ranked local > screen > speaking > hand, "+N" opens people panel; 1:1 remote full-screen with local PiP tile; spotlight mode
- [ ] Immersive mode auto-hides controls after 4 s (system bars hidden)
- [ ] Per-tile stats overlay (`parseStats`/`fmtStats`: resolution, fps, kbps, loss, rtt, codec)
- [ ] Controls: mic, camera, flip camera (cycle all cameras), audio route/speaker, hand, reactions + full emoji picker, people panel (name, hand, mic, quality), hang up
- [ ] Status: on hold (phone call), reconnecting, "X joined" / "X left", ringing, alone, duration
- [ ] Minimize to call bar; picture-in-picture; "a call is in progress, join" bar in a room
- [ ] Starting a call checks permission to send `org.matrix.msc3401.call.member` and offers `allowCalls` to admins
- [ ] Calls list: 1:1 calls in loaded timelines; all / missed tabs; direction, outcome, duration (`legacyOutcome`, `rtcOutcome`); call back
- [ ] Timeline call lines: "called . duration", missed, declined

## Notifications
- [ ] Triggers: live timeline events after first sync, not mine, not from ignored users; encrypted events wait for decryption
- [ ] Decision by server push rules (`notify`, `tweaks.sound`, `tweaks.highlight`) plus local prefs `notify`, `notifyDMs`, `notifyGroups`; mentions and keywords always get through; skipped if the chat is open and attentive
- [ ] `notifyPreview`: all / name only (text becomes `پیام تازه`) / none (title `پنبه`, no sender, no photo, no conversation shortcut)
- [ ] Clearing on my read receipt, on redaction (that one message is removed), on opening the chat
- [ ] Mute via room push rule (`setRoomMutePushRule`); timed mutes in room account data `ir.panbeh.mute_until {until}` checked every minute, whichever client runs unmutes
- [ ] Notification actions: Reply (sends `m.text` then marks read; failure becomes a notice), Mark read, Mute 8 h; wait up to 60 s for the room (cold start)
- [ ] Server push rules UI: @room toggle (`.m.rule.roomnotif` / `.m.rule.is_room_mention`); keyword content rules add/remove (actions notify + sound default + highlight)

## Settings & prefs
- [ ] Prefs stored as JSON `panbeh.prefs`; keys and defaults:
  - [ ] `theme`: system (system / light / dark)
  - [ ] `accent`: `#007ded` (options `#8774e1` `#40a7a0` `#e5864a` `#e0578b` `#4fae4e`; old `#3390ec` migrated)
  - [ ] `wallpaper`: doodle (doodle / gradient / plain)
  - [ ] `notify` true; `notifyDMs` true; `notifyGroups` true; `notifyPreview` all (all / name / none)
  - [ ] `previews` true (link previews)
  - [ ] `shareLastSeen` true
  - [ ] `searchIndex` true
  - [ ] `dev` false; `legacyCalls` false
  - [ ] `enterSends` true; `askSave` false
  - [ ] `camQuality` 720; `screenQuality` `1080hi`; `audioQuality` high; `lowData` off
  - [ ] `cacheMB` 1024
  - [ ] `autoDl` `{mobile, wifi} x {photos, videos(maxMB), files(maxMB), gifs, voice} x {dm, group}`; Wi-Fi defaults 50/50 MB, mobile 10/1 MB (`AUTO_DL_DEFAULT`, `mergeAutoDl`)
  - [ ] `recordMode` voice; `noteQuality` 640
- [ ] Other persisted keys: `panbeh.lock`, `panbeh.lockFails`, `panbeh.callDevices`, `panbeh.voiceSpeed`, `panbeh.recentEmoji`, `panbeh.saved`, `panbeh.autoUpdate`, `panbeh.lastServer`, `panbeh.profile:<user>`, `panbeh.drafts:<user>`, `panbeh.folder:<user>`, `panbeh.folderOrder:<user>`, `panbeh.scheduled:<user>`, `panbeh.oauthClient:*`, `panbeh.oauthPending`, `panbeh.searchIndexDefault`
- [ ] Settings main page: profile (display name, avatar change/remove), accounts list (switch, add), category rows, logout
- [ ] Chats & appearance: theme, accent, wallpaper (doodle pattern / four-blob gradient / plain), link previews, Enter sends, ask where to save, my emoji/sticker packs
- [ ] Notifications page: toggle (asks permission, starts service), DMs/groups/content, sound & vibration (opens system channel settings), background interval (real-time, 1 / 2 / 5 / 10 / 15 / 30 min, hourly), battery-optimization exemption, full-screen-intent permission (Android 14+), push rules
- [ ] Privacy & security: app lock, last-seen toggle, local search index toggle, blocked users (`m.ignored_user_list`), encryption, key export/import, devices
- [ ] Data & storage: auto-download page, media cache size & clear, camera / video note / audio quality, low-data mode
- [ ] Account: my link + QR, change password (`logout_devices=false`, UIA), deactivate (`erase=false`); OAuth accounts get the account-management URL instead
- [ ] App: updates, version (Persian digits), developer options (+ legacy calls toggle)
- [ ] Theme application (light/dark/system, accent, wallpaper, theme cross-fade) and status-bar icon style
- [ ] Self-update: check GitHub `releases/latest` of `mhfmobin/panbeh-matrix-client` for an `.apk` with newer version (numeric compare) every 6 h; auto-download unless metered; release builds only; download only from `https://github.com/mhfmobin/panbeh-matrix-client/releases/download/` into `update.apk` with progress; install; delete installed update APK after boot/upgrade (adapt for the Kotlin app's own repo/id)
- [ ] App lock: device-wide PIN of 4-8 digits, PBKDF2-SHA-256 100k iterations with per-device salt (`hashPin`), config `{salt, hash, iter, len, after, bio}`; auto-lock immediately / 1 min / 5 min (default) / 1 h; locked at start; PIN pad accepts Persian digits
- [ ] App lock backoff: 5 free tries then 30 s doubling up to 1 h (`lockDelay`), persisted
- [ ] Biometric unlock (`BIOMETRIC_WEAK`); "forgot PIN" removes the lock and logs out all accounts; lock covers UI only
- [ ] Admin/help: Persian error texts (see Admin)

## Admin
- [ ] `memberOps`: act on members below my power level; role select `عضو` 0 / `ناظر` 50 / `مدیر` 100 + custom level; confirm when granting a level equal to mine; room v12 creators count as infinite (`مالک`)
- [ ] Kick / cancel invite (with reason), ban / unban (with reason); from profile and member long-press menu
- [ ] Knock requests: accept (invite) / deny (kick); banned list with reasons
- [ ] Group settings (`canManage`): admins list, add admin
- [ ] Permissions page: send messages (`events_default` or `m.room.message` override), invite, kick, ban, redact, name/avatar/topic, pinned events, `notifications.room`, `org.matrix.msc3401.call.member`, `m.space.child` for spaces; `levelAt` / `withLevel` / `levelOptions`; locked if above my level
- [ ] Join rule: public, invite, knock (room v7+), restricted (v8+), knock_restricted (v10+) pointing at the parent space (`m.room.join_rules` `allow` type `m.room_membership`)
- [ ] History visibility: shared, invited, joined, world_readable
- [ ] Enable E2EE (irreversible), algorithm `m.megolm.v1.aes-sha2`
- [ ] Address: create/delete canonical alias, publish in / remove from the room directory
- [ ] Room details: ID copy, version (flag unstable), creators (incl. `additional_creators`), creation date, encryption algorithm, link to predecessor
- [ ] Room upgrade (`upgradeTarget` vs capabilities `m.room_versions`): `upgradeRoom`, re-invite members (non-public rooms), re-parent in spaces I manage
- [ ] Persian Matrix error texts (`errorText`): `M_FORBIDDEN`, `M_ROOM_IN_USE`, `M_EXCLUSIVE`, `M_UNSUPPORTED_ROOM_VERSION`, `M_TOO_LARGE`, `M_RESOURCE_LIMIT_EXCEEDED`, `M_NOT_FOUND`, `M_UNKNOWN_TOKEN`, `M_CANNOT_LEAVE_SERVER_NOTICE_ROOM`, `M_GUEST_ACCESS_FORBIDDEN`, `M_LIMIT_EXCEEDED` (with `retry_after_ms`); plus permission-denied (camera/mic) and network errors

## Misc
- [ ] Deep links (`ir.panbeh.core.uri`): `https://matrix.to/#/<@user|#alias|!room>[/$event][?via=...&action=join|chat]` and `matrix:u/...`, `matrix:r/...`, `matrix:roomid/...[/e/...]` (MSC2312), case-insensitive scheme; `parseMatrixHash`, `viaServers` (top 3 by member count), `matrixToLink`
- [ ] Links queued until first sync; user link -> confirm then open DM; room link -> open if joined, else confirm, resolve alias, join with via, jump to event
- [ ] Pasted matrix.to link text treated as a link; opening a chat clears its marked-unread flag
- [ ] User profile: fresh profile, presence/last seen, trust badge; message / start chat; voice/video call (only with a DM); link + QR; verify; block/unblock; member admin
- [ ] Link sheet: QR, copy, share
- [ ] Dialogs: confirm, prompt (input filter + hint), alert; toast; copy fallback
- [ ] Sheets (bottom sheets on phones), context menus, custom select, long-press with hold-to-switch, sliding segmented control, sortable tabs
- [ ] Back handling: LIFO handlers, then overlays, then close chat, then low-priority (clear search, leave calls, leave archive, first folder), else minimize app
- [ ] Window/app title and badge `(N) پنبه` equivalent as launcher badge
- [ ] Layout: Telegram-like (bubble radius 16 with burst corners, centred pills, folder tabs with sliding underline), light/dark tokens (light bg `#fff`, text `#000`, muted `#707579`, border `#e6e6e6`; dark bg `#181818`, panel `#212121`, text `#fff`, muted `#aaa`, border `#303030`), outgoing bubble accent mixed 14% with white (light) or 70% with `#212121` (dark), read ticks `#4fae4e`, 7-colour sender palette, Vazirmatn 15 px/1.45, reduced-motion support, edge-to-edge insets

## Android native
- [ ] minSdk 24, target/compile 36; `allowBackup=false`; data-extraction rules exclude everything from cloud backup and device transfer
- [ ] Main activity `singleTask`, `supportsPictureInPicture`; text zoom fixed 100
- [ ] Intent filters: MAIN/LAUNCHER; VIEW scheme `ir.panbeh.app.kotlin` (OAuth redirect); VIEW scheme `matrix`; VIEW `https://matrix.to` (no autoVerify); SEND and SEND_MULTIPLE `*/*`
- [ ] Permissions: INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS, FOREGROUND_SERVICE (+ `_REMOTE_MESSAGING`, `_MICROPHONE`, `_CAMERA`), RECEIVE_BOOT_COMPLETED, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, REQUEST_INSTALL_PACKAGES, VIBRATE, RECORD_AUDIO, MODIFY_AUDIO_SETTINGS, CAMERA (camera feature not required), USE_FULL_SCREEN_INTENT, MANAGE_OWN_CALLS, WAKE_LOCK
- [ ] `SyncService` foreground service (types remoteMessaging|microphone|camera), `START_STICKY`; exactly one client syncs at a time
- [ ] Prefs: `background` (bool), `interval` (minutes; 0 = real-time)
- [ ] Real-time mode: permanent `PARTIAL_WAKE_LOCK` "panbeh:sync"
- [ ] Interval mode: `AlarmManager.setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP)`, check wake lock 45 s "panbeh:check", kick; check ends 5 s after sync reports SYNCING
- [ ] Watchdog kicks every 60 s; `registerDefaultNetworkCallback` -> `kick(changed network)`; track online state
- [ ] Ongoing notification channel `service` (MIN): «پنبه متصل است» / «در حال اتصال…» / «اتصال قطع است؛ تلاش دوباره…»; «تماس در جریان است» during a call; mic/camera foreground types during calls (fallback mic only)
- [ ] Notification channels: `dms`, `groups`, `mentions` (HIGH, vibrate), `messages_quiet` (LOW), `service` (MIN, no badge), `calls` (HIGH, ringtone, vibration 0/1000/1000); legacy `messages` channel deleted
- [ ] One MessagingStyle notification per room (tag = roomId, id 1), up to 7 messages read back, Person per sender with circular avatar, event id in extras so redaction removes the message
- [ ] Group summary `ir.panbeh.app.kotlin.MESSAGES`-style key; long-lived dynamic conversation shortcut per room (skipped when hidden)
- [ ] Actions Reply (RemoteInput key `reply`, FLAG_MUTABLE), Mark read, Mute 8h (semantic actions); `replyFailed` notification; accent colour `0xFF007DED`
- [ ] Incoming call: `CallStyle.forIncomingCall` (decline -> receiver; answer/open -> activity), full-screen intent, FLAG_INSISTENT, `setTimeoutAfter`, tag = roomId id 2; registered with Telecom
- [ ] `CallConnectionService` (API 26+): self-managed PhoneAccount "panbeh", address `panbeh:<roomId>`, capabilities hold / support hold / mute, VoIP audio mode; `addNewIncomingCall` for rings, `placeCall` for outgoing/answered
- [ ] Telecom callbacks: `onAnswer` opens app with answer action (30 s phantom-call guard), `onReject` decline, `onDisconnect` hangup, `onHold`/`onUnhold` mute mic + silence audio; audio routing via `setAudioRoute` / `requestBluetoothAudio`
- [ ] Notify/call receivers deliver to the app or the background client; with no client running the call ring just stops
- [ ] Boot receiver (BOOT_COMPLETED, MY_PACKAGE_REPLACED): restart service if enabled
- [ ] Notification permission request on first start (when enabled), battery-optimization exemption, full-screen intent request, `status` (permission, service, interval, batteryOptimized, fullScreen), open system notification settings
- [ ] Launch intents consumed once: room, link, share, call
- [ ] Call device handling: `MODE_IN_COMMUNICATION`, show over lock screen / turn screen on, proximity wake lock for voice calls not on speaker, `audioRoutes`/`setAudioRoute` (Android 12+ communication devices: earpiece, speaker, wired/USB, Bluetooth SCO/BLE; Bluetooth preferred at start; new headset takes over), PiP (9:16, auto-enter on 12+), immersive mode
- [ ] File save API via MediaStore/SAF in chunks (512 KB) with abort; `openFile` only for URIs this app saved
- [ ] Biometric availability + unlock (`BIOMETRIC_WEAK`); `isActiveNetworkMetered`
- [ ] FileProvider `${applicationId}.fileprovider`
- [ ] Activity `visible` flag: suppress notification for the open room while visible
- [ ] Share targets (SEND intents) through a chat picker (`ShareSheet`)

## Not ported (desktop/web only)
- Electron desktop bridge (`desktop.ts`): tray visibility, badge, autostart, updater, link handler, screen-share source picker
- Browser Notification API permission flow, web protocol handler registration for `matrix:`, `<a download>` saving
- Browser picture-in-picture, WebAudio ringtone synthesis, `setSinkId` device pickers (web mic/camera/speaker), noise-suppression toggle, `panbeh.callDevices`
- Screen share (web/desktop; Android screen capture is out of scope until requested)
- Keyboard shortcuts (Ctrl+K, Ctrl+F, Alt+arrows, Esc, Alt+Left/Right folder order, Up to edit last), hover action bar, drag-and-drop, paste-files, right-click menus, floating window title
- Capacitor/WebView plumbing (`window.PanbehAndroid`, `panbehKick`, headless WebView, `WebViewAssetLoader`), IndexedDB / Cache API specifics, DOMPurify, react-virtuoso
- `sdk.test.ts` guard for js-sdk `RustCrypto.encryptEvent` (the Trixnity equivalent is to encrypt a delayed event before scheduling it)
