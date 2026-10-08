import { useSyncExternalStore } from "react";
import { AutoDiscovery, CallEvent, CallFeedEvent, createNewMatrixCall, EventType, MatrixEventEvent, RelationType, RoomEvent, type IRoomTimelineData, type MatrixCall, type MatrixEvent, type Room } from "matrix-js-sdk";
import { CallErrorCode, CallState } from "matrix-js-sdk/lib/webrtc/call.js";
import { CallEventHandlerEvent } from "matrix-js-sdk/lib/webrtc/callEventHandler.js";
import type { CallFeed } from "matrix-js-sdk/lib/webrtc/callFeed.js";
import type { MCallInviteNegotiate } from "matrix-js-sdk/lib/webrtc/callEventTypes.js";
import { getCallNotificationExpiry, isLivekitTransportConfig, MatrixRTCSessionEvent, type IRTCNotificationContent, type LivekitTransportConfig, type MatrixRTCSession } from "matrix-js-sdk/lib/matrixrtc/index.js";
import { BaseKeyProvider, ConnectionQuality, createKeyMaterialFromBuffer, AudioPresets, createLocalTracks, Room as LkRoom, ScreenSharePresets, VideoPresets, VideoQuality, RoomEvent as LkEvent, Track, type LocalAudioTrack, type LocalVideoTrack, type Participant, type RemoteTrack } from "livekit-client";
import { avatarUrl, client, isDirect, isEncrypted, loadEvent, pastFirstSync } from "./matrix.ts";
import { callNotice, isMuted, ringtone, waitingTone } from "./notify.ts";
import { senderName } from "./ui/common.tsx";
import { alertDialog } from "./ui/dialog.tsx";
import { isGroupCallAlert, isLegacyRing, isRing, isVideoOffer } from "./logic.ts";
import { legacyCallsOn, loadPrefs } from "./ui/Settings.tsx";
import { isHeadless, isNative, nativeCallActive, nativeCancelCall, nativeSetAudioRoute, nativeShowCall, nativeSpeaker, toDataUrl, watchAudioRoutes, type CallAction, type Routes } from "./native.ts";
import { isWindowVisible, showWindow } from "./desktop.ts";

/**
 * Two kinds of call:
 * - MatrixRTC (what Element Call / Element X speak): membership and E2EE keys via the SDK, media via LiveKit. Groups and our own DMs.
 * - legacy 1:1 m.call.* (old Element, FluffyChat, Nheko…): the SDK's peer-to-peer MatrixCall. Answered, and used to call those people back.
 */

type Common = {
  room: Room; video: boolean;
  since?: number; // first time someone else was in the call
  min: boolean; speaker: boolean; facing: "user" | "environment";
  routes?: Routes; // Android 12+: where audio can go and where it goes now
  held?: { mic: boolean }; // Android: a phone call put ours on hold; whether our mic was on before
  lowData?: boolean; lowDataOff?: boolean; // low-data mode on now; turned off by hand for this call (auto stays out)
  reconnecting?: boolean; // network dropped; LiveKit / ICE is trying to get it back
  notice?: string; // "X joined" for a few seconds (groups)
};
export type Active = Common & ({ kind: "rtc"; session: MatrixRTCSession; lk: LkRoom } | { kind: "legacy"; mc: MatrixCall });
/** ev: the m.rtc.notification or m.call.invite. mc: the SDK's legacy call (not in Android's headless page, which leaves VoIP off). */
export type Incoming = { room: Room; ev: MatrixEvent; video: boolean; mc?: MatrixCall };
type Snap = { active?: Active; incoming?: Incoming; tick: number };

let snap: Snap = { tick: 0 };
const subs = new Set<() => void>();
const set = (p: Partial<Snap>) => { snap = { ...snap, ...p, tick: snap.tick + 1 }; subs.forEach((f) => f()); };
const patch = (p: Partial<Common>) => { if (snap.active) set({ active: { ...snap.active, ...p } }); };
const bump = () => set({});
const subscribe = (f: () => void) => (subs.add(f), () => void subs.delete(f));
export const useCall = () => useSyncExternalStore(subscribe, () => snap);
export const getCall = () => snap;

// ---------- finding the SFU ----------

let transport: Promise<LivekitTransportConfig | undefined> | undefined;
/** Our homeserver's LiveKit: MSC4143 /rtc/transports (newer Synapse), else .well-known rtc_foci (any server). */
export const ourTransport = () => transport ??= (async () => {
  const fromHs = await client.cachedRtcTransports.wait().catch(() => undefined);
  const list = fromHs?.length ? fromHs
    : (await AutoDiscovery.getRawClientConfig(client.getDomain()!).catch(() => undefined))?.["org.matrix.msc4143.rtc_foci"];
  return (Array.isArray(list) ? list : []).find(isLivekitTransportConfig);
})();

/**
 * LiveKit JWT from lk-jwt-service. The legacy endpoint names us "@user:server:DEVICE", the identity our m.call.member keys are filed under.
 * Clients on sticky m.rtc.member events (/get_token, hashed identities) still end up in the same LiveKit room, which lk-jwt-service
 * names after the Matrix room and slot for both endpoints. The SDK reads both kinds of membership, so calls mix.
 */
// ponytail: /sfu/get only; switch to sticky m.rtc.member + /get_token together once our servers support MSC4354 sticky events
async function sfuToken(t: LivekitTransportConfig, roomId: string): Promise<{ url: string; jwt: string }> {
  const openid_token = await client.getOpenIdToken();
  const r = await fetch(t.livekit_service_url.replace(/\/+$/, "") + "/sfu/get", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ room: roomId, openid_token, device_id: client.getDeviceId() }),
  });
  if (!r.ok) throw new Error(`سرور تماس در دسترس نیست (${r.status})`);
  return r.json();
}

