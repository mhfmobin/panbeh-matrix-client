import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { KnownMembership, RoomMemberEvent, type MatrixEvent, type Room as SdkRoom } from "matrix-js-sdk";
import { addDirect, client, dmPeer, isDirect, loadEvent } from "../matrix.ts";
import { usePresence, useTick } from "../hooks.ts";
import { Icon } from "../icons.tsx";
import { num, stamp } from "../logic.ts";
import { bdi, errText, me, RoomAvatar, senderName } from "./common.tsx";
import { Timeline, type Jumper } from "./Timeline.tsx";
import { PinnedBar } from "./Pinned.tsx";
import { NowPlaying } from "./Voice.tsx";
import { CallBar, CallButtons } from "./Call.tsx";
import { Composer, DropZone, type Mode } from "./Composer.tsx";
import { copyMessages, copyTextOf, type Actions } from "./Message.tsx";
import { RoomInfo, SeenBy } from "./RoomInfo.tsx";
import { UserProfile } from "./Profile.tsx";
import { ForwardSheet } from "./Forward.tsx";
import { pendingJump, SearchSheet } from "./Search.tsx";
import { MediaViewer, timelineMedia } from "./Media.tsx";
import { Predecessor, Upgraded } from "./Admin.tsx";
import { alertDialog, confirmDialog } from "./dialog.tsx";

