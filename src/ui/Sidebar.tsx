import { rtl, t } from "../i18n.ts";
import { useLongPress } from "./useLongPress.ts";
import { useEffect, useLayoutEffect, useRef, useState, type ReactNode, type TouchEvent } from "react";
import { Virtuoso } from "react-virtuoso";
import { NotificationCountType, UserEvent, type Room } from "matrix-js-sdk";
import { client, dmPeer, emoteMap } from "../matrix.ts";
import { setMuted } from "../notify.ts";
import { isCallStart, useFolderOrder, useRooms, useTick, useTyping, type RoomRow } from "../hooks.ts";
import { ARCHIVED, leaveAndForget, markRead, PINNED, setMarkedUnread, setTag, unreadCount } from "../chats.ts";
import { applyFolderOrder, BASE_FOLDERS, inFolder, isUnread, listTime, moveFolder, normalize, num } from "../logic.ts";
import { useSortableTabs } from "./useSortableTabs.ts";
import { pushBack } from "../back.ts";
import { setBadge } from "../desktop.ts";
import logo from "../logo.svg";
import { Icon } from "../icons.tsx";
import { bdi, ContextMenu, Dots, errText, formattedPlain, useChange, me, noticeText, previewText, RoomAvatar, senderName } from "./common.tsx";
import { EmoteText } from "./Emoji.tsx";
import { Ticks } from "./Message.tsx";
import { NewChat } from "./NewChat.tsx";
import { RoomInfo } from "./RoomInfo.tsx";
import { MessageResults, requestJump } from "./Search.tsx";
import { QuickSwitch } from "./QuickSwitch.tsx";
import { CallsList } from "./Calls.tsx";
import { alertDialog, confirmDialog } from "./dialog.tsx";

type Props = { loading?: boolean; selected?: string; onSelect: (id: string) => void; onSettings: () => void; banner?: ReactNode };
const FOLDER_KEY = () => `panbeh.folder:${client.getUserId()}`;
type Menu = { row: RoomRow; x: number; y: number };