/** Per-participant media keys handed out by the SDK's MatrixRTC encryption manager. */
class Keys extends BaseKeyProvider {
  constructor() { super({ ratchetWindowSize: 0, keyringSize: 256 }); }
  async set(key: Uint8Array, index: number, identity: string) {
    this.onSetEncryptionKey(await createKeyMaterialFromBuffer(key.slice().buffer), identity, index);
  }
}

// ---------- devices (desktop/web) ----------

/** The mic, camera and speaker picked on the call screen, and noise suppression; unset = the system's default. */
type Devices = { audioinput?: string; videoinput?: string; audiooutput?: string; noiseSuppression?: boolean };
const DEVICES_KEY = "panbeh.callDevices";
export const loadDevices = (): Devices => { try { return JSON.parse(localStorage.getItem(DEVICES_KEY) ?? "{}"); } catch { return {}; } };
const saveDevices = (p: Devices) => { try { localStorage.setItem(DEVICES_KEY, JSON.stringify({ ...loadDevices(), ...p })); } catch { /* not remembered */ } };
const audioCapture = (d = loadDevices()) => ({ deviceId: d.audioinput, noiseSuppression: d.noiseSuppression ?? true, echoCancellation: true, autoGainControl: true });

/** What we send, picked in settings: camera, screen share and audio (calls and voice messages). */
const CAM_PRESETS = { "360": VideoPresets.h360, "540": VideoPresets.h540, "720": VideoPresets.h720, "1080": VideoPresets.h1080 };
const SCREEN_PRESETS = { "720": ScreenSharePresets.h720fps15, "1080": ScreenSharePresets.h1080fps15, "1080hi": ScreenSharePresets.h1080fps30 };
export const AUDIO_BPS = { low: AudioPresets.speech.maxBitrate, normal: AudioPresets.music.maxBitrate, high: AudioPresets.musicHighQuality.maxBitrate };
const cam = () => ({ resolution: CAM_PRESETS[loadPrefs().camQuality].resolution });

/** Switches a device now and for later calls. */
export async function setDevice(kind: MediaDeviceKind, deviceId: string) {
  saveDevices({ [kind]: deviceId });
  const a = snap.active;
  if (a?.kind === "rtc") await a.lk.switchActiveDevice(kind, deviceId);
  else if (a && kind === "audioinput") await client.getMediaHandler().setAudioInput(deviceId);
  else if (a && kind === "videoinput") await client.getMediaHandler().setVideoInput(deviceId);
  else if (a) for (const el of document.querySelectorAll<HTMLAudioElement>("audio.call-audio")) await el.setSinkId(deviceId);
  bump();
}

export async function setNoiseSuppression(on: boolean) {
  saveDevices({ noiseSuppression: on });
  const a = snap.active;
  if (a?.kind === "rtc") await (a.lk.localParticipant.getTrackPublication(Track.Source.Microphone)?.audioTrack as LocalAudioTrack | undefined)?.restartTrack(audioCapture());
  else if (a) await client.getMediaHandler().setAudioSettings(audioCapture());
  bump();
}

/** The legacy stack's MediaHandler starts from our picks too. */
function legacyDevices() {
  const d = loadDevices(), mh = client.getMediaHandler();
  mh.restoreMediaSettings(d.audioinput ?? "", d.videoinput ?? "");
  void mh.setAudioSettings(audioCapture(d));
}

// ---------- the call ----------

let cleanup: (() => void)[] = [];

