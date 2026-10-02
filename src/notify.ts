import { EventType, MatrixEventEvent, PushRuleActionName, PushRuleKind, RoomEvent, type IRoomTimelineData, type MatrixEvent, type Room } from "matrix-js-sdk";
import { avatarUrl, client } from "./matrix.ts";
import { isGroupChat, previewText, senderName, roomAvatarMxc } from "./ui/common.tsx";
import { loadPrefs } from "./ui/Settings.tsx";

/** Muted = a room or override rule for this room that doesn't notify (ours, or Element's). */
export function isMuted(room: Room) {
  const g = client.pushRules?.global;
  const mutes = (r?: { rule_id: string; enabled: boolean; actions: unknown[] }) => !!r?.enabled && !r.actions.includes(PushRuleActionName.Notify);
  return mutes(g?.[PushRuleKind.RoomSpecific]?.find((r) => r.rule_id === room.roomId))
    || mutes(g?.[PushRuleKind.Override]?.find((r) => r.rule_id === room.roomId));
}

export const setMuted = (room: Room, mute: boolean) => client.setRoomMutePushRule("global", room.roomId, mute) ?? Promise.resolve();

const shown = new Set<string>();

/** Desktop notifications for new messages while the app is open (any tab state). Returns an unsubscribe. */
export function startNotifications() {
  const onTimeline = (ev: MatrixEvent, room: Room | undefined, toStart: boolean | undefined, _removed: boolean, data: IRoomTimelineData) => {
    if (toStart || !data?.liveEvent || !room || !client.isInitialSyncComplete() || ev.getSender() === client.getUserId() || client.isUserIgnored(ev.getSender()!)) return;
    // push rules need the decrypted type and body
    if (ev.getType() === EventType.RoomMessageEncrypted && !ev.isDecryptionFailure()) ev.once(MatrixEventEvent.Decrypted, () => notify(ev, room));
    else notify(ev, room);
  };
  client.on(RoomEvent.Timeline, onTimeline);
  return () => { client.off(RoomEvent.Timeline, onTimeline); };
}

async function notify(ev: MatrixEvent, room: Room) {
  const id = ev.getId()!;
  const prefs = loadPrefs();
  if (!prefs.notify || !("Notification" in window) || Notification.permission !== "granted" || shown.has(id)) return;
  const actions = client.getPushActionsForEvent(ev, true); // server push rules: mute, mentions, keywords, edits…
  if (!actions?.notify) return;
  // the DM/group switches silence ordinary messages only; mentions and keywords still come through
  if (!actions.tweaks?.highlight && !(isGroupChat(room) ? prefs.notifyGroups : prefs.notifyDMs)) return;
  if (document.hasFocus() && location.hash.slice(1) === room.roomId) return; // already looking at it
  shown.add(id);
  // the sync recalculates names only after emitting the batch's events: a member that names this room may have just arrived
  room.recalculate();
  const icon = await Promise.race([avatarUrl(roomAvatarMxc(room), 96)?.catch(() => undefined),
    new Promise<undefined>((r) => setTimeout(r, 1500))]);
  const n = new Notification(room.name, {
    body: (isGroupChat(room) ? senderName(ev) + ": " : "") + previewText(ev),
    tag: room.roomId, // newest message per chat replaces the previous one
    icon, lang: "fa", dir: "rtl",
  });
  n.onclick = () => { focus(); location.hash = room.roomId; n.close(); };
  if (actions.tweaks?.sound) ding();
}

let ctx: AudioContext | undefined;
/** Short beep; no sound file to ship. */
function ding() {
  try {
    ctx ??= new AudioContext();
    const o = ctx.createOscillator(), g = ctx.createGain(), t = ctx.currentTime;
    o.frequency.value = 880;
    g.gain.setValueAtTime(0.15, t);
    g.gain.exponentialRampToValueAtTime(0.001, t + 0.35);
    o.connect(g).connect(ctx.destination);
    o.start();
    o.stop(t + 0.35);
  } catch { /* no audio: the notification is enough */ }
}
