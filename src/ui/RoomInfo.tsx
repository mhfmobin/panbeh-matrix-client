import { useCallback, useState } from "react";
import { ClientEvent, EventType, RoomEvent, RoomStateEvent, type MatrixEvent, type Room } from "matrix-js-sdk";
import { addToSpace, client, removeFromSpace, seenBy, setRoomAvatar } from "../matrix.ts";
import { usePresence, useTick } from "../hooks.ts";
import { JOIN_RULES, num, roleLabel, stamp } from "../logic.ts";
import { Icon } from "../icons.tsx";
import { Avatar, errText, me, RoomAvatar, roomAvatarMxc, Sheet } from "./common.tsx";
import { UserProfile } from "./Profile.tsx";
import { ChatForm, UserPicker } from "./NewChat.tsx";
import { isMuted, setMuted } from "../notify.ts";
import { PhotoViewer, SharedMedia } from "./Media.tsx";
import { BannedList, canManage, GroupSettings, KnockRequests } from "./Admin.tsx";
import { alertDialog, confirmDialog } from "./dialog.tsx";

type View = "main" | "invite" | "add" | "new-group" | "settings";

/** Chat/space info: profile, members, invite, space contents, leave. */
export function RoomInfo({ room, onClose }: { room: Room; onClose: () => void }) {
  useTick(client, [RoomStateEvent.Events, RoomStateEvent.Members, RoomEvent.Name, ClientEvent.Room, ClientEvent.AccountData]);
  const [view, setView] = useState<View>("main");
  const [busy, setBusy] = useState(false);
  const [profile, setProfile] = useState<string | null>(null);
  const [media, setMedia] = useState(false);
  const [viewing, setViewing] = useState(false);
  const closeViewer = useCallback(() => setViewing(false), []);
  const photo = roomAvatarMxc(room);
  const act = (fn: () => Promise<unknown>, then?: () => void) => {
    setBusy(true);
    fn().then(then, (e) => alertDialog(errText(e))).finally(() => setBusy(false));
  };

  const space = room.isSpaceRoom();
  const can = (type: string) => room.currentState.maySendStateEvent(type, me());
  const topic: string = room.currentState.getStateEvents(EventType.RoomTopic, "")?.getContent().topic ?? "";
  const members = room.getMembers().filter((m) => m.membership === "join" || m.membership === "invite")
    .sort((a, b) => +(a.membership === "invite") - +(b.membership === "invite") || b.powerLevel - a.powerLevel || a.name.localeCompare(b.name));
  const children = space
    ? room.currentState.getStateEvents(EventType.SpaceChild).filter((e) => Array.isArray(e.getContent().via)).map((e) => e.getStateKey()!)
    : [];
  const title = space ? "اطلاعات فضا" : "اطلاعات گفتگو";

  if (view === "invite") return (
    <Sheet title="دعوت اعضا" onClose={() => setView("main")}>
      <UserPicker exclude={members.map((m) => m.userId)} onPick={(p) => act(() => client.invite(room.roomId, p.userId), () => setView("main"))} />
    </Sheet>
  );
  if (view === "settings") return (
    <Sheet title={space ? "تنظیمات فضا" : "تنظیمات گروه"} onClose={() => setView("main")}>
      <GroupSettings room={room} />
    </Sheet>
  );
  if (view === "new-group") return (
    <Sheet title="گروه جدید" onClose={() => setView("main")}>
      <ChatForm kind="group" space={room.roomId} onDone={(id) => { onClose(); location.hash = id; }} />
    </Sheet>
  );
  if (view === "add") {
    const inSpace = new Set(children);
    const candidates = client.getRooms().filter((r) => r.getMyMembership() === "join" && !r.isSpaceRoom() && !inSpace.has(r.roomId));
    return (
      <Sheet title="افزودن گفتگو به فضا" onClose={() => setView("main")}>
        {candidates.length === 0 && <p className="muted">گفتگوی دیگری برای افزودن نیست</p>}
        {candidates.map((r) => (
          <button key={r.roomId} className="user-row" disabled={busy} onClick={() => act(() => addToSpace(room.roomId, r.roomId))}>
            <RoomAvatar room={r} size={42} /><span><b>{r.name}</b></span><Icon name="plus" />
          </button>
        ))}
      </Sheet>
    );
  }

  return (
    <Sheet title={title} onClose={onClose}>
      <div className="info-head">
        {photo ? (
          <button className="avatar-view" onClick={() => setViewing(true)} title="نمایش عکس" aria-label="نمایش عکس"><RoomAvatar room={room} size={96} /></button>
        ) : <RoomAvatar room={room} size={96} />}
        {viewing && photo && <PhotoViewer mxc={photo} name={room.name} onClose={closeViewer} />}
        {can(EventType.RoomAvatar) && (
          <span>
            <label className="photo-remove photo-change">
              <input type="file" accept="image/*" hidden disabled={busy}
                onChange={(e) => { const f = e.target.files?.[0]; if (f) act(() => setRoomAvatar(room.roomId, f)); }} />
              {room.getMxcAvatarUrl() ? "تغییر عکس" : "افزودن عکس"}
            </label>
            {room.getMxcAvatarUrl() && (
              <button className="photo-remove" disabled={busy} onClick={() => confirmDialog("عکس گفتگو حذف شود؟", { danger: true }).then((y) => y && act(() => client.sendStateEvent(room.roomId, EventType.RoomAvatar, {}, "")))}>حذف عکس</button>
            )}
          </span>
        )}
        {room.getCanonicalAlias() && <bdi dir="ltr" className="muted">{room.getCanonicalAlias()}</bdi>}
      </div>
      <Profile key={room.name + topic} room={room} topic={topic} editable={can(EventType.RoomName)} />
      {!space && (
        <label className="switch-row"><span>اعلان‌ها<small>{isMuted(room) ? "بی‌صدا؛ فقط وقتی از شما نام برده شود" : "روشن"}</small></span>
          <input type="checkbox" role="switch" checked={!isMuted(room)} disabled={busy} onChange={(e) => act(() => setMuted(room, !e.target.checked))} /></label>
      )}
      {!space && <button className="user-row" onClick={() => setMedia(true)}><span className="device-box"><Icon name="file" /></span><span><b>رسانه‌ها، فایل‌ها و پیوندها</b></span></button>}
      {canManage(room) && (
        <button className="user-row" onClick={() => setView("settings")}>
          <span className="device-box"><Icon name="settings" /></span>
          <span><b>{space ? "تنظیمات فضا" : "تنظیمات گروه"}</b><small>{JOIN_RULES[room.getJoinRule()] ?? room.getJoinRule()}</small></span>
        </button>
      )}

      {space && (
        <>
          <h3>گفتگوهای این فضا ({num(children.length)})</h3>
          {can(EventType.SpaceChild) && (
            <div className="row-actions">
              <button onClick={() => setView("add")}><Icon name="plus" /> افزودن گفتگو</button>
              <button onClick={() => setView("new-group")}><Icon name="group" /> گروه جدید</button>
            </div>
          )}
          {children.map((id) => {
            const r = client.getRoom(id);
            return (
              <div key={id} className="user-row">
                {r ? <RoomAvatar room={r} size={42} /> : <Avatar name={id} id={id} size={42} />}
                <span><b>{r?.name ?? id}</b>{!r && <small>عضو نیستید</small>}</span>
                {can(EventType.SpaceChild) && (
                  <button className="icon-btn" disabled={busy} title="حذف از فضا" aria-label="حذف از فضا"
                    onClick={() => act(() => removeFromSpace(room.roomId, id))}><Icon name="close" size={18} /></button>
                )}
              </div>
            );
          })}
        </>
      )}

      <KnockRequests room={room} onUser={setProfile} />
      <h3>اعضا ({num(room.getJoinedMemberCount())})</h3>
      {room.canInvite(me()) && <div className="row-actions"><button onClick={() => setView("invite")}><Icon name="plus" /> دعوت عضو</button></div>}
      {members.map((m) => (
        <button key={m.userId} className="user-row" onClick={() => setProfile(m.userId)}>
          <Avatar mxc={m.getMxcAvatarUrl()} name={m.name} id={m.userId} size={42} />
          <span><b>{m.name}</b><small dir="ltr">{m.userId}</small><LastSeen userId={m.userId} /></span>
          {m.membership === "invite" ? <em className="muted">دعوت‌شده</em> : roleLabel(m.powerLevel) && <em>{roleLabel(m.powerLevel)}</em>}
        </button>
      ))}
      <BannedList room={room} onUser={setProfile} />

      <button className="danger" disabled={busy}
        onClick={() => confirmDialog(space ? "از این فضا خارج می‌شوید؟" : "از این گفتگو خارج می‌شوید؟", { danger: true }).then((y) => y && act(() => client.leave(room.roomId), () => {
          if (location.hash.slice(1) === room.roomId) location.hash = "";
          onClose();
        }))}>
        {space ? "خروج از فضا" : "خروج از گفتگو"}
      </button>
      {profile && <UserProfile userId={profile} room={room} onClose={() => setProfile(null)} onOpened={onClose} />}
      {media && <SharedMedia room={room} onClose={() => setMedia(false)} onJump={onClose} />}
    </Sheet>
  );
}

