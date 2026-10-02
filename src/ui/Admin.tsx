import { useEffect, useState, type ReactNode } from "react";
import { EventTimeline, EventType, RoomEvent, RoomStateEvent, Visibility, type Room } from "matrix-js-sdk";
import { client } from "../matrix.ts";
import { useTick } from "../hooks.ts";
import { aliasLocalpart, HISTORY, JOIN_RULES, num, roleLabel, supportsKnock } from "../logic.ts";
import { Icon } from "../icons.tsx";
import { Avatar, errText, me } from "./common.tsx";

const STATE = [RoomStateEvent.Events, RoomStateEvent.Members];

/** busy flag + run-and-alert, as in RoomInfo */
function useAct() {
  const [busy, setBusy] = useState(false);
  const act = (fn: () => Promise<unknown>, then?: () => void) => {
    setBusy(true);
    fn().then(then, (e) => alert(errText(e))).finally(() => setBusy(false));
  };
  return [busy, act] as const;
}

type Levels = { users?: Record<string, number>; users_default?: number; kick?: number; ban?: number };
const levels = (room: Room): Levels => room.currentState.getStateEvents(EventType.RoomPowerLevels, "")?.getContent() ?? {};
/** The member's level as the SDK computes it: from room v12 on, creators aren't in `users` and outrank everyone (Infinity). */
const levelOf = (room: Room, id: string) => {
  const pl = levels(room);
  return room.getMember(id)?.powerLevel ?? pl.users?.[id] ?? pl.users_default ?? 0;
};

/** null on cancel, undefined for an empty reason */
const askReason = (q: string) => { const r = prompt(q); return r === null ? null : r.trim() || undefined; };

// ---------- one member: role, kick, ban ----------

/** Admin actions on someone I outrank; nothing otherwise. */
export function MemberAdmin({ room, userId }: { room: Room; userId: string }) {
  useTick(client, STATE);
  const [busy, act] = useAct();
  if (userId === me() || room.getMyMembership() !== "join") return null;
  const pl = levels(room), mine = levelOf(room, me()), theirs = levelOf(room, userId);
  if (theirs >= mine) return null;
  const membership = room.getMember(userId)?.membership;
  const mayKick = mine >= (pl.kick ?? 50) && (membership === "join" || membership === "invite");
  const mayBan = mine >= (pl.ban ?? 50);
  const mayRole = membership !== "ban" && room.currentState.maySendStateEvent(EventType.RoomPowerLevels, me());
  if (!mayKick && !mayBan && !mayRole) return null;

  const roles: [number, string][] = ([[0, "عضو"], [50, "ناظر"], [100, "مدیر"]] as [number, string][]).filter(([l]) => l <= mine);
  if (!roles.some(([l]) => l === theirs)) roles.push([theirs, roleLabel(theirs) ?? num(theirs)]);
  roles.sort((a, b) => a[0] - b[0]);
  const setRole = (l: number) => {
    // same level as mine can't be undone by me
    if (l >= mine && !confirm("هم‌سطح شما می‌شود و دیگر نمی‌توانید نقشش را تغییر دهید یا بیرونش کنید. ادامه می‌دهید؟")) return;
    act(() => client.setPowerLevel(room.roomId, userId, l));
  };
  const kick = () => {
    const r = askReason(membership === "invite" ? "دعوت لغو شود؟ دلیل (اختیاری):" : "از گفتگو بیرون شود؟ دلیل (اختیاری):");
    if (r !== null) act(() => client.kick(room.roomId, userId, r));
  };
  const ban = () => {
    const r = askReason("مسدود شود؟ دیگر نمی‌تواند بپیوندد. دلیل (اختیاری):");
    if (r !== null) act(() => client.ban(room.roomId, userId, r));
  };

  return (
    <div className="admin">
      <h3>مدیریت در این گفتگو</h3>
      {mayRole && (
        <label className="select-row">نقش
          <select value={theirs} disabled={busy} onChange={(e) => setRole(+e.target.value)}>
            {roles.map(([l, label]) => <option key={l} value={l}>{label}</option>)}
          </select>
        </label>
      )}
      <div className="row-actions">
        {mayKick && <button className="warn" disabled={busy} onClick={kick}>{membership === "invite" ? "لغو دعوت" : "بیرون کردن"}</button>}
        {mayBan && (membership === "ban"
          ? <button disabled={busy} onClick={() => act(() => client.unban(room.roomId, userId))}>رفع مسدودیت در گروه</button>
          : <button className="warn" disabled={busy} onClick={ban}>مسدود در این گروه</button>)}
      </div>
    </div>
  );
}

// ---------- RoomInfo lists: knocks and bans ----------