/** Starts (ring = true) or joins the room's call. One call at a time. */
export async function call(room: Room, video: boolean, ring = true, legacy = false) {
  if (snap.active) {
    if (snap.active.room === room) return patch({ min: false });
    await hangup();
  }
  stopRinging();
  if (legacy) return placeLegacy(room, video);
  // mic (and camera) first: if they're refused, nobody gets rung for nothing
  const d = loadDevices();
  const media = await createLocalTracks({ audio: audioCapture(d), video: video ? { ...cam(), facingMode: "user", deviceId: d.videoinput } : false });
  const mine = await ourTransport();
  const session = client.matrixRTC.getRoomSession(room);
  // everyone meets on the SFU of whoever was in the call first
  const oldest = session.getOldestMembership();
  const theirs = oldest?.getTransport(oldest);
  const sfu = theirs && isLivekitTransportConfig(theirs) ? theirs : mine;
  if (!sfu) { media.forEach((t) => t.stop()); throw new Error("سرور شما از تماس پشتیبانی نمی‌کند"); }
  const e2ee = isEncrypted(room);
  const keys = new Keys();
  const p = loadPrefs();
  const lk = new LkRoom({
    adaptiveStream: true, dynacast: true,
    publishDefaults: {
      audioPreset: { maxBitrate: AUDIO_BPS[p.audioQuality] }, dtx: false,
      videoEncoding: CAM_PRESETS[p.camQuality].encoding,
      screenShareEncoding: SCREEN_PRESETS[p.screenQuality].encoding, screenShareSimulcastLayers: [],
    },
    audioCaptureDefaults: audioCapture(d), videoCaptureDefaults: { ...cam(), deviceId: d.videoinput }, audioOutput: { deviceId: d.audiooutput },
    e2ee: e2ee ? { keyProvider: keys, worker: new Worker(new URL("livekit-client/e2ee-worker", import.meta.url), { type: "module" }) } : undefined,
  });
  set({ active: { kind: "rtc", room, session, lk, video, min: false, speaker: video, facing: "user" } });

  const onKey = (key: Uint8Array, index: number, _m: unknown, identity: string) => void keys.set(key, index, identity);
  const onMembers = () => {
    const a = snap.active;
    if (!a || !isDirect(room)) return;
    // 1:1: the call is over once the other side has left
    if (a.since && !session.memberships.some((m) => m.userId !== client.getUserId())) void hangup();
  };
  session.on(MatrixRTCSessionEvent.EncryptionKeyChanged, onKey);
  session.on(MatrixRTCSessionEvent.MembershipsChanged, onMembers);
  session.on(MatrixRTCSessionEvent.MembershipsChanged, scanHands);
  const onTrack = (t: RemoteTrack) => { // plays even with the call screen minimized
    if (t.kind === Track.Kind.Audio) document.body.append(Object.assign(t.attach(), { className: "call-audio" }));
  };
  const offTrack = (t: RemoteTrack) => t.detach().forEach((el) => el.remove());
  const onPeople = () => {
    if (!snap.active?.since && lk.remoteParticipants.size) patch({ since: Date.now() });
    else bump();
  };
  const said = (verb: string) => (p: Participant) => { if (!isDirect(room)) notice(`${nameOf(room, userOf(session, p.identity))} ${verb}`); };
  lk.on(LkEvent.TrackSubscribed, onTrack).on(LkEvent.TrackUnsubscribed, offTrack)
    .on(LkEvent.ParticipantConnected, onPeople).on(LkEvent.ParticipantDisconnected, bump)
    .on(LkEvent.ParticipantConnected, said("پیوست")).on(LkEvent.ParticipantDisconnected, said("رفت"))
    .on(LkEvent.Reconnecting, () => patch({ reconnecting: true })).on(LkEvent.SignalReconnecting, () => patch({ reconnecting: true }))
    .on(LkEvent.Reconnected, () => patch({ reconnecting: false })).on(LkEvent.ConnectionQualityChanged, bump)
    .on(LkEvent.TrackMuted, bump).on(LkEvent.TrackUnmuted, bump).on(LkEvent.ActiveSpeakersChanged, bump)
    .on(LkEvent.LocalTrackPublished, bump).on(LkEvent.LocalTrackUnpublished, bump)
    .on(LkEvent.ConnectionStateChanged, bump).on(LkEvent.Disconnected, () => void hangup())
    .on(LkEvent.AudioPlaybackStatusChanged, () => { if (!lk.canPlaybackAudio) void lk.startAudio(); });
  // low-data mode: tracks that show up later get it too; "auto" follows our own connection (on after 5s poor, off after 15s fine)
  let lowTimer: ReturnType<typeof setTimeout> | undefined;
  const onQuality = (q: ConnectionQuality, p: Participant) => {
    if (!p.isLocal || loadPrefs().lowData !== "auto" || snap.active?.lowDataOff) return;
    const poor = q === ConnectionQuality.Poor || q === ConnectionQuality.Lost;
    clearTimeout(lowTimer);
    if (poor !== !!snap.active?.lowData) lowTimer = setTimeout(() => void setLowData(poor), poor ? 5000 : 15000);
  };
  const reapply = () => { if (snap.active?.lowData) void setLowData(true); };
  lk.on(LkEvent.ConnectionQualityChanged, onQuality).on(LkEvent.TrackSubscribed, reapply).on(LkEvent.LocalTrackPublished, reapply);
  // 1:1 nobody picks up: give up when our ring would have expired anyway
  const noAnswer = ring && isDirect(room) ? setTimeout(() => { if (!snap.active?.since) void hangup(); }, 90_000) : undefined;
  cleanup = [
    () => { session.off(MatrixRTCSessionEvent.EncryptionKeyChanged, onKey); session.off(MatrixRTCSessionEvent.MembershipsChanged, onMembers); session.off(MatrixRTCSessionEvent.MembershipsChanged, scanHands); },
    () => { clearTimeout(noAnswer); clearTimeout(lowTimer); },
    leaveOnUnload(),
  ];

  try {
    const { url, jwt } = await sfuToken(sfu, room.roomId);
    const now = snap.active;
    if (now?.kind !== "rtc" || now.lk !== lk) return media.forEach((t) => t.stop()); // hung up meanwhile
    const [userId, deviceId] = [client.getSafeUserId(), client.getDeviceId()!];
    session.joinRTCSession({ userId, deviceId, memberId: `${userId}:${deviceId}` }, [{ ...(mine ?? sfu), livekit_alias: room.roomId }], undefined, {
      notificationType: ring ? (isDirect(room) ? "ring" : "notification") : undefined, // the SDK only sends it if we're the first one in
      callIntent: video ? "video" : "audio",
      manageMediaKeys: e2ee,
    });
    session.reemitEncryptionKeys();
    await lk.connect(url, jwt);
    if (e2ee) await lk.setE2EEEnabled(true);
    onPeople();
    scanHands();
    for (const t of media) await lk.localParticipant.publishTrack(t);
    if (p.lowData === "on") await setLowData(true);
    if (isNative) nativeAudioOn(room, video);
  } catch (e) {
    media.forEach((t) => t.stop()); // not published yet = not stopped by the disconnect
    await hangup();
    throw e;
  }
}

function leaveOnUnload() {
  const f = () => void hangup();
  addEventListener("pagehide", f);
  return () => removeEventListener("pagehide", f);
}

export async function hangup() {
  const a = snap.active;
  if (!a) return;
  set({ active: undefined });
  hands.clear();
  reactions.clear();
  cleanup.forEach((f) => f());
  cleanup = [];
  if (isNative) nativeCallActive(false, false);
  if (a.kind === "legacy") {
    if (!a.mc.callHasEnded()) a.mc.hangup(CallErrorCode.UserHangup, false);
    document.querySelectorAll("audio.call-audio").forEach((el) => el.remove());
    return;
  }
  await a.lk.disconnect().catch(() => {});
  document.querySelectorAll("audio.call-audio").forEach((el) => el.remove()); // disconnecting doesn't unsubscribe them
  await a.session.leaveRoomSession(5000).catch(() => {});
}