export function Sidebar({ loading, selected, onSelect, onSettings, banner }: Props) {
  const { rows, spaces } = useRooms();
  const [folder, setFolder] = useState(() => localStorage.getItem(FOLDER_KEY()) ?? "all");
  const [query, setQuery] = useState("");
  const [spaceInfo, setSpaceInfo] = useState(false);
  const [archive, setArchive] = useState(false);
  const [calls, setCalls] = useState(false);
  const [menu, setMenu] = useState<Menu | null>(null);
  const [quick, setQuick] = useState(false);

  const [order, setOrder] = useFolderOrder();
  const folders = applyFolderOrder([...BASE_FOLDERS, ...spaces.map((s) => ({ id: s.roomId, label: s.name }))], order);
  const active = folders.some((f) => f.id === folder) ? folder : "all";
  const q = normalize(query.trim());
  const archived = rows.filter((r) => inFolder(r, "archive"));
  const inArchiveView = archive && !q && archived.length > 0;
  const inCalls = calls && !q;
  useEffect(() => { if (!archived.length) setArchive(false); }, [archived.length]); // last one unarchived
  const shown = rows.filter((r) => (q ? normalize(r.room.name).includes(q) : inFolder(r, inArchiveView ? "archive" : active)));
  const unreadIn = (id: string) => rows.filter((r) => isUnread(r) && inFolder(r, id)).length;
  const unreadChats = rows.filter((r) => isUnread(r) && !r.muted).length;
  useEffect(() => { document.title = unreadChats ? t("app.titleUnread", { count: unreadChats }) : t("app.name"); setBadge(unreadChats); }, [unreadChats]);
  useEffect(() => () => setBadge(0), []); // signed out or switching accounts

  const nav = useRef<HTMLElement>(null);
  const indicator = useRef<HTMLSpanElement>(null);
  const tabsKey = folders.map((f) => f.id).join("\n");
  useLayoutEffect(() => { // the underline slides to the active tab; the first placement and resizes don't animate
    const bar = nav.current, ind = indicator.current, tab = bar?.querySelector<HTMLElement>("button.on");
    if (!bar || !ind) return;
    if (!tab) { ind.style.opacity = "0"; return; }
    const place = () => { ind.style.opacity = "1"; ind.style.width = tab.offsetWidth - 16 + "px"; ind.style.transform = `translateX(${tab.offsetLeft + 8}px)`; };
    place();
    requestAnimationFrame(() => ind.classList.add("ready"));
    const ro = new ResizeObserver(() => { ind.classList.remove("ready"); place(); requestAnimationFrame(() => ind.classList.add("ready")); });
    ro.observe(tab);
    return () => ro.disconnect();
  }, [active, tabsKey, !q && !inArchiveView && !inCalls]);
  const { dragging, barProps } = useSortableTabs(nav, folders.map((f) => f.id), (from, to) => setOrder(moveFolder(folders.map((f) => f.id), from, to)));
  // keep the active tab visible (e.g. a remembered space folder past the edge)
  useEffect(() => { nav.current?.querySelector(".on")?.scrollIntoView({ block: "nearest", inline: "nearest" }); }, [active, q]);

  // keyboard shortcuts; capture phase so we see the page before handlers that close things on the same key
  const keys = useRef({ shown, selected, onSelect });
  keys.current = { shown, selected, onSelect };
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const { shown, selected, onSelect } = keys.current;
      const mod = e.ctrlKey || e.metaKey;
      if (mod && !e.altKey && e.code === "KeyK") { e.preventDefault(); setQuick((x) => !x); return; }
      if (mod && !e.altKey && e.code === "KeyF" && selected) { e.preventDefault(); dispatchEvent(new Event("panbeh-search")); return; }
      if (e.altKey && !mod && (e.key === "ArrowUp" || e.key === "ArrowDown")) {
        e.preventDefault();
        const i = shown.findIndex((r) => r.id === selected);
        const next = shown[i < 0 ? 0 : i + (e.key === "ArrowDown" ? 1 : -1)];
        if (next) onSelect(next.id);
        return;
      }
      if (e.key === "Escape" && selected && !e.defaultPrevented && !busyEsc(e)) location.hash = "";
    };
    addEventListener("keydown", onKey, true);
    return () => removeEventListener("keydown", onKey, true);
  }, []);

  const activeSpace = spaces.find((s) => s.roomId === active);
  const pick = (id: string) => { setFolder(id); localStorage.setItem(FOLDER_KEY(), id); setArchive(false); };
  // touch: swipe the chat list sideways to change folder. RTL: the next tab sits to the left,
  // so a swipe to the right brings it in
  const touch = useRef<{ x: number; y: number } | null>(null);
  const swipe = {
    onTouchStart: (e: TouchEvent) => { const t = e.touches[0]; touch.current = e.touches.length === 1 ? { x: t.clientX, y: t.clientY } : null; },
    onTouchEnd: (e: TouchEvent) => {
      const s = touch.current, t = e.changedTouches[0];
      touch.current = null;
      if (!s || q) return;
      const dx = t.clientX - s.x, dy = t.clientY - s.y;
      if (Math.abs(dx) < 70 || Math.abs(dx) < 2 * Math.abs(dy)) return;
      const next = folders[folders.findIndex((f) => f.id === active) + ((dx > 0) === rtl ? 1 : -1)];
      if (next) pick(next.id);
    },
  };
  // Android back, last resort (an open chat closes first): leave the search, the archive, then return to the first folder
  const backState = useRef({ query, archive, calls, active, pick });
  backState.current = { query, archive, calls, active, pick };
  useEffect(() => pushBack(() => {
    const b = backState.current;
    if (b.query) { setQuery(""); (document.activeElement as HTMLElement | null)?.blur(); return true; }
    if (b.calls) { setCalls(false); return true; }
    if (b.archive) { setArchive(false); return true; }
    if (b.active !== BASE_FOLDERS[0].id) { b.pick(BASE_FOLDERS[0].id); return true; }
    return false;
  }, { low: true }), []);
  const item = (r: RoomRow) => (
    <RoomItem row={r} active={r.id === selected} onClick={() => onSelect(r.id)} onMenu={(x, y) => setMenu({ row: r, x, y })} />
  );

  return (
    <aside className="sidebar">
      <header className="sidebar-head">
        {inArchiveView || inCalls
          ? <button className="icon-btn" onClick={() => inCalls ? setCalls(false) : setArchive(false)} title={t("common.back")} aria-label={t("common.back")}><Icon name="back" /></button>
          : <button className="icon-btn" onClick={onSettings} title={t("settings.title")} aria-label={t("settings.title")}><Icon name="settings" /></button>}
        <img className="logo" src={logo} alt={t("app.name")} />
        <label className="search">
          <Icon name="search" size={16} />
          <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder={inArchiveView ? t("sidebar.searchAll") : t("common.search")} aria-label={t("sidebar.searchChats")}
            onKeyDown={(e) => { if (e.key === "Enter" && shown[0]) { onSelect(shown[0].id); setQuery(""); } if (e.key === "Escape") setQuery(""); }} />
          {query && <button className="icon-btn" onClick={(e) => { setQuery(""); e.currentTarget.blur(); }} title={t("sidebar.closeSearch")} aria-label={t("sidebar.closeSearch")}><Icon name="close" size={16} /></button>}
        </label>
        {!inCalls && !inArchiveView && <button className="icon-btn" onClick={() => setCalls(true)} title={t("calls.title")} aria-label={t("calls.title")}><Icon name="phone" /></button>}
      </header>
      {banner}
      {inArchiveView && <h2 className="archive-title">{t("archive")}</h2>}
      {inCalls && <h2 className="archive-title">{t("calls.title")}</h2>}
      {inCalls ? <CallsList rooms={rows.filter((r) => !r.invite).map((r) => r.room)} onSelect={onSelect} /> : <>
      {!q && !inArchiveView && (
        <nav className={"folders" + (dragging ? " sorting" : "")} role="tablist" ref={nav} {...barProps}
          // mouse wheel scrolls the tabs sideways: "down" moves toward the end (the left in RTL)
          onWheel={(e) => { if (!e.deltaX) e.currentTarget.scrollBy({ left: rtl ? -e.deltaY : e.deltaY }); }}>
          {folders.map((f) => {
            const n = unreadIn(f.id);
            return (
              <button key={f.id} data-id={f.id} role="tab" aria-selected={f.id === active} className={(f.id === active ? "on" : "") + (f.id === dragging ? " dragging" : "")} onClick={() => pick(f.id)}>
                {f.label}{n > 0 && <span className="folder-badge">{num(n)}</span>}
              </button>
            );
          })}
          <span className="folder-indicator" ref={indicator} aria-hidden />
        </nav>
      )}
      {!q && !inArchiveView && activeSpace && (
        <button className="space-row" onClick={() => setSpaceInfo(true)}><Icon name="space" size={18} /> {t("sidebar.manageSpace")}</button>
      )}
      {!q && !inArchiveView && active === "all" && archived.length > 0 && (
        <button className="space-row archive-row" onClick={() => setArchive(true)}>
          <Icon name="archive" size={18} /> {t("archive")} <small>{num(archived.length)}</small>
          {unreadIn("archive") > 0 && <span className="badge muted">{num(unreadIn("archive"))}</span>}
        </button>
      )}
      {q.length >= 2 ? (
        <div className="room-list scroll">
          {shown.length === 0 ? <p className="empty-list">{t("quick.none")}</p> : shown.map((r) => <div key={r.id}>{item(r)}</div>)}
          <MessageResults term={query} onPick={(roomId, eventId) => { requestJump(roomId, eventId); onSelect(roomId); setQuery(""); }} />
        </div>
      ) : (
        <div className="list-swipe" {...(inArchiveView ? {} : swipe)}>
          {loading // not a list still filling in from the sync cache: chats would land between the ones already shown
            ? <div className="skeleton-list" aria-busy aria-label={t("sidebar.syncing")}>{[0, 1, 2, 3, 4, 5, 6].map((i) => <div key={i} className="skeleton-row" style={{ animationDelay: i * 0.06 + "s" }}><i className="skeleton avatar-sk" /><span><i className="skeleton" style={{ width: 55 - (i % 3) * 8 + "%" }} /><i className="skeleton" style={{ width: 85 - (i % 4) * 10 + "%" }} /></span></div>)}</div>
            : shown.length === 0 ? <p className="empty-list">{t("sidebar.empty")}</p>
            : <Virtuoso className="room-list" data={shown} computeItemKey={(_, r) => r.id} itemContent={(_, r) => item(r)} />}
        </div>
      )}
      </>}
      <NewChat activeSpace={activeSpace?.roomId} onOpen={(id, space) => (space ? pick(id) : onSelect(id))} />
      {spaceInfo && activeSpace && <RoomInfo room={activeSpace} onClose={() => setSpaceInfo(false)} />}
      {menu && <ChatMenu {...menu} onClose={() => setMenu(null)} />}
      {quick && <QuickSwitch rows={rows} onPick={(id) => { setQuick(false); onSelect(id); }} onClose={() => setQuick(false)} />}
    </aside>
  );
}