export function KnockRequests({ room, onUser }: { room: Room; onUser: (id: string) => void }) {
  useTick(client, STATE);
  const [busy, act] = useAct();
  const knocks = room.getMembersWithMembership("knock");
  if (!knocks.length || !room.canInvite(me())) return null;
  const mayDeny = levelOf(room, me()) >= (levels(room).kick ?? 50);
  return (
    <>
      <h3>درخواست‌های عضویت ({num(knocks.length)})</h3>
      {knocks.map((m) => (
        <div key={m.userId} className="user-row">
          <button className="plain" onClick={() => onUser(m.userId)} aria-label={m.name}><Avatar mxc={m.getMxcAvatarUrl()} name={m.name} id={m.userId} size={42} /></button>
          <span><b>{m.name}</b><small dir="auto">{m.events.member?.getContent().reason || m.userId}</small></span>
          {mayDeny && <button className="icon-btn" disabled={busy} title="رد" aria-label="رد" onClick={() => act(() => client.kick(room.roomId, m.userId))}><Icon name="close" size={18} /></button>}
          <button className="icon-btn accent" disabled={busy} title="پذیرفتن" aria-label="پذیرفتن" onClick={() => act(() => client.invite(room.roomId, m.userId))}><Icon name="check" size={18} /></button>
        </div>
      ))}
    </>
  );
}

export function BannedList({ room, onUser }: { room: Room; onUser: (id: string) => void }) {
  useTick(client, STATE);
  const banned = room.getMembersWithMembership("ban");
  if (!banned.length) return null;
  return (
    <>
      <h3>مسدودشده‌ها ({num(banned.length)})</h3>
      {/* unban lives in the profile's MemberAdmin, next to the reason */}
      {banned.map((m) => (
        <button key={m.userId} className="user-row" onClick={() => onUser(m.userId)}>
          <Avatar mxc={m.getMxcAvatarUrl()} name={m.name} id={m.userId} size={42} />
          <span><b>{m.name}</b><small>{m.events.member?.getContent().reason || <bdi dir="ltr">{m.userId}</bdi>}</small></span>
        </button>
      ))}
    </>
  );
}

// ---------- group settings ----------

/** Anything in GroupSettings I'm allowed to change. */
export function canManage(room: Room) {
  const can = (t: string) => room.currentState.maySendStateEvent(t, me());
  return can(EventType.RoomJoinRules) || can(EventType.RoomCanonicalAlias)
    || (!room.isSpaceRoom() && (can(EventType.RoomHistoryVisibility) || (can(EventType.RoomEncryption) && !room.hasEncryptionStateEvent())));
}

export function GroupSettings({ room }: { room: Room }) {
  useTick(client, STATE);
  const [busy, act] = useAct();
  const can = (t: string) => room.currentState.maySendStateEvent(t, me());
  const send = (type: string, content: object) => act(() => client.sendStateEvent(room.roomId, type as never, content as never, ""));
  const space = room.isSpaceRoom();
  const rule = room.getJoinRule();
  const rules = ["public", "invite", ...(supportsKnock(room.getVersion()) ? ["knock"] : [])];
  if (!rules.includes(rule)) rules.push(rule);
  const history = room.getHistoryVisibility();

  return (
    <div className="form">
      {can(EventType.RoomJoinRules) && (
        <label className="select-row">پیوستن
          <select value={rule} disabled={busy} onChange={(e) => send(EventType.RoomJoinRules, { join_rule: e.target.value })}>
            {rules.map((r) => <option key={r} value={r}>{JOIN_RULES[r] ?? r}</option>)}
          </select>
        </label>
      )}
      {!space && can(EventType.RoomHistoryVisibility) && (
        <label className="select-row">چه کسی تاریخچه را می‌بیند
          <select value={history} disabled={busy} onChange={(e) => send(EventType.RoomHistoryVisibility, { history_visibility: e.target.value })}>
            {Object.entries(HISTORY).map(([v, label]) => <option key={v} value={v}>{label}</option>)}
          </select>
        </label>
      )}
      {!space && (room.hasEncryptionStateEvent()
        ? <p className="card ok"><Icon name="lock" size={16} /> رمزنگاری سرتاسری روشن است</p>
        : can(EventType.RoomEncryption) && (
          <div className="row-actions">
            <button disabled={busy} onClick={() => confirm("رمزنگاری سرتاسری پس از روشن شدن خاموش نمی‌شود و جستجوی سرور و ربات‌ها شاید دیگر کار نکنند. روشن شود؟")
              && send(EventType.RoomEncryption, { algorithm: "m.megolm.v1.aes-sha2" })}>
              <Icon name="lock" size={16} /> روشن کردن رمزنگاری سرتاسری
            </button>
          </div>
        ))}
      {can(EventType.RoomCanonicalAlias) && <Address room={room} />}
    </div>
  );
}

