import { t } from "../i18n.ts";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { KnownMembership, type MatrixEvent, type Room as SdkRoom } from "matrix-js-sdk";
import { addDirect, client, dmPeer, isDirect, loadEvent } from "../matrix.ts";
import { usePresence, useTick, useTyping } from "../hooks.ts";
import { Icon } from "../icons.tsx";
import { clock, neshanUrl, num, stamp } from "../logic.ts";
import { geoClick } from "../native.ts";
import { bdi, Dots, errText, me, RoomAvatar, senderName } from "./common.tsx";
import { Timeline, type Jumper } from "./Timeline.tsx";
import { LiveEmojis } from "./LiveEmoji.tsx";
import { PinnedBar } from "./Pinned.tsx";
import { NowPlaying } from "./Voice.tsx";
import { CallBar, CallButtons } from "./Call.tsx";
import { Composer, DropZone, type Mode } from "./Composer.tsx";
import { takeShared } from "../drafts.ts";
import { copyMessages, copyTextOf, type Actions } from "./Message.tsx";
import { ReactedBy, RoomInfo, SeenBy } from "./RoomInfo.tsx";
import { UserProfile } from "./Profile.tsx";
import { ForwardSheet } from "./Forward.tsx";
import { pendingJump, SearchSheet } from "./Search.tsx";
import { MediaViewer, timelineMedia } from "./Media.tsx";
import { Predecessor, Upgraded } from "./Admin.tsx";
import { liveBeacons, onBeaconChange, sharingIn, stopSharing, useLiveLocation } from "../liveLocation.ts";
import { alertDialog, confirmDialog } from "./dialog.tsx";
import { useExit } from "./useDismiss.ts";
import { overlayOpen, pushBack } from "../back.ts";