export const minimize = (min: boolean) => patch({ min });

/** A LiveKit identity's call membership: "@user:server:DEVICE" for legacy memberships, hashed for newer clients. */
export const membershipOf = (session: MatrixRTCSession, identity: string) =>
  session.memberships.find((m) => m.rtcBackendIdentity === identity || `${m.userId}:${m.deviceId}` === identity);
export const userOf = (session: MatrixRTCSession, identity: string) =>
  membershipOf(session, identity)?.userId ?? identity.slice(0, identity.lastIndexOf(":"));
const nameOf = (room: Room, userId: string) => room.getMember(userId)?.name ?? userId;

let noticeTimer: ReturnType<typeof setTimeout> | undefined;
/** Shows `text` in the call's status line for 3s. */
function notice(text: string) {
  patch({ notice: text });
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => patch({ notice: undefined }), 3000);
}

/** What we're sending, for the control buttons. */
export function myMedia(a: Active) {
  if (a.kind === "legacy") return { mic: !a.mc.isMicrophoneMuted(), cam: !a.mc.isLocalVideoMuted() && a.mc.hasLocalUserMediaVideoTrack, screen: a.mc.isScreensharing() };
  const p = a.lk.localParticipant;
  return { mic: p.isMicrophoneEnabled, cam: p.isCameraEnabled, screen: p.isScreenShareEnabled };
}

export async function toggleMic() {
  const a = snap.active;
  if (a?.kind === "legacy") await a.mc.setMicrophoneMuted(!a.mc.isMicrophoneMuted());
  else if (a) await a.lk.localParticipant.setMicrophoneEnabled(!a.lk.localParticipant.isMicrophoneEnabled);
  bump();
}
export async function toggleCam() {
  const a = snap.active;
  if (a?.kind === "legacy") await a.mc.setLocalVideoMuted(myMedia(a).cam); // unmuting a voice call upgrades it to video
  else if (a) await a.lk.localParticipant.setCameraEnabled(!a.lk.localParticipant.isCameraEnabled, { ...cam(), facingMode: a.facing, deviceId: loadDevices().videoinput });
  if (a && isNative) nativeCallActive(true, myMedia(a).cam); // camera in the background service, proximity sensor off for video
  bump();
}
export async function flipCam() {
  const a = snap.active;
  if (!a) return;
  // every camera in turn (front, back, the other back ones…), by device id
  // ponytail: only the cameras Android's WebView lists; some phones hide ultra-wide/tele behind one logical camera
  const track = () => a.kind === "legacy" ? a.mc.localUsermediaStream?.getVideoTracks()[0]
    : a.lk.localParticipant.getTrackPublication(Track.Source.Camera)?.videoTrack?.mediaStreamTrack;
  const cams = (await navigator.mediaDevices.enumerateDevices()).filter((d) => d.kind === "videoinput" && d.deviceId);
  const next = cams[(cams.findIndex((d) => d.deviceId === track()?.getSettings().deviceId) + 1) % cams.length];
  if (!next) return;
  if (a.kind === "legacy") await client.getMediaHandler().setVideoInput(next.deviceId);
  else await a.lk.switchActiveDevice("videoinput", next.deviceId);
  patch({ facing: track()?.getSettings().facingMode === "environment" ? "environment" : "user" }); // mirrors the preview
}
export async function toggleScreen() {
  const a = snap.active;
  if (a?.kind === "legacy") await a.mc.setScreensharingEnabled(!a.mc.isScreensharing());
  else if (a) await a.lk.localParticipant.setScreenShareEnabled(!a.lk.localParticipant.isScreenShareEnabled, { audio: true, contentHint: "detail" });
  bump();
}
/** Android 12+: call audio to the earpiece, speaker, a wired or a Bluetooth headset (the routes event then updates the button). */
export const setAudioRoute = (id: number) => nativeSetAudioRoute(id);
/** Low-data mode (MatrixRTC): everyone's video at the lowest simulcast layer, and ours sent at its lowest. byUser: off by hand, auto stays out. */
// ponytail: dynacast may re-enable our higher layers when someone asks for them; legacy calls have no layers and aren't covered
export async function setLowData(on: boolean, byUser = false) {
  const a = snap.active;
  if (a?.kind !== "rtc") return;
  const q = on ? VideoQuality.LOW : VideoQuality.HIGH;
  for (const p of a.lk.remoteParticipants.values()) for (const pub of p.videoTrackPublications.values()) pub.setVideoQuality(q);
  for (const pub of a.lk.localParticipant.videoTrackPublications.values()) (pub.videoTrack as LocalVideoTrack | undefined)?.setPublishingQuality(q);
  patch({ lowData: on, ...(byUser ? { lowDataOff: !on } : {}) });
}

/** Android: a phone call put ours on hold (Telecom): mic off and their audio silenced until it gives the call back. */
export async function hold(on: boolean) {
  const a = snap.active;
  if (!a || !!a.held === on) return;
  const mic = myMedia(a).mic;
  if (on ? mic : a.held?.mic && !mic) await toggleMic();
  document.querySelectorAll<HTMLAudioElement>("audio.call-audio").forEach((el) => { el.muted = on; });
  patch({ held: on ? { mic } : undefined });
}
export function toggleSpeaker() {
  const a = snap.active;
  if (!a) return;
  nativeSpeaker(!a.speaker);
  patch({ speaker: !a.speaker });
}

// ---------- raised hands and reactions (MatrixRTC; Element Call's events) ----------