/** Name + topic; editable fields save only what changed. Remounted (key) when the room's values change. */
function Profile({ room, topic, editable }: { room: Room; topic: string; editable: boolean }) {
  // the explicit name, not room.name: that one is computed from members when there's none
  const current: string = room.currentState.getStateEvents(EventType.RoomName, "")?.getContent().name ?? "";
  const [name, setName] = useState(current);
  const [newTopic, setTopic] = useState(topic);
  const [busy, setBusy] = useState(false);
  if (!editable) return <div className="info-text"><h2>{room.name}</h2>{topic && <p>{topic}</p>}</div>;
  const dirty = name.trim() !== current || newTopic.trim() !== topic;
  const save = async () => {
    setBusy(true);
    try {
      if (name.trim() !== current) await client.setRoomName(room.roomId, name.trim()); // "" removes it
      if (newTopic.trim() !== topic) await client.setRoomTopic(room.roomId, newTopic.trim());
    } catch (e) {
      alertDialog(errText(e));
    }
    setBusy(false);
  };
  return (
    <form className="form" onSubmit={(e) => { e.preventDefault(); save(); }}>
      <input value={name} onChange={(e) => setName(e.target.value)} placeholder={current ? "نام" : room.name} aria-label="نام" />
      <input value={newTopic} onChange={(e) => setTopic(e.target.value)} placeholder="توضیحات" aria-label="توضیحات" />
      {dirty && <button className="primary" disabled={busy}>ذخیره</button>}
    </form>
  );
}