export function Room({ room, onBack }: { room: SdkRoom; onBack: () => void }) {
  useTick(client, ["Room.myMembership", "Room.name"]);
  const seen = usePresence(isDirect(room) && room.getMyMembership() === KnownMembership.Join ? dmPeer(room) : undefined);
  // lazy-loaded sync only has members who spoke recently; thread replies (/relations) bring none, so senders showed as IDs
  // ponytail: whole member list per opened room; fetch single profiles instead if huge rooms get slow
  useEffect(() => { room.loadMembersIfNeeded().catch(() => {}); }, [room]);
  const [mode, setMode] = useState<Mode>(null);
  const [files, setFiles] = useState<File[]>(() => takeShared(room.roomId));
  const [threadId, setThreadId] = useState<string | null>(null);
  const [viewing, setViewing] = useState<MatrixEvent | null>(null);
  const [info, setInfo] = useState(false);
  const [seenFor, setSeenFor] = useState<MatrixEvent | null>(null);
  const [reactionsFor, setReactionsFor] = useState<MatrixEvent | null>(null);
  const [profile, setProfile] = useState<string | null>(null);
  const [forwarding, setForwarding] = useState<MatrixEvent[] | null>(null);
  const [sel, setSel] = useState<ReadonlySet<string> | null>(null); // multi-select: event ids of the main timeline
  const forward1 = useCallback((ev: MatrixEvent | MatrixEvent[]) => setForwarding([ev].flat()), []);
  const [search, setSearch] = useState<string | null>(null); // the sheet's starting query, while open

  const jumper = useRef<Jumper>(null);
  useEffect(() => { // Ctrl+F (Sidebar's shortcuts)
    const open = (e: Event) => { if (room.getMyMembership() !== KnownMembership.Invite) setSearch((e as CustomEvent<string | undefined>).detail ?? ""); }; // detail: a tapped #hashtag
    addEventListener("panbeh-search", open);
    return () => removeEventListener("panbeh-search", open);
  }, [room]);

  useEffect(() => { // Escape leaves selection mode (not while the forward sheet is open: that closes first)
    if (!sel || forwarding) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") { e.preventDefault(); setSel(null); } };
    addEventListener("keydown", onKey);
    return () => removeEventListener("keydown", onKey);
  }, [!!sel, !!forwarding]);

  const actions: Actions = useMemo(() => ({
    reply: (ev: MatrixEvent) => setMode({ kind: "reply", ev }),
    edit: (ev: MatrixEvent) => setMode({ kind: "edit", ev }),
    thread: (ev: MatrixEvent) => {
      if (!room.getThread(ev.getId()!)) room.createThread(ev.getId()!, ev, [], true);
      setThreadId(ev.getId()!);
    },
    view: setViewing,
    info: setSeenFor,
    reactions: setReactionsFor,
    profile: setProfile,
    forward: forward1,
    jump: (id: string) => void searchJump(id),
    select: (ev: MatrixEvent) => setSel((s) => { // toggle; empty = leave selection mode
      const n = new Set(s);
      if (!n.delete(ev.getId()!)) n.add(ev.getId()!);
      return n.size ? n : null;
    }),
    selection: sel ?? undefined,
  }), [room, sel]);

  const picked = sel ? [...sel].map((id) => room.findEventById(id)).filter((e) => !!e).sort((a, b) => a.getTs() - b.getTs()) : []; // timeline order
  const copySel = () => {
    const text = picked.map((e) => [e, copyTextOf(e)] as const).filter(([, t]) => t)
      .map(([e, t]) => (picked.length === 1 ? t : `${senderName(e)}, [${stamp(e.getTs())}]:\n${t}`)).join("\n\n");
    if (text) void copyMessages(text);
    setSel(null);
  };
  const canDel = picked.length > 0 && picked.every((e) => e.getSender() === me() || room.currentState.maySendRedactionForEvent(e, me()));
  const delSel = async () => {
    if (!(await confirmDialog(t("msg.deleteN", { count: picked.length }), { danger: true }))) return;
    Promise.all(picked.map((e) => client.redactEvent(room.roomId, e.getId()!))).catch((e) => alertDialog(errText(e)));
    setSel(null);
  };

  // live location: our sharing in this room, and other people's live beacons
  const [, bumpLive] = useState(0);
  useEffect(() => onBeaconChange(room, () => bumpLive((n) => n + 1)), [room]);
  useLiveLocation();
  const myShare = sharingIn(room.roomId);
  const others = liveBeacons(room).filter((b) => b.userId !== me());
  const typing = useTyping(room).map((m) => bdi(m.name.split(" ")[0]));
  const subtitle = typing.length
    ? (typing.length > 2 ? t("typing.more", { a: typing[0], b: typing[1], count: typing.length - 2 }) : typing.length === 2 ? t("typing.two", { a: typing[0], b: typing[1] }) : t("typing.single", { a: typing[0] }))
    : room.getMyMembership() === KnownMembership.Invite ? t("room.invite") // invites carry no member counts
    : seen.text ?? t("room.members", { count: room.getJoinedMemberCount() });
  useEffect(() => { // Android back closes the thread, back to the chat it was opened from
    if (threadId) return pushBack(() => (overlayOpen() ? false : setThreadId(null)));
  }, [threadId]);
  const liveThread = threadId ? room.getThread(threadId) : null;
  const [threadShown, threadClosing] = useExit(!!liveThread, 240);
  const lastThread = useRef(liveThread);
  if (liveThread) lastThread.current = liveThread;
  const thread = liveThread ?? (threadShown ? lastThread.current : null); // keeps rendering while the panel slides out

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

  const searchJump = async (id: string, maxPages?: number) => { if (!(await jump(id, maxPages))) alertDialog(t("room.tooOld")); };
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
          <header className="room-head select-bar" role="toolbar" aria-label={t("select.bar")}>
            <button className="icon-btn" onClick={() => setSel(null)} title={t("select.cancel")} aria-label={t("select.cancel")}><Icon name="close" /></button>
            <b className="select-count">{t("select.count", { count: sel.size })}</b>
            <button className="icon-btn" onClick={copySel} title={t("common.copy")} aria-label={t("common.copy")}><Icon name="copy" /></button>
            <button className="icon-btn" onClick={() => setForwarding(picked)} title={t("msg.forward")} aria-label={t("msg.forward")}><Icon name="forward" /></button>
            <button className="icon-btn" disabled={!canDel} onClick={delSel} title={t("common.delete")} aria-label={t("common.delete")}><Icon name="trash" /></button>
          </header>
        ) : (
        <header className="room-head">
          <button className="icon-btn back" onClick={onBack} aria-label={t("common.back")}><Icon name="back" /></button>
          <button className="room-head-info" onClick={() => setInfo(true)} aria-label={t("room.info")}>
            <RoomAvatar room={room} size={40} />
            <div className="room-head-text">
              <b>{room.name}</b>
              <span className={typing.length || seen.online ? "typing" : ""}>{subtitle}{typing.length > 0 && <Dots />}</span>
            </div>
          </button>
          {room.getMyMembership() !== KnownMembership.Invite && <>
            <CallButtons room={room} />
            <button className="icon-btn" onClick={() => setSearch("")} title={t("common.search")} aria-label={t("common.search")}><Icon name="search" /></button>
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
            {(myShare || others.length > 0) && (
              <div className="banner live-loc">
                <Icon name="location" size={16} />
                <span>
                  {myShare && <>{t("loc.mineUntil", { time: clock(myShare.until) })}{others.length ? " · " : ""}</>}
                  {others.length === 1
                    ? <><a href={neshanUrl(others[0].lat, others[0].lon)} target="_blank" rel="noreferrer noopener" onClick={geoClick(others[0].lat, others[0].lon, others[0].name)}>{bdi(others[0].name)}</a> {t("loc.oneSharing")}</>
                    : others.length > 1 ? <>{t("loc.nSharing", { count: others.length })}</> : null}
                </span>
                {myShare && <button onClick={() => stopSharing(client, room.roomId).catch((e) => alertDialog(errText(e)))}>{t("loc.stop")}</button>}
              </div>
            )}
            <PinnedBar room={room} onJump={jump} />
            <Timeline room={room} actions={actions} jumpRef={jumper} />
            <Upgraded room={room}><Composer room={room} threadId={null} mode={mode} setMode={setMode} files={files} setFiles={setFiles} /></Upgraded>
          </DropZone>
        )}
      </div>
      {thread && (
        <aside className={"thread-panel" + (threadClosing ? " closing" : "")} inert={threadClosing}>
          <header className="room-head">
            <div className="room-head-text"><b>{t("thread.title")}</b><span>{t("thread.replies", { count: thread.length })}</span></div>
            <button className="icon-btn" onClick={() => setThreadId(null)} aria-label={t("thread.close")}><Icon name="close" /></button>
          </header>
          <ThreadView key={thread.id} room={room} threadId={thread.id} view={setViewing} info={setSeenFor} reactions={setReactionsFor} profile={setProfile} forward={forward1} />
        </aside>
      )}
      {search !== null && <SearchSheet key={search} room={room} initial={search} onJump={(id, server) => searchJump(id, server ? 50 : undefined)} onClose={() => setSearch(null)} />}
      {info && <RoomInfo room={room} onClose={() => setInfo(false)} />}
      {seenFor && <SeenBy room={room} ev={seenFor} onClose={() => setSeenFor(null)} />}
      {reactionsFor && <ReactedBy room={room} ev={reactionsFor} onClose={() => setReactionsFor(null)} />}
      {profile && <UserProfile userId={profile} room={room} onClose={() => setProfile(null)} />}
      {forwarding && <ForwardSheet evs={forwarding} onClose={() => setForwarding(null)} onSent={() => setSel(null)} />}
      {viewing && <MediaViewer items={timelineMedia(room, viewing)} start={viewing} onClose={() => setViewing(null)} onJump={(ev) => jump(ev.getId()!)} />}
      <LiveEmojis room={room} />
    </section>
  );
}