/** Raised hands: membership event id → the 🖐️ m.reaction on it. Reactions on screen: membership event id → emoji. */
const hands = new Map<string, string>();
const reactions = new Map<string, string>();
const HAND = "🖐️", REACTION = "io.element.call.reaction";
export const handOf = (membershipId?: string) => !!membershipId && hands.has(membershipId);
export const reactionOf = (membershipId?: string) => membershipId ? reactions.get(membershipId) : undefined;
/** What Element Call offers; name is its key for the reaction's sound. */
export const REACTIONS: [string, string][] = [["👍", "thumbsup"], ["👏", "clapping"], ["🎉", "party"], ["😄", "laugh"], ["🥰", "heart"], ["👌", "ok"], ["💡", "lightbulb"], ["👎", "thumbsdown"]];

const myMembership = (session: MatrixRTCSession) =>
  session.memberships.find((m) => m.userId === client.getUserId() && m.deviceId === client.getDeviceId());

/** Hands already up when we join (or after memberships change): 🖐️ reactions on the current membership events. */
function scanHands() {
  const a = snap.active;
  if (a?.kind !== "rtc") return;
  const rel = a.room.getUnfilteredTimelineSet().relations;
  hands.clear();
  for (const m of a.session.memberships) {
    const r = rel.getChildEventsForEvent(m.eventId, RelationType.Annotation, EventType.Reaction)?.getRelations()
      .find((e) => !e.isRedacted() && e.getSender() === m.sender && e.getContent()["m.relates_to"]?.key === HAND);
    if (r) hands.set(m.eventId, r.getId()!);
  }
  bump();
}

/** Live hands, lowered hands (redactions) and reactions in the room we're calling in. */
function onCallReaction(ev: MatrixEvent) {
  const rel = ev.getContent()["m.relates_to"];
  if (ev.getType() === EventType.Reaction && rel?.key === HAND && rel.event_id) hands.set(rel.event_id, ev.getId()!);
  else if (ev.getType() === EventType.RoomRedaction) {
    for (const [m, r] of hands) if (r === ev.getAssociatedId()) hands.delete(m);
  } else if (ev.getType() === REACTION && rel?.event_id && typeof ev.getContent().emoji === "string" && ev.getSender() !== client.getUserId()) {
    showReaction(rel.event_id, [...new Intl.Segmenter().segment(ev.getContent().emoji)][0]?.segment ?? ""); // one emoji, however long the string
  } else return;
  bump();
}

function showReaction(membershipId: string, emoji: string) {
  if (!emoji.trim()) return;
  reactions.set(membershipId, emoji);
  setTimeout(() => { if (reactions.get(membershipId) === emoji) { reactions.delete(membershipId); bump(); } }, 3000);
  bump();
}

export async function toggleHand() {
  const a = snap.active, mine = a?.kind === "rtc" ? myMembership(a.session) : undefined;
  if (!a || !mine) return;
  const up = hands.get(mine.eventId);
  if (up) {
    hands.delete(mine.eventId);
    bump();
    await client.redactEvent(a.room.roomId, up);
    return;
  }
  const r = await client.sendEvent(a.room.roomId, EventType.Reaction, { "m.relates_to": { rel_type: RelationType.Annotation, event_id: mine.eventId, key: HAND } } as never);
  hands.set(mine.eventId, r.event_id);
  bump();
}

/** Any emoji: Element Call shows names it doesn't know as their emoji. */
export async function react(emoji: string, name = REACTIONS.find(([e]) => e === emoji)?.[1] ?? "generic") {
  const a = snap.active, mine = a?.kind === "rtc" ? myMembership(a.session) : undefined;
  if (!a || !mine) return;
  showReaction(mine.eventId, emoji);
  await client.sendEvent(a.room.roomId, REACTION as never, { "m.relates_to": { rel_type: RelationType.Reference, event_id: mine.eventId }, emoji, name } as never);
}

// ---------- legacy 1:1 calls ----------

/** Rejects with the reason a legacy call failed to start (no mic, no TURN…), or resolves once it's under way. */
async function legacyStart(mc: MatrixCall, go: () => Promise<void>) {
  let err: unknown;
  const onErr = (e: { err?: unknown }) => { err = e.err ?? e; };
  mc.on(CallEvent.Error, onErr as never);
  try { await go(); } finally { mc.off(CallEvent.Error, onErr as never); }
  if (err) throw err; // getUserMedia's NotAllowedError etc., which errText understands
}

/**
 * TURN credentials arrive a moment after startup, and a legacy call keeps the ICE servers it was created with. A call made or
 * answered right after a cold start (Android answering from the notification) would get none, not even STUN, and never connect
 * across NATs. Wait for them, and hand them to an incoming call's peer connection before it starts gathering.
 */
async function withTurn(mc?: MatrixCall) {
  await client.checkTurnServers().catch(() => false);
  const ice = client.getTurnServers(), pc = mc?.peerConn;
  if (pc && ice.length) pc.setConfiguration({ ...pc.getConfiguration(), iceServers: ice });
}

/** What ICE had to work with, for the "couldn't connect" message: TURN from our server, and the kinds of address each side offered. */
async function iceReport(mc: MatrixCall) {
  const local = new Set<string>(), remote = new Set<string>();
  (await mc.peerConn?.getStats().catch(() => undefined))?.forEach((r) => {
    if (r.type === "local-candidate") local.add(r.candidateType);
    else if (r.type === "remote-candidate") remote.add(r.candidateType);
  });
  const list = (s: Set<string>) => [...s].join(", ") || "-";
  return `TURN: ${client.getTurnServers().length ? "yes" : "no"} | local: ${list(local)} | remote: ${list(remote)} | ICE: ${mc.peerConn?.iceConnectionState ?? "-"} | ${mc.state}`;
}