export function Room({ room, onBack }: { room: SdkRoom; onBack: () => void }) {
  useTick(client, [RoomMemberEvent.Typing, "Room.myMembership", "Room.name"]);
  const seen = usePresence(isDirect(room) && room.getMyMembership() === KnownMembership.Join ? dmPeer(room) : undefined);
  // lazy-loaded sync only has members who spoke recently; thread replies (/relations) bring none, so senders showed as IDs
  // ponytail: whole member list per opened room; fetch single profiles instead if huge rooms get slow
  useEffect(() => { room.loadMembersIfNeeded().catch(() => {}); }, [room]);
  const [mode, setMode] = useState<Mode>(null);
  const [files, setFiles] = useState<File[]>([]);
  const [threadId, setThreadId] = useState<string | null>(null);
  const [viewing, setViewing] = useState<MatrixEvent | null>(null);
  const [info, setInfo] = useState(false);
  const [seenFor, setSeenFor] = useState<MatrixEvent | null>(null);
  const [profile, setProfile] = useState<string | null>(null);
  const [forwarding, setForwarding] = useState<MatrixEvent[] | null>(null);
  const [sel, setSel] = useState<ReadonlySet<string> | null>(null); // multi-select: event ids of the main timeline
  const forward1 = useCallback((ev: MatrixEvent) => setForwarding([ev]), []);
  const [search, setSearch] = useState(false);

  const jumper = useRef<Jumper>(null);
  useEffect(() => { // Ctrl+F (Sidebar's shortcuts)
    const open = () => { if (room.getMyMembership() !== KnownMembership.Invite) setSearch(true); };
    addEventListener("panbeh-search", open);
    return () => removeEventListener("panbeh-search", open);
  }, [room]);

  useEffect(() => { // Escape leaves selection mode (not while the forward sheet is open: that closes first)
    if (!sel || forwarding) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") { e.preventDefault(); setSel(null); } };
    addEventListener("keydown", onKey);
    return () => removeEventListener("keydown", onKey);
  }, [!!sel, !!forwarding]); // eslint-disable-line react-hooks/exhaustive-deps

  const actions: Actions = useMemo(() => ({
    reply: (ev: MatrixEvent) => setMode({ kind: "reply", ev }),
    edit: (ev: MatrixEvent) => setMode({ kind: "edit", ev }),
    thread: (ev: MatrixEvent) => {
      if (!room.getThread(ev.getId()!)) room.createThread(ev.getId()!, ev, [], true);
      setThreadId(ev.getId()!);
    },
    view: setViewing,
    info: setSeenFor,
    profile: setProfile,
    forward: forward1,
    jump: (id: string) => void searchJump(id),
    select: (ev: MatrixEvent) => setSel((s) => { // toggle; empty = leave selection mode
      const n = new Set(s);
      if (!n.delete(ev.getId()!)) n.add(ev.getId()!);
      return n.size ? n : null;
    }),
    selection: sel ?? undefined,
  }), [room, sel]); // eslint-disable-line react-hooks/exhaustive-deps

  const picked = sel ? room.getLiveTimeline().getEvents().filter((e) => sel.has(e.getId()!)) : []; // timeline order
  const copySel = () => {
    const text = picked.map((e) => [e, copyTextOf(e)] as const).filter(([, t]) => t)
      .map(([e, t]) => (picked.length === 1 ? t : `${senderName(e)}, [${stamp(e.getTs())}]:\n${t}`)).join("\n\n");
    if (text) void copyMessages(text);
    setSel(null);
  };
  const canDel = picked.length > 0 && picked.every((e) => e.getSender() === me() || room.currentState.maySendRedactionForEvent(e, me()));
  const delSel = async () => {
    if (!(await confirmDialog(`${num(picked.length)} پیام برای همه حذف شود؟`, { danger: true }))) return;
    Promise.all(picked.map((e) => client.redactEvent(room.roomId, e.getId()!))).catch((e) => alertDialog(errText(e)));
    setSel(null);
  };

  const typing = room.getMembers().filter((m) => m.typing && m.userId !== me()).map((m) => bdi(m.name.split(" ")[0]));
  const subtitle = typing.length
    ? `${typing.length > 2 ? `${typing.slice(0, 2).join("، ")} و ${num(typing.length - 2)} نفر دیگر` : typing.join(" و ")} در حال نوشتن…`
    : room.getMyMembership() === KnownMembership.Invite ? "دعوت‌نامه" // invites carry no member counts
    : seen.text ?? `${num(room.getJoinedMemberCount())} عضو`;
  const thread = threadId ? room.getThread(threadId) : null;

  async function jump(id: string, maxPages?: number) {
    const ev = await loadEvent(room, id).catch(() => null);
    const root = ev?.threadRootId;
    if (root && root !== id) { // thread replies aren't in the main timeline: open their thread
      const rootEv = await loadEvent(room, root).catch(() => null);
      if (!rootEv) return false;
      actions.thread!(rootEv);
      return true;
    }
    return jumper.current?.(id, maxPages) ?? false;
  }

  const searchJump = async (id: string, maxPages?: number) => { if (!(await jump(id, maxPages))) alertDialog("این پیام خیلی قدیمی است"); };
  // a result picked in the sidebar: on mount (room just opened) and via event (room already open)
  const jumpRef = useRef(searchJump);
  jumpRef.current = searchJump;
  useEffect(() => {
    const take = async () => {
      const p = pendingJump.current;
      if (p?.roomId !== room.roomId) return;
      pendingJump.current = null;
      for (let i = 0; i < 10 && !jumper.current; i++) await new Promise((r) => setTimeout(r, 100)); // Timeline not rendered yet
      jumpRef.current(p.eventId, 50);
    };
    take();
    window.addEventListener("panbeh-jump", take);
    return () => window.removeEventListener("panbeh-jump", take);
  }, [room.roomId]);

  return (
    <section className={"room" + (thread ? " with-thread" : "") + (sel ? " selecting" : "")}>
      <div className="room-main">
        {sel ? (
          <header className="room-head select-bar" role="toolbar" aria-label="انتخاب پیام‌ها">
            <button className="icon-btn" onClick={() => setSel(null)} title="لغو انتخاب" aria-label="لغو انتخاب"><Icon name="close" /></button>
            <b className="select-count">{num(sel.size)} پیام انتخاب شد</b>
            <button className="icon-btn" onClick={copySel} title="کپی" aria-label="کپی"><Icon name="copy" /></button>
            <button className="icon-btn" onClick={() => setForwarding(picked)} title="هدایت" aria-label="هدایت"><Icon name="forward" /></button>
            <button className="icon-btn" disabled={!canDel} onClick={delSel} title="حذف" aria-label="حذف"><Icon name="trash" /></button>
          </header>
        ) : (
        <header className="room-head">
          <button className="icon-btn back" onClick={onBack} aria-label="بازگشت"><Icon name="back" /></button>
          <button className="room-head-info" onClick={() => setInfo(true)} aria-label="اطلاعات گفتگو">
            <RoomAvatar room={room} size={40} />
            <div className="room-head-text">
              <b>{room.name}</b>
              <span className={typing.length || seen.online ? "typing" : ""}>{subtitle}</span>
            </div>
          </button>
          {room.getMyMembership() !== KnownMembership.Invite && <>
            <CallButtons room={room} />
            <button className="icon-btn" onClick={() => setSearch(true)} title="جستجو" aria-label="جستجو"><Icon name="search" /></button>
          </>}
        </header>
        )}
        <NowPlaying onJump={(id) => jump(id)} />
        {room.getMyMembership() !== KnownMembership.Invite && <CallBar room={room} />}
        {room.getMyMembership() === KnownMembership.Invite ? (
          <Invite room={room} />
        ) : (
          <DropZone onFiles={(f) => setFiles((x) => [...x, ...f])}>
            <Predecessor room={room} />
            <PinnedBar room={room} onJump={jump} />
            <Timeline room={room} actions={actions} jumpRef={jumper} />
            <Upgraded room={room}><Composer room={room} threadId={null} mode={mode} setMode={setMode} files={files} setFiles={setFiles} /></Upgraded>
          </DropZone>
        )}
      </div>
      {thread && (
        <aside className="thread-panel">
          <header className="room-head">
            <div className="room-head-text"><b>رشته‌ی گفتگو</b><span>{num(thread.length)} پاسخ</span></div>
            <button className="icon-btn" onClick={() => setThreadId(null)} aria-label="بستن رشته"><Icon name="close" /></button>
          </header>
          <ThreadView key={thread.id} room={room} threadId={thread.id} view={setViewing} info={setSeenFor} profile={setProfile} forward={forward1} />
        </aside>
      )}
      {search && <SearchSheet room={room} onJump={(id, server) => searchJump(id, server ? 50 : undefined)} onClose={() => setSearch(false)} />}
      {info && <RoomInfo room={room} onClose={() => setInfo(false)} />}
      {seenFor && <SeenBy room={room} ev={seenFor} onClose={() => setSeenFor(null)} />}
      {profile && <UserProfile userId={profile} room={room} onClose={() => setProfile(null)} />}
      {forwarding && <ForwardSheet evs={forwarding} onClose={() => setForwarding(null)} onSent={() => setSel(null)} />}
      {viewing && <MediaViewer items={timelineMedia(room, viewing)} start={viewing} onClose={() => setViewing(null)} onJump={(ev) => jump(ev.getId()!)} />}
    </section>
  );
}