/** Esc belongs to whatever is open on top (sheets, menus, mention list, reply/edit bar) or to a field being typed in. */
function busyEsc(e: KeyboardEvent) {
  if (document.querySelector("[role=dialog], [role=menu], [role=listbox], .composer-mode, .select-bar")) return true;
  const t = e.target as HTMLInputElement | null;
  return !!t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA") && t.value !== "";
}

/** The chat actions in the context menu. */
function chatOps(row: RoomRow) {
  const { room } = row, unread = isUnread(row);
  return {
    unread,
    pin: () => setTag(room, PINNED, !row.pinned),
    archive: () => setTag(room, ARCHIVED, !row.archived),
    read: () => (unread ? markRead(room) : setMarkedUnread(room, true)),
  };
}

function OnlineDot({ room }: { room: Room }) {
  const peer = dmPeer(room);
  const u = peer ? client.getUser(peer) : null;
  useTick(client, [UserEvent.Presence]);
  return u?.presence === "online" ? <i className="online-dot" aria-label={t("seen.online")} /> : null;
}

function RoomItem({ row, active, onClick, onMenu }: { row: RoomRow; active: boolean; onClick: () => void; onMenu: (x: number, y: number) => void }) {
  const { room, last } = row;
  const { click, handlers } = useLongPress(row.invite ? undefined : onMenu);
  let preview = row.invite ? t("room.invite") : "";
  let emotes: ReadonlyMap<string, string> | undefined; // the preview's custom emoji, code → mxc
  if (last && !row.invite && isCallStart(last)) preview = "📞 " + noticeText(last); // names the caller itself
  else if (last && !row.invite) {
    const who = last.getSender() === me() ? t("common.you") : row.isDM ? "" : bdi(senderName(last).split(" ")[0]);
    preview = (who ? who + ": " : "") + previewText(last);
    emotes = formattedPlain(last.getContent())?.emotes;
  }
  const typers = useTyping(room);
  const typing = row.invite ? [] : typers;
  if (typing.length) preview = row.isDM ? t("typing.dm") : t("typing.single", { a: bdi(typing[0].name.split(" ")[0]) });
  const draft = !typing.length && row.draft;
  if (draft) { preview = draft.body.split("\n")[0]; emotes = emoteMap(room); } // composer text: this room's emoji
  const mine = last && !row.invite && !isCallStart(last) && last.getSender() === me(); // sent/seen, like in the chat
  const muted = !!row.muted;
  const bumped = useChange(row.unread);
  const previewChanged = useChange(preview, 350);
  const mentioned = row.unread > 0 && unreadCount(room, NotificationCountType.Highlight) > 0;
  return (
    <button className={"room-item" + (active ? " active" : "")} onClick={click(onClick)} {...handlers}>
      <span className="avatar-wrap"><RoomAvatar room={room} size={50} />{row.isDM && !row.invite && <OnlineDot room={room} />}</span>
      <div className="room-item-body">
        <div className="room-item-top">
          <span className="room-name">{room.name}</span>
          {muted && <span className="room-muted" aria-label={t("chat.muted")}><Icon name="bellOff" size={14} /></span>}
          <span className="room-time">{mine && <Ticks ev={last} room={room} />}{listTime(row.ts)}</span>
        </div>
        <div className="room-item-bottom">
          <span className={"room-preview" + (typing.length ? " typing" : "") + (previewChanged ? " changed" : "")}>{draft && <span className="draft">{t("sidebar.draft")} </span>}{typing.length ? preview : <EmoteText text={preview} emotes={emotes} />}{typing.length > 0 && <Dots />}</span>
          {mentioned && <span className="badge">@</span>}
          {(row.unread > 0 || row.invite) ? <span className={"badge" + (muted ? " muted" : "") + (bumped ? " bump" : "")}>{row.invite ? "!" : num(row.unread)}</span>
            : row.marked ? <span className={"badge dot" + (muted ? " muted" : "")} aria-label={t("folder.unread")} />
            : row.pinned && <span className="room-pin" aria-label={t("chat.pinned")}><Icon name="pin" size={16} /></span>}
        </div>
      </div>
    </button>
  );
}