type ThreadProps = { room: SdkRoom; threadId: string; view: (ev: MatrixEvent) => void; info: (ev: MatrixEvent) => void; reactions: (ev: MatrixEvent) => void; profile: (id: string) => void; forward: (ev: MatrixEvent | MatrixEvent[]) => void };
function ThreadView({ room, threadId, view, info, reactions, profile, forward }: ThreadProps) {
  const [mode, setMode] = useState<Mode>(null);
  const [files, setFiles] = useState<File[]>([]);
  const jumper = useRef<Jumper>(null);

  const actions: Actions = useMemo(() => ({
    reply: (ev: MatrixEvent) => setMode({ kind: "reply", ev }),
    edit: (ev: MatrixEvent) => setMode({ kind: "edit", ev }),
    view,
    info,
    reactions,
    profile,
    forward,
    jump: (id: string) => void jumper.current?.(id),
  }), [view, info, reactions, profile, forward]);
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
      <p>{room.getDMInviter() ? t("invite.by", { name: bdi(room.getDMInviter()!) }) : t("invite.you")}</p>
      <div className="invite-actions">
        <button disabled={busy} onClick={() => act(() => client.leave(room.roomId))}>{t("invite.decline")}</button>
        <button className="primary" disabled={busy} onClick={() => act(() => join(room))}>{t("invite.join")}</button>
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