type ThreadProps = { room: SdkRoom; threadId: string; view: (ev: MatrixEvent) => void; info: (ev: MatrixEvent) => void; profile: (id: string) => void; forward: (ev: MatrixEvent) => void };
function ThreadView({ room, threadId, view, info, profile, forward }: ThreadProps) {
  const [mode, setMode] = useState<Mode>(null);
  const [files, setFiles] = useState<File[]>([]);
  const jumper = useRef<Jumper>(null);

  const actions: Actions = useMemo(() => ({
    reply: (ev: MatrixEvent) => setMode({ kind: "reply", ev }),
    edit: (ev: MatrixEvent) => setMode({ kind: "edit", ev }),
    view,
    info,
    profile,
    forward,
    jump: (id: string) => void jumper.current?.(id),
  }), [view, info, profile, forward]);
  return (
    <DropZone onFiles={(f) => setFiles((x) => [...x, ...f])}>
      <Timeline room={room} thread={room.getThread(threadId)!} actions={actions} jumpRef={jumper} />
      <Upgraded room={room}><Composer room={room} threadId={threadId} mode={mode} setMode={setMode} files={files} setFiles={setFiles} /></Upgraded>
    </DropZone>
  );
}

function Invite({ room }: { room: SdkRoom }) {
  const [busy, setBusy] = useState(false);
  const act = (fn: () => Promise<unknown>) => { setBusy(true); fn().catch((e) => alertDialog(errText(e))).finally(() => setBusy(false)); };
  return (
    <div className="invite">
      <RoomAvatar room={room} size={88} />
      <h2>{room.name}</h2>
      <p>{room.getDMInviter() ? `${bdi(room.getDMInviter()!)} شما را دعوت کرد` : "به این گفتگو دعوت شده‌اید"}</p>
      <div className="invite-actions">
        <button disabled={busy} onClick={() => act(() => client.leave(room.roomId))}>رد کردن</button>
        <button className="primary" disabled={busy} onClick={() => act(() => join(room))}>پیوستن</button>
      </div>
    </div>
  );
}

/** Join; for a DM invite also record it in m.direct. */
async function join(room: SdkRoom) {
  const inviter = room.getDMInviter();
  await client.joinRoom(room.roomId);
  if (inviter) await addDirect(inviter, room.roomId);
}