/** Puts a legacy call on screen and follows it until it ends. */
function showLegacy(room: Room, mc: MatrixCall, video: boolean) {
  set({ active: { kind: "legacy", room, mc, video, min: false, speaker: video, facing: "user" } });
  const audio = Object.assign(document.createElement("audio"), { className: "call-audio", autoplay: true });
  document.body.append(audio); // plays even with the call screen minimized
  const out = loadDevices().audiooutput;
  if (out && "setSinkId" in audio) audio.setSinkId(out).catch(() => {});
  const seen = new WeakSet<CallFeed>();
  const onFeeds = () => {
    for (const f of mc.getFeeds()) if (!seen.has(f)) { seen.add(f); f.on(CallFeedEvent.MuteStateChanged, bump); f.on(CallFeedEvent.NewStream, onFeeds); }
    if (audio.srcObject !== (mc.remoteUsermediaStream ?? null)) audio.srcObject = mc.remoteUsermediaStream ?? null;
    bump();
  };
  // stuck before connecting (no mic, or ICE finds no way through): say why instead of "connecting…" forever
  let stuck: ReturnType<typeof setTimeout> | undefined;
  const watch = (s: CallState) => {
    clearTimeout(stuck);
    if ([CallState.InviteSent, CallState.Ringing, CallState.Connected, CallState.Ended].includes(s) || snap.active?.since) return;
    stuck = setTimeout(async () => {
      const still = () => snap.active?.kind === "legacy" && snap.active.mc === mc && !snap.active.since;
      if (!still()) return;
      const report = await iceReport(mc); // before hanging up closes the connection
      if (!still()) return;
      console.warn("legacy call didn't connect", report);
      void hangup();
      alertDialog(mc.state === CallState.WaitLocalMedia
        ? "میکروفون یا دوربین در دسترس نیست. اجازه‌ی دسترسی پنبه به آن‌ها را بررسی کنید."
        : `اتصال تماس برقرار نشد. احتمالاً سرور ماتریکس شما سرور TURN ندارد یا به آن دسترسی نیست؛ با مدیر سرور در میان بگذارید.\n\n${report}`);
    }, 30_000);
  };
  const onState = (s: CallState) => {
    watch(s);
    if (s === CallState.Connected && !snap.active?.since) patch({ since: Date.now() });
    else if (s === CallState.Ended) void hangup();
    else bump();
  };
  const onHangup = () => void hangup(); // their hangup, decline, no answer, ICE failure
  // ICE dropping after we were connected = the network went; the SDK restarts ICE by itself
  const onIce = function (this: RTCPeerConnection) {
    if (snap.active?.since) patch({ reconnecting: ["disconnected", "failed"].includes(this.iceConnectionState) });
  };
  let pc: RTCPeerConnection | undefined;
  const onPc = (p: RTCPeerConnection) => { pc?.removeEventListener("iceconnectionstatechange", onIce); pc = p; p.addEventListener("iceconnectionstatechange", onIce); };
  if (mc.peerConn) onPc(mc.peerConn); // incoming calls have one already
  const onReplaced = (next: MatrixCall) => { // glare: we called each other at once and the SDK kept their call
    cleanup.forEach((f) => f());
    audio.remove();
    showLegacy(room, next, video);
  };
  mc.on(CallEvent.FeedsChanged, onFeeds).on(CallEvent.State, onState).on(CallEvent.Hangup, onHangup).on(CallEvent.Replaced, onReplaced).on(CallEvent.PeerConnectionCreated, onPc);
  cleanup = [() => {
    mc.off(CallEvent.FeedsChanged, onFeeds).off(CallEvent.State, onState).off(CallEvent.Hangup, onHangup).off(CallEvent.Replaced, onReplaced).off(CallEvent.PeerConnectionCreated, onPc);
    pc?.removeEventListener("iceconnectionstatechange", onIce);
    clearTimeout(stuck);
  }, leaveOnUnload()];
  watch(mc.state);
  onFeeds();
  if (isNative) nativeAudioOn(room, video);
}

/** Android: call audio mode, routed to a headset if there is one, else the speaker for video; then follows the routes as they change. */
function nativeAudioOn(room: Room, video: boolean) {
  nativeCallActive(true, video, room);
  cleanup.push(watchAudioRoutes((routes) => patch({ routes, speaker: routes.routes.length ? routes.routes.find((r) => r.id === routes.current)?.kind === "speaker" : !!snap.active?.speaker })));
}

async function placeLegacy(room: Room, video: boolean) {
  legacyDevices();
  await withTurn(); // createCall copies the TURN servers we have now
  const mc = client.createCall(room.roomId);
  if (!mc) throw new Error("این دستگاه از تماس پشتیبانی نمی‌کند");
  showLegacy(room, mc, video);
  try {
    await legacyStart(mc, () => video ? mc.placeVideoCall() : mc.placeVoiceCall()); // the SDK gets mic/camera before inviting
  } catch (e) {
    await hangup();
    throw e;
  }
}

async function answerLegacy(room: Room, mc: MatrixCall, video: boolean) {
  if (snap.active) await hangup();
  stopRinging();
  if (mc.callHasEnded()) return;
  legacyDevices();
  await withTurn(mc);
  showLegacy(room, mc, video);
  try {
    await legacyStart(mc, () => mc.answer(true, video));
  } catch (e) {
    await hangup();
    throw e;
  }
}

