import { EventType, MatrixEventEvent, PushRuleActionName, PushRuleKind, ReceiptType, RoomEvent, type IRoomTimelineData, type MatrixEvent, type Room } from "matrix-js-sdk";
import { avatarUrl, client, pastFirstSync } from "./matrix.ts";
import { isGroupChat, previewText, senderName, roomAvatarMxc } from "./ui/common.tsx";
import { loadPrefs } from "./ui/Settings.tsx";
import { isHeadless, isNative, nativeCancel, nativeNotify, toDataUrl } from "./native.ts";
import { showWindow } from "./desktop.ts";

/** Muted = a room or override rule for this room that doesn't notify (ours, or Element's). */
export function isMuted(room: Room) {
  const g = client.pushRules?.global;
  const mutes = (r?: { rule_id: string; enabled: boolean; actions: unknown[] }) => !!r?.enabled && !r.actions.includes(PushRuleActionName.Notify);
  return mutes(g?.[PushRuleKind.RoomSpecific]?.find((r) => r.rule_id === room.roomId))
    || mutes(g?.[PushRuleKind.Override]?.find((r) => r.rule_id === room.roomId));
}

export const setMuted = (room: Room, mute: boolean) => client.setRoomMutePushRule("global", room.roomId, mute) ?? Promise.resolve();

const shown = new Set<string>();

/** Notifications for new messages: desktop ones while the tab is open, Android ones in the app. Returns an unsubscribe. */
export function startNotifications() {
  const onTimeline = (ev: MatrixEvent, room: Room | undefined, toStart: boolean | undefined, _removed: boolean, data: IRoomTimelineData) => {
    if (toStart || !data?.liveEvent || !room || !pastFirstSync() || ev.getSender() === client.getUserId() || client.isUserIgnored(ev.getSender()!)) return;
    // push rules need the decrypted type and body
    if (ev.getType() === EventType.RoomMessageEncrypted && !ev.isDecryptionFailure()) ev.once(MatrixEventEvent.Decrypted, () => notify(ev, room));
    else notify(ev, room);
  };
  client.on(RoomEvent.Timeline, onTimeline);
  if (!isNative) return () => { client.off(RoomEvent.Timeline, onTimeline); };
  // Android notifications stay in the shade: clear a chat's once it's read, here or on another device
  const onReceipt = (ev: MatrixEvent, room: Room) => {
    const mine = Object.values(ev.getContent()).some((r) => [ReceiptType.Read, ReceiptType.ReadPrivate].some((t) => r?.[t]?.[client.getUserId()!]));
    if (mine && room.getUnreadNotificationCount() === 0) nativeCancel(room.roomId);
  };
  const onHash = () => { if (location.hash.length > 1) nativeCancel(location.hash.slice(1)); };
  client.on(RoomEvent.Receipt, onReceipt);
  addEventListener("hashchange", onHash);
  return () => { client.off(RoomEvent.Timeline, onTimeline); client.off(RoomEvent.Receipt, onReceipt); removeEventListener("hashchange", onHash); };
}

async function notify(ev: MatrixEvent, room: Room) {
  if (/^m\.call\.|\.rtc\./.test(ev.getType())) return; // call.ts rings for calls (and .m.rule.call would turn every invite into a message)
  const id = ev.getId()!;
  const prefs = loadPrefs();
  if (!prefs.notify || shown.has(id)) return;
  if (!isNative && (!("Notification" in window) || Notification.permission !== "granted")) return;
  const actions = client.getPushActionsForEvent(ev, true); // server push rules: mute, mentions, keywords, edits…
  if (!actions?.notify) return;
  // the DM/group switches silence ordinary messages only; mentions and keywords still come through
  if (!actions.tweaks?.highlight && !(isGroupChat(room) ? prefs.notifyGroups : prefs.notifyDMs)) return;
  shown.add(id);
  await show(room, (isGroupChat(room) ? senderName(ev) + ": " : "") + previewText(ev), !!actions.tweaks?.sound);
}

/** A notification about a call that didn't ring (missed, or a group call starting). group: obeys the group switch. */
export async function callNotice(room: Room, body: string, group = false) {
  const prefs = loadPrefs();
  if (!prefs.notify || (group && !prefs.notifyGroups)) return;
  if (!isNative && (!("Notification" in window) || Notification.permission !== "granted")) return;
  await show(room, body, true);
}

async function show(room: Room, body: string, sound: boolean) {
  // already looking at it (in the app, native checks the activity is on screen)
  if (!isNative && document.hasFocus() && location.hash.slice(1) === room.roomId) return;
  // the sync recalculates names only after emitting the batch's events: a member that names this room may have just arrived
  room.recalculate();
  const icon = await Promise.race([avatarUrl(roomAvatarMxc(room), 96)?.catch(() => undefined),
    new Promise<undefined>((r) => setTimeout(r, 1500))]);
  if (isNative) {
    nativeNotify({
      roomId: room.roomId, title: room.name, body, sound,
      icon: icon && await toDataUrl(icon).catch(() => undefined),
      openRoom: isHeadless ? undefined : location.hash.slice(1),
    });
    return;
  }
  const n = new Notification(room.name, {
    body,
    tag: room.roomId, // newest message per chat replaces the previous one
    icon, lang: "fa", dir: "rtl",
  });
  n.onclick = () => { showWindow(); location.hash = room.roomId; n.close(); };
  if (sound) ding();
}

let ctx: AudioContext | undefined;
/** Short beep; no sound file to ship. */
const ding = () => beep(880, 0.35);

function beep(freq: number, len: number, after = 0) {
  try {
    ctx ??= new AudioContext();
    const o = ctx.createOscillator(), g = ctx.createGain(), t = ctx.currentTime + after;
    o.frequency.value = freq;
    g.gain.setValueAtTime(0.15, t);
    g.gain.exponentialRampToValueAtTime(0.001, t + len);
    o.connect(g).connect(ctx.destination);
    o.start(t);
    o.stop(t + len);
  } catch { /* no audio: the notification is enough */ }
}

/** Incoming-call ring (two tones every 2s) until the returned stop is called. */
/** Call waiting: a soft double beep every 3s, over the call we're in. */
export function waitingTone() {
  const ring = () => { beep(440, 0.15); beep(440, 0.15, 0.3); };
  ring();
  const t = setInterval(ring, 3000);
  return () => clearInterval(t);
}

export function ringtone() {
  const ring = () => { beep(440, 0.4); beep(480, 0.4, 0.5); };
  ring();
  const t = setInterval(ring, 2000);
  return () => clearInterval(t);
}