function ChatMenu({ row, x, y, onClose }: Menu & { onClose: () => void }) {
  const { room } = row;
  const run = (fn: () => Promise<unknown>) => { onClose(); fn().catch((e) => alertDialog(errText(e))); };
  const ops = chatOps(row), unread = ops.unread;
  return (
    <ContextMenu x={x} y={y} target=".room-item" onClose={onClose}>
        <button role="menuitem" onClick={() => run(ops.pin)}>
          <Icon name="pin" /> {row.pinned ? t("msg.unpin") : t("msg.pin")}</button>
        <button role="menuitem" onClick={() => run(ops.archive)}>
          <Icon name="archive" /> {row.archived ? t("chat.unarchive") : t("archive")}</button>
        <button role="menuitem" onClick={() => run(ops.read)}>
          <Icon name={unread ? "checks" : "unread"} /> {unread ? t("chat.markRead") : t("chat.markUnread")}</button>
        <button role="menuitem" onClick={() => run(() => setMuted(room, !row.muted))}>
          <Icon name={row.muted ? "bell" : "bellOff"} /> {row.muted ? t("chat.unmute") : t("chat.mute")}</button>
        <button role="menuitem" className="danger-item"
          onClick={() => confirmDialog(t("chat.leaveQ", { name: room.name }), { danger: true }).then((y) => y && run(() => leaveAndForget(room)))}>
          <Icon name="trash" /> {t("chat.leave")}</button>
    </ContextMenu>
  );
}