/** The SDK's call for an invite. A cold-started app replayed the invite from its cache before the SDK listened, so build it ourselves then. */
async function legacyCallFor(room: Room, invite: MatrixEvent) {
  const h = client.callEventHandler, c = invite.getContent<MCallInviteNegotiate>();
  if (!h) return;
  const known = h.calls.get(c.call_id);
  if (known || !isLegacyRing(c, invite.getTs(), client.getSafeUserId())) return known;
  const mc = createNewMatrixCall(client, room.roomId, { forceTURN: client.forceTURN });
  if (!mc) return;
  mc.callId = c.call_id;
  await mc.initWithInvite(invite);
  h.calls.set(mc.callId, mc);
  // candidates that came before we were listening
  for (const ev of room.getLiveTimeline().getEvents()) {
    if (ev.getType() === EventType.CallCandidates && ev.getContent().call_id === mc.callId) await mc.onRemoteIceCandidatesReceived(ev);
  }
  return mc;
}

const rejectedByUs = (room: Room, callId: string) => room.getLiveTimeline().getEvents()
  .some((e) => e.getType() === EventType.CallReject && e.getSender() === client.getUserId() && e.getContent().call_id === callId);

const inviteOf = (room: Room, callId: string) =>
  room.getLiveTimeline().getEvents().findLast((e) => e.getType() === EventType.CallInvite && e.getContent().call_id === callId);

/** Our decline of a legacy invite, for when there's no MatrixCall to reject() (headless page). */
function rejectInvite(roomId: string, invite: MatrixEvent) {
  const c = invite.getContent(), v1 = String(c.version) === "1";
  return client.sendEvent(roomId, v1 ? EventType.CallReject : EventType.CallHangup,
    { call_id: c.call_id, party_id: client.getDeviceId(), version: v1 ? "1" : 0 } as never).catch(() => {});
}

// ---------- ringing ----------

let stopRing: (() => void)[] = [];

/** missed: it stopped without any of our devices answering or declining (caller gave up, or it timed out). */
function stopRinging(missed = false) {
  const i = snap.incoming;
  if (missed && i) void callNotice(i.room, i.video ? "تماس تصویری از دست رفته" : "تماس صوتی از دست رفته");
  stopRing.forEach((f) => f());
  stopRing = [];
  if (isNative && snap.incoming && !snap.active) nativeCallActive(false, false); // a ring that woke the lock screen lets it sleep again
  if (snap.incoming) set({ incoming: undefined });
}

/** Already ringing, in a call in that same room, muted or ignored: don't ring. In another call, it rings as call waiting. */
const shouldRing = (room: Room, from: string) => !snap.incoming && snap.active?.room !== room && !isMuted(room) && !client.isUserIgnored(from);

/** Rings for an m.rtc.notification if it's a ring meant for us, still fresh, and we're not in that call already. */
// ponytail: our own checks, not the SDK's parseCallNotificationContent: that one rejects rings in rooms without an m.rtc.slot, i.e. most of today's
function ring(room: Room, ev: MatrixEvent) {
  const c = ev.getContent<IRTCNotificationContent>(), me = client.getSafeUserId();
  const until = getCallNotificationExpiry(c, ev.getTs());
  const session = client.matrixRTC.getRoomSession(room);
  const inIt = session.memberships.some((m) => m.userId === me); // already in it (another device)
  if (ev.getSender() === me || inIt || isMuted(room) || client.isUserIgnored(ev.getSender()!)) return;
  const video = c["m.call.intent"] === "video";
  if (isGroupCallAlert(c, until, me)) return void callNotice(room, `${senderName(ev)} ${video ? "تماس تصویری" : "تماس صوتی"} گروهی را شروع کرد`, true);
  if (!isRing(c, until, me) || !shouldRing(room, ev.getSender()!)) return;
  startRinging({ room, ev, video }, until);
  // stops when the caller gives up or one of our devices answers
  const onMembers = () => {
    if (session.memberships.some((m) => m.userId === me)) stopRinging();
    else if (!session.memberships.length) stopRinging(true);
  };
  session.on(MatrixRTCSessionEvent.MembershipsChanged, onMembers);
  stopRing.push(() => session.off(MatrixRTCSessionEvent.MembershipsChanged, onMembers));
}

/** Rings for a legacy invite: with the SDK's call (VoIP on), or just the event (headless page, which only shows the notification). */
function ringLegacy(room: Room, ev: MatrixEvent, mc?: MatrixCall) {
  const c = ev.getContent<MCallInviteNegotiate>();
  if (mc?.callHasEnded() || !isLegacyRing(c, ev.getTs(), client.getSafeUserId()) || ev.getSender() === client.getUserId() || !shouldRing(room, ev.getSender()!)) return;
  // off by default: say so instead of ringing. No reject, so the user's other apps still ring.
  if (!legacyCallsOn()) return void callNotice(room, `${senderName(ev)} با روش قدیمی تماس گرفت که پشتیبانی نمی‌شود. برای دریافت این تماس‌ها، از تنظیمات › گزینه‌های توسعه‌دهنده «دریافت تماس‌های قدیمی» را روشن کنید.`);
  startRinging({ room, ev, mc, video: isVideoOffer(c) }, ev.getTs() + c.lifetime);
  if (!mc) return; // headless: onEvent stops it on the hangup / answer events
  // caller gave up, or answered/declined on another of our devices
  const onEnd = () => stopRinging(mc.hangupReason !== CallErrorCode.AnsweredElsewhere && !rejectedByUs(room, mc.callId));
  mc.on(CallEvent.Hangup, onEnd);
  stopRing.push(() => mc.off(CallEvent.Hangup, onEnd));
}