/** Who has read one of my messages, and when. */
export function SeenBy({ room, ev, onClose }: { room: Room; ev: MatrixEvent; onClose: () => void }) {
  useTick(client, [RoomEvent.Receipt]);
  const seen = seenBy(room, ev);
  const [profile, setProfile] = useState<string | null>(null);
  return (
    <Sheet title="دیده‌شده توسط" onClose={onClose}>
      {seen.length === 0 && <p className="muted">هنوز کسی ندیده</p>}
      {seen.map(({ userId, member, ts }) => (
        <button key={userId} className="user-row" onClick={() => setProfile(userId)}>
          <Avatar mxc={member?.getMxcAvatarUrl()} name={member?.name ?? userId} id={userId} size={42} />
          <span><b>{member?.name ?? userId}</b>{ts > 0 && <small>{stamp(ts)}</small>}</span>
          <Icon name="checks" size={18} />
        </button>
      ))}
      {profile && <UserProfile userId={profile} room={room} onClose={() => setProfile(null)} onOpened={onClose} />}
    </Sheet>
  );
}

/** Member-row last seen (cached /presence lookups). */
function LastSeen({ userId }: { userId: string }) {
  const { text, online } = usePresence(userId === me() ? undefined : userId);
  return text ? <small className={online ? "typing" : undefined}>{text}</small> : null;
}