/** Canonical #alias (create/remove) and listing in the server's public directory. */
function Address({ room }: { room: Room }) {
  const [busy, act] = useAct();
  const alias = room.getCanonicalAlias();
  const [lp, setLp] = useState(() => aliasLocalpart(room.name));
  const [listed, setListed] = useState<boolean | null>(null);
  useEffect(() => {
    client.getRoomDirectoryVisibility(room.roomId).then((r) => setListed(r.visibility === Visibility.Public), () => {});
  }, [room.roomId]);
  const content = () => room.currentState.getStateEvents(EventType.RoomCanonicalAlias, "")?.getContent() ?? {};
  const setCanonical = (a?: string) => {
    const { alias: _, ...rest } = content();
    return client.sendStateEvent(room.roomId, EventType.RoomCanonicalAlias, (a ? { ...rest, alias: a } : rest) as never, "");
  };
  const add = async () => {
    const a = `#${lp}:${client.getDomain()}`;
    try {
      await client.createAlias(a, room.roomId);
    } catch (e) {
      if ((e as { errcode?: string }).errcode === "M_ROOM_IN_USE") throw new Error("این نشانی قبلاً گرفته شده است");
      throw e;
    }
    await setCanonical(a);
  };
  const remove = async () => {
    await setCanonical();
    await client.deleteAlias(alias!).catch(() => {}); // may belong to someone else or another server
  };

  return (
    <>
      <h3>نشانی عمومی</h3>
      {alias ? (
        <div className="user-row">
          <span className="device-box"><Icon name="link" /></span>
          <span><b dir="ltr">{alias}</b></span>
          <button className="icon-btn" disabled={busy} title="حذف نشانی" aria-label="حذف نشانی" onClick={() => confirm("نشانی حذف شود؟") && act(remove)}><Icon name="trash" size={18} /></button>
        </div>
      ) : (
        <form className="alias-input" dir="ltr" onSubmit={(e) => { e.preventDefault(); act(add); }}>
          <span>#</span>
          <input value={lp} onChange={(e) => setLp(e.target.value.replace(/[\s:#]/g, ""))} placeholder="address" required />
          <span>:{client.getDomain()}</span>
          <button className="accent-btn" disabled={busy}>ثبت</button>
        </form>
      )}
      {listed !== null && (
        <label className="switch-row"><span>در فهرست گفتگوهای عمومی<small>دیگران با جستجو پیدایش می‌کنند</small></span>
          <input type="checkbox" role="switch" checked={listed} disabled={busy}
            onChange={(e) => { const on = e.target.checked; act(() => client.setRoomDirectoryVisibility(room.roomId, on ? Visibility.Public : Visibility.Private), () => setListed(on)); }} /></label>
      )}
    </>
  );
}

// ---------- room upgrades ----------

/** A tombstoned room takes no messages: the composer becomes a link to the new room. */
export function Upgraded({ room, children }: { room: Room; children: ReactNode }) {
  useTick(client, [RoomStateEvent.Events]);
  const [busy, act] = useAct();
  const ev = room.currentState.getStateEvents(EventType.RoomTombstone, "");
  if (!ev) return children;
  const to: string | undefined = ev.getContent().replacement_room;
  const go = async () => {
    // the upgrader's server is in the new room for sure
    if (client.getRoom(to!)?.getMyMembership() !== "join") await client.joinRoom(to!, { viaServers: [ev.getSender()!.replace(/^[^:]*:/, "")] });
    location.hash = to!;
  };
  return (
    <div className="upgraded">
      <span>این گروه ارتقا یافته و دیگر پیامی در آن فرستاده نمی‌شود</span>
      {to && <button className="primary" disabled={busy} onClick={() => act(go)}>رفتن به گروه جدید</button>}
    </div>
  );
}

/** Link back to the room this one replaced, once its start is reached. */
export function Predecessor({ room }: { room: Room }) {
  useTick(client, [RoomEvent.Timeline, RoomEvent.TimelineReset]);
  const pred = room.findPredecessor();
  const old = pred && client.getRoom(pred.roomId);
  // ponytail: only rooms we're still in; joining a left predecessor needs via servers we may not have
  if (!old || old.getMyMembership() !== "join" || room.getLiveTimeline().getPaginationToken(EventTimeline.BACKWARDS)) return null;
  return <button className="predecessor" onClick={() => { location.hash = old.roomId; }}>ادامه‌ی گروه قبلی؛ دیدن پیام‌های قدیمی‌تر</button>;
}