/** Rings until `until`: Android's call notification in the background, our ring screen and tone otherwise. */
function startRinging(i: Incoming, until: number) {
  const { room, ev, video } = i;
  set({ incoming: i });
  const t = setTimeout(() => stopRinging(true), until - Date.now());
  stopRing = [() => clearTimeout(t)];

  // ponytail: call waiting is only our banner and beep, even with the app in the background (where the call keeps the page alive)
  if (snap.active) return void stopRing.push(waitingTone());
  if (isNative && (isHeadless || document.visibilityState === "hidden")) {
    void (async () => {
      const icon = await avatarUrl(room.getAvatarFallbackMember()?.getMxcAvatarUrl() ?? room.getMxcAvatarUrl(), 96)?.catch(() => undefined);
      if (snap.incoming?.ev !== ev) return;
      nativeShowCall({ roomId: room.roomId, eventId: ev.getId()!, caller: room.name, video, timeout: until - Date.now(), icon: icon && await toDataUrl(icon).catch(() => undefined) });
    })();
    stopRing.push(() => nativeCancelCall(room.roomId));
    return;
  }
  stopRing.push(ringtone());
  if (!isWindowVisible() && "Notification" in window && Notification.permission === "granted") {
    const n = new Notification(room.name, { body: video ? "تماس تصویری…" : "تماس صوتی…", tag: "call:" + room.roomId, requireInteraction: true, lang: "fa", dir: "rtl" });
    n.onclick = () => { showWindow(); n.close(); };
    stopRing.push(() => n.close());
  }
}

const sendDecline = (roomId: string, eventId: string) =>
  client.sendEvent(roomId, EventType.RTCDecline, { "m.relates_to": { rel_type: RelationType.Reference, event_id: eventId } } as never).catch(() => {});

/** Declines a ring, whichever kind it is. */
function declineEvent(room: Room, ev: MatrixEvent, mc?: MatrixCall) {
  if (ev.getType() !== EventType.CallInvite) return void sendDecline(room.roomId, ev.getId()!);
  if (mc) mc.reject();
  else void rejectInvite(room.roomId, ev);
}

export function decline() {
  const i = snap.incoming;
  if (!i) return;
  stopRinging();
  declineEvent(i.room, i.ev, i.mc);
}

export async function answer(video: boolean) {
  const i = snap.incoming;
  if (i?.mc) await answerLegacy(i.room, i.mc, video);
  else if (i) await call(i.room, video, false);
}

const LEGACY_ENDS: string[] = [EventType.CallHangup, EventType.CallReject, EventType.CallSelectAnswer, EventType.CallAnswer];

function onEvent(ev: MatrixEvent, room: Room) {
  const type = ev.getType();
  if (snap.active?.kind === "rtc" && snap.active.room === room) onCallReaction(ev);
  if (type === EventType.RTCNotification) return ring(room, ev);
  // legacy invites: the SDK hands us its call (Incoming below) when VoIP is on; the headless page only has the event
  if (type === EventType.CallInvite) return void (!client.callEventHandler && ringLegacy(room, ev));
  if (LEGACY_ENDS.includes(type)) { // headless: caller gave up, or another of our devices answered/declined
    const i = snap.incoming;
    const ends = type !== EventType.CallAnswer || ev.getSender() === client.getUserId(); // an answer only ends our ring if it's ours
    if (ends && i && !i.mc && i.ev.getType() === EventType.CallInvite && i.ev.getContent().call_id === ev.getContent().call_id) {
      stopRinging(type === EventType.CallHangup && ev.getSender() !== client.getUserId());
    }
    return;
  }
  if (type !== EventType.RTCDecline) return;
  const of = ev.getContent()["m.relates_to"]?.event_id;
  // declined on another of our devices
  if (ev.getSender() === client.getUserId() && snap.incoming?.ev.getId() === of) stopRinging();
  // the other side of our 1:1 declined
  const a = snap.active;
  if (ev.getSender() !== client.getUserId() && a?.room === room && !a.since && isDirect(room)) void hangup();
}

/** Listens for rings and declines. Returns an unsubscribe. */
export function startCalls() {
  const onTimeline = (ev: MatrixEvent, room: Room | undefined, toStart: boolean | undefined, _removed: boolean, data: IRoomTimelineData) => {
    if (toStart || !data?.liveEvent || !room || !pastFirstSync()) return;
    if (ev.getType() === EventType.RoomMessageEncrypted && !ev.isDecryptionFailure()) ev.once(MatrixEventEvent.Decrypted, () => onEvent(ev, room));
    else onEvent(ev, room);
  };
  const onIncoming = (mc: MatrixCall) => {
    const room = client.getRoom(mc.roomId), ev = room && inviteOf(room, mc.callId);
    if (room && ev) ringLegacy(room, ev, mc);
  };
  client.on(RoomEvent.Timeline, onTimeline);
  client.on(CallEventHandlerEvent.Incoming, onIncoming);
  return () => { client.off(RoomEvent.Timeline, onTimeline); client.off(CallEventHandlerEvent.Incoming, onIncoming); };
}

/** Buttons on Android's call notification. */
export async function onNativeCall(a: CallAction) {
  if (a.action === "hangup") return hangup();
  if (a.action === "hold" || a.action === "unhold") return hold(a.action === "hold");
  const room = client.getRoom(a.roomId);
  if (!room) return;
  const i = snap.incoming;
  if (i?.ev.getId() === a.eventId) return a.action === "decline" ? decline() : a.action === "answer" ? answer(a.video) : undefined;
  // not ringing here: the app was cold-started, or this is the headless page
  const ev = await loadEvent(room, a.eventId).catch(() => null);
  if (!ev) return;
  const legacy = ev.getType() === EventType.CallInvite;
  if (legacy && !legacyCallsOn()) return nativeCancelCall(room.roomId); // a ring shown before legacy calls were turned off
  const mc = legacy ? await legacyCallFor(room, ev) : undefined;
  if (a.action === "decline") return declineEvent(room, ev, mc);
  if (a.action === "answer") return legacy ? mc && answerLegacy(room, mc, a.video) : call(room, a.video, false);
  if (legacy) ringLegacy(room, ev, mc); // tapped: show the ring screen
  else ring(room, ev);
}
