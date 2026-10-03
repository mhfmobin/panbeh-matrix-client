import { useEffect, useRef, useState, type ClipboardEvent, type DragEvent, type KeyboardEvent, type ReactNode } from "react";
import { ContentHelpers, LocationAssetType, MsgType, type IContent, type MatrixEvent, type Room } from "matrix-js-sdk";
import { client, startUpload } from "../matrix.ts";
import { Icon } from "../icons.tsx";
import { Avatar, bdi, errText, formatSize, me, previewText, senderName, stripReplyFallback } from "./common.tsx";
import { escRe, formatMessage, num, osmUrl, type Sticker } from "../logic.ts";
import { isMessage } from "../hooks.ts";
import { VoiceRecorder } from "./Voice.tsx";
import { PollForm } from "./Poll.tsx";
import { EmojiPanel, sendSticker } from "./Emoji.tsx";
import { alertDialog, confirmDialog } from "./dialog.tsx";

export type Mode = { kind: "reply" | "edit"; ev: MatrixEvent } | null;
const MEDIA = ["m.image", "m.video", "m.file"];
export const EDITABLE = ["m.text", "m.emote", "m.notice", ...MEDIA];
/** A media message's caption (MSC2530): body differs from filename; otherwise body is just the name. */
export const captionOf = (c: IContent): string => (c.filename && c.body !== c.filename ? c.body ?? "" : "");
const drafts = new Map<string, string>();

type Props = { room: Room; threadId: string | null; mode: Mode; setMode: (m: Mode) => void; files: File[]; setFiles: (f: (x: File[]) => File[]) => void };
type Mention = { name: string; id: string };
type Suggestion = Mention & { mxc?: string | null };

export function Composer({ room, threadId, mode, setMode, files, setFiles }: Props) {
  const draftKey = room.roomId + (threadId ?? "");
  const [text, setText] = useState(drafts.get(draftKey) ?? "");
  const mediaEdit = mode?.kind === "edit" && MEDIA.includes(mode.ev.getContent().msgtype ?? "");
  const [menu, setMenu] = useState(false);
  const [pollForm, setPollForm] = useState(false);
  const [recording, setRecording] = useState(false);
  const [locating, setLocating] = useState(false);
  const [emoji, setEmoji] = useState(false);
  const [caret, setCaret] = useState(0);
  const [sel, setSel] = useState(0);
  const [closedAt, setClosedAt] = useState(-1); // Esc hides the mention list for this "@"
  const ta = useRef<HTMLTextAreaElement>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const typingAt = useRef(0);
  const stash = useRef(""); // the draft, put aside while editing a message
  const editing = useRef(false);
  const mentions = useRef<Mention[]>([]); // people picked from the @ list; turned into links on send

  useEffect(() => { drafts.set(draftKey, editing.current ? stash.current : text); }, [draftKey, text]);
  const mounted = useRef(false);
  useEffect(() => { // autosize
    const el = ta.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = Math.min(el.scrollHeight, 200) + "px";
  }, [text, recording]);
  useEffect(() => {
    const edit = mode?.kind === "edit";
    if (edit && !editing.current) stash.current = text;
    if (edit) {
      const c = mode.ev.getContent();
      setText(mediaEdit ? captionOf(c) : stripReplyFallback(c.body ?? ""));
      // keep the original's mentions as links in the edit
      mentions.current = (c["m.mentions"]?.user_ids ?? []).map((id: string) => ({ id, name: room.getMember(id)?.name ?? id }));
    } else if (editing.current) setText(stash.current); // edit sent/cancelled, or switched to reply
    editing.current = edit;
    // opening a chat must not pop the keyboard on touch devices; reply/edit always focuses
    if (!mounted.current && !matchMedia("(pointer: fine)").matches) mounted.current = true;
    else { mounted.current = true; ta.current?.focus(); }
  }, [mode]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => () => { // left the room mid-typing
    if (typingAt.current) client.sendTyping(room.roomId, false, 0).catch(() => {});
    typingAt.current = 0;
  }, [room.roomId]);

  // ---------- @ mentions ----------
  const at = /(^|\s)@([^\s@]*)$/.exec(text.slice(0, caret));
  const atPos = at ? caret - at[2].length - 1 : -1;
  const suggestions: Suggestion[] = [];
  if (at && atPos !== closedAt) {
    const q = at[2].toLowerCase();
    suggestions.push(...room.getJoinedMembers()
      .filter((m) => m.userId !== me() && (m.name.toLowerCase().includes(q) || m.userId.toLowerCase().includes(q)))
      .slice(0, 6).map((m) => ({ id: m.userId, name: m.name, mxc: m.getMxcAvatarUrl() })));
    if ("room".startsWith(q) && room.currentState.mayTriggerNotifOfType("room", me())) suggestions.push({ id: "@room", name: "room" });
  }
  useEffect(() => { if (at) room.loadMembersIfNeeded().catch(() => {}); }, [!!at, room]); // eslint-disable-line react-hooks/exhaustive-deps

  function pick(s: Suggestion) {
    const insert = "@" + s.name + " ";
    const next = text.slice(0, atPos) + insert + text.slice(caret);
    if (s.id !== "@room") mentions.current.push({ name: s.name, id: s.id });
    const c = atPos + insert.length;
    setText(next);
    setCaret(c);
    requestAnimationFrame(() => { ta.current?.focus(); ta.current?.setSelectionRange(c, c); });
  }

  function typing(on: boolean) {
    const now = Date.now();
    if (on && now - typingAt.current < 3000) return;
    typingAt.current = on ? now : 0;
    client.sendTyping(room.roomId, on, 5000).catch(() => {});
  }

  /** body + optional HTML with mention links, and the m.mentions that decides who gets pinged. */
  function compose(body: string) {
    // "@Name" / "@user:server" typed out without picking from the list counts too (Telegram habit)
    const typed = room.getJoinedMembers().filter((m) => m.userId !== me())
      .flatMap((m) => [{ name: m.name, id: m.userId }, { name: m.userId.slice(1), id: m.userId }])
      .filter((m) => new RegExp("@" + escRe(m.name) + "(?![\\p{L}\\p{N}_])", "u").test(body));
    const html = formatMessage(body, [...mentions.current, ...typed]);
    const ids = new Set(html?.ids);
    const replyTo = mode?.kind === "reply" ? mode.ev.getSender() : undefined;
    if (replyTo && replyTo !== me()) ids.add(replyTo); // spec: a reply mentions who it replies to
    const everyone = /(^|\s)@room\b/.test(body) && room.currentState.mayTriggerNotifOfType("room", me());
    return {
      body,
      ...(html && { format: "org.matrix.custom.html", formatted_body: html.html }),
      "m.mentions": { user_ids: [...ids], ...(everyone && { room: true }) },
    };
  }

  function send() {
    const body = text.trim();
    if (!body && !mediaEdit) return;
    setText("");
    typing(false);
    if (mode?.kind === "edit") {
      const old = mode.ev.getContent();
      if (body !== (mediaEdit ? captionOf(old) : stripReplyFallback(old.body ?? ""))) {
        let next: ReturnType<typeof compose> & Record<string, unknown> = { msgtype: old.msgtype, ...compose(body) };
        if (mediaEdit) { // the replacement is the whole content: keep the file, swap the caption (empty = file name only)
          const base = { ...old };
          for (const k of ["format", "formatted_body", "m.relates_to", "m.mentions"]) delete base[k];
          const name = old.filename ?? old.body;
          next = { ...base, ...(body ? compose(body) : { body: name, "m.mentions": { user_ids: [] } }), filename: name } as typeof next;
        }
        const before = new Set<string>(old["m.mentions"]?.user_ids ?? []);
        client.sendMessage(room.roomId, {
          ...(mediaEdit ? next : { msgtype: old.msgtype }), body: "* " + next.body,
          "m.new_content": next,
          // only people newly mentioned by the edit get pinged
          "m.mentions": { user_ids: next["m.mentions"].user_ids.filter((id) => !before.has(id)) },
          "m.relates_to": { rel_type: "m.replace", event_id: mode.ev.getId()! },
        } as never);
      }
    } else {
      const content: Record<string, unknown> = { msgtype: MsgType.Text, ...compose(body) };
      if (mode?.kind === "reply") content["m.relates_to"] = { "m.in_reply_to": { event_id: mode.ev.getId() } };
      client.sendMessage(room.roomId, threadId, content as never);
    }
    mentions.current = [];
    setMode(null);
  }

  function sendFiles(list: File[], caption: string, asFile: boolean) {
    const replyTo = mode?.kind === "reply" ? mode.ev : undefined;
    setMode(null);
    setFiles(() => []);
    list.forEach((f, i) => startUpload(room, f, threadId, i ? undefined : replyTo, undefined, i ? undefined : caption, asFile));
  }

  function shareLocation() {
    if (!navigator.geolocation) return alertDialog("این مرورگر موقعیت مکانی را پشتیبانی نمی‌کند.");
    setLocating(true);
    navigator.geolocation.getCurrentPosition(async (p) => {
      setLocating(false);
      const { latitude: lat, longitude: lon, accuracy } = p.coords;
      const acc = Math.round(accuracy);
      if (!(await confirmDialog(`موقعیت فعلی شما (با دقت حدود ${num(acc)} متر) ارسال شود؟`))) return;
      const la = +lat.toFixed(6), lo = +lon.toFixed(6);
      // body keeps a map link for clients that don't show locations
      const content: Record<string, unknown> = ContentHelpers.makeLocationContent(`موقعیت مکانی ${osmUrl(la, lo)}`, `geo:${la},${lo};u=${acc}`, Date.now(), null, LocationAssetType.Self);
      if (mode?.kind === "reply") content["m.relates_to"] = { "m.in_reply_to": { event_id: mode.ev.getId() } };
      setMode(null);
      client.sendMessage(room.roomId, threadId, content as never).catch((e) => alertDialog(errText(e)));
    }, (e) => {
      setLocating(false);
      alertDialog(e.code === e.PERMISSION_DENIED ? "اجازه‌ی دسترسی به موقعیت مکانی داده نشد." : "موقعیت مکانی پیدا نشد.");
    }, { enableHighAccuracy: true, timeout: 15_000 });
  }

  function onKey(e: KeyboardEvent) {
    if (suggestions.length) {
      const i = sel % suggestions.length;
      if (e.key === "ArrowDown" || e.key === "ArrowUp") { e.preventDefault(); setSel((i + (e.key === "ArrowDown" ? 1 : suggestions.length - 1)) % suggestions.length); return; }
      if ((e.key === "Enter" || e.key === "Tab") && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); pick(suggestions[i]); return; }
      if (e.key === "Escape") { e.preventDefault(); setClosedAt(atPos); return; }
    }
    if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); send(); }
    else if (e.key === "Escape" && mode) setMode(null);
    else if (e.key === "ArrowUp" && !text && !mode) { // Telegram: ↑ edits your last message
      const tl = threadId ? room.getThread(threadId)?.liveTimeline : room.getLiveTimeline();
      const mine = tl?.getEvents().findLast((ev) => ev.getSender() === me() && !ev.status && isMessage(ev) && EDITABLE.includes(ev.getContent().msgtype ?? ""));
      if (mine) { e.preventDefault(); setMode({ kind: "edit", ev: mine }); }
    }
  }

  /** Emoji at the caret (replacing a selection); the caret ends up after it for the next pick. */
  function insert(s: string) {
    const el = ta.current;
    const a = el?.selectionStart ?? text.length, b = el?.selectionEnd ?? a;
    setText(text.slice(0, a) + s + text.slice(b));
    const c = a + s.length;
    setCaret(c);
    requestAnimationFrame(() => el?.setSelectionRange(c, c));
  }

  function pickSticker(s: Sticker) {
    setEmoji(false);
    sendSticker(room, threadId, s, mode?.kind === "reply" ? mode.ev : undefined).catch((e) => alertDialog(errText(e)));
    setMode(null);
  }

  const onPaste = (e: ClipboardEvent) => {
    const f = [...e.clipboardData.files];
    if (f.length) { e.preventDefault(); setFiles((x) => [...x, ...f]); }
  };

  const canRecord = !text.trim() && mode?.kind !== "edit" && "MediaRecorder" in window;

  return (
    <div className="composer">
      {mode && (
        <div className="composer-mode">
          <Icon name={mode.kind === "edit" ? "edit" : "reply"} />
          <div>
            <b>{mode.kind === "edit" ? "ویرایش پیام" : `پاسخ به ${bdi(senderName(mode.ev))}`}</b>
            <span>{previewText(mode.ev)}</span>
          </div>
          <button className="icon-btn" aria-label="لغو" onClick={() => setMode(null)}><Icon name="close" /></button>
        </div>
      )}
      {locating && <div className="composer-mode"><span className="spinner inline" /> در حال پیدا کردن موقعیت…</div>}
      {suggestions.length > 0 && (
        <div className="mention-list" role="listbox" aria-label="نام بردن">
          {suggestions.map((s, i) => (
            <button key={s.id} role="option" aria-selected={i === sel % suggestions.length} className={"user-row" + (i === sel % suggestions.length ? " on" : "")}
              onMouseDown={(e) => e.preventDefault()} onClick={() => pick(s)}>
              {s.id === "@room" ? <span className="mention-room"><Icon name="group" /></span> : <Avatar mxc={s.mxc} name={s.name} id={s.id} size={32} />}
              <span><b>{s.id === "@room" ? "@room" : s.name}</b><small dir="ltr">{s.id === "@room" ? "همه‌ی اعضای گفتگو" : s.id}</small></span>
            </button>
          ))}
        </div>
      )}
      {recording ? (
        <VoiceRecorder onDone={(v) => { setRecording(false); if (v) { startUpload(room, v.file, threadId, mode?.kind === "reply" ? mode.ev : undefined, v.extra); setMode(null); } }} />
      ) : (
        <div className="composer-row">
          <div className="attach">
            {/* outside the menu: picking closes the menu, and an unmounted input never gets its change event */}
            <input ref={fileInput} type="file" multiple hidden onChange={(e) => { const f = [...e.target.files!]; setFiles((x) => [...x, ...f]); e.target.value = ""; }} />
            <button className="icon-btn" title="پیوست" aria-label="پیوست" aria-expanded={menu} onClick={() => setMenu((m) => !m)}><Icon name="attach" /></button>
            {menu && (
              <>
                <div className="menu-backdrop" onClick={() => setMenu(false)} />
                <div className="attach-menu" role="menu" onClick={() => setMenu(false)}>
                  <button role="menuitem" onClick={() => fileInput.current?.click()}><Icon name="file" /> فایل یا عکس</button>
                  <button role="menuitem" onClick={() => setPollForm(true)}><Icon name="poll" /> نظرسنجی</button>
                  <button role="menuitem" onClick={shareLocation}><Icon name="location" /> موقعیت مکانی</button>
                </div>
              </>
            )}
          </div>
          <button className="icon-btn emoji-toggle" title="اموجی و استیکر" aria-label="اموجی و استیکر" aria-expanded={emoji} onClick={() => setEmoji((x) => !x)}><Icon name="smile" /></button>
          <textarea ref={ta} rows={1} value={text} placeholder={mediaEdit ? "کپشن…" : "پیام"} aria-label="پیام"
            onChange={(e) => { setText(e.target.value); setCaret(e.target.selectionStart); setSel(0); typing(!!e.target.value); }}
            onSelect={(e) => setCaret(e.currentTarget.selectionStart)}
            onKeyDown={onKey} onPaste={onPaste} onBlur={() => typingAt.current && typing(false)} />
          {canRecord ? (
            <button className="send-btn" onClick={() => setRecording(true)} title="پیام صوتی" aria-label="ضبط پیام صوتی"><Icon name="mic" /></button>
          ) : (
            <button className={"send-btn" + (text.trim() || mediaEdit ? " ready" : "")} onClick={send} aria-label="ارسال" disabled={!text.trim() && !mediaEdit}>
              <Icon name={mode?.kind === "edit" ? "check" : "send"} />
            </button>
          )}
        </div>
      )}
      {emoji && !recording && <EmojiPanel onEmoji={insert} onClose={() => setEmoji(false)} stickers={mode?.kind === "edit" ? undefined : { room, onPick: pickSticker }} />}
      {files.length > 0 && <SendFiles files={files} setFiles={setFiles} onSend={sendFiles} />}
      {pollForm && <PollForm room={room} threadId={threadId} onClose={() => setPollForm(false)} />}
    </div>
  );
}

function SendFiles({ files, setFiles, onSend }: { files: File[]; setFiles: Props["setFiles"]; onSend: (files: File[], caption: string, asFile: boolean) => void }) {
  const [caption, setCaption] = useState("");
  const [asFile, setAsFile] = useState(false);
  const [urls, setUrls] = useState<(string | null)[]>([]);
  useEffect(() => {
    const u = files.map((f) => (f.type.startsWith("image/") ? URL.createObjectURL(f) : null));
    setUrls(u);
    return () => u.forEach((x) => x && URL.revokeObjectURL(x));
  }, [files]);
  const close = () => setFiles(() => []);
  const send = () => onSend(files, caption.trim(), asFile);
  const remove = (i: number) => setFiles((x) => x.filter((_, j) => j !== i));
  const imgs = files.map((f, i) => i).filter((i) => urls[i]);
  const others = files.map((f, i) => i).filter((i) => !urls[i]);
  const title = files.length === 1 && files[0].type.startsWith("image/") ? "ارسال عکس" : `ارسال ${num(files.length)} فایل`;
  const x = (i: number) => <button className="icon-btn send-x" aria-label="حذف" onClick={() => remove(i)}><Icon name="close" size={14} /></button>;
  return (
    <div className="modal-backdrop" onClick={close}>
      <div className="modal" role="dialog" aria-label={title} onClick={(e) => e.stopPropagation()}
        onKeyDown={(e) => { if (e.key === "Escape") close(); }}>
        <h2>{title}</h2>
        {imgs.length > 0 && (
          <div className="send-grid">
            {imgs.map((i) => <div key={i} className="send-thumb"><img src={urls[i]!} alt="" />{x(i)}</div>)}
          </div>
        )}
        {others.map((i) => (
          <div key={i} className="file-row send-file">
            <span className="file-icon"><Icon name="file" /></span>
            <span><b>{bdi(files[i].name)}</b><small dir="auto">{formatSize(files[i].size)}</small></span>
            {x(i)}
          </div>
        ))}
        <textarea className="send-caption" rows={2} autoFocus value={caption} placeholder="کپشن…" aria-label="کپشن"
          onChange={(e) => setCaption(e.target.value)}
          onPaste={(e) => { const f = [...e.clipboardData.files]; if (f.length) { e.preventDefault(); setFiles((x) => [...x, ...f]); } }}
          onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); send(); } }} />
        {imgs.length > 0 && (
          <label className="switch-row"><span>ارسال به صورت فایل<small>بدون فشرده‌سازی</small></span>
            <input type="checkbox" role="switch" checked={asFile} onChange={(e) => setAsFile(e.target.checked)} /></label>
        )}
        <div className="modal-actions">
          <button onClick={close}>لغو</button>
          <button className="primary" onClick={send}>ارسال</button>
        </div>
      </div>
    </div>
  );
}

/** Wraps the chat column; dropping files anywhere on it hands them to `onFiles`. */
export function DropZone({ onFiles, children }: { onFiles: (f: File[]) => void; children: ReactNode }) {
  const [over, setOver] = useState(false);
  const depth = useRef(0);
  const has = (e: DragEvent) => e.dataTransfer.types.includes("Files");
  return (
    <div className="drop-zone"
      onDragEnter={(e) => { if (has(e)) { depth.current++; setOver(true); } }}
      onDragLeave={(e) => { if (has(e) && --depth.current <= 0) { depth.current = 0; setOver(false); } }}
      onDragOver={(e) => has(e) && e.preventDefault()}
      onDrop={(e) => { if (!has(e)) return; e.preventDefault(); depth.current = 0; setOver(false); onFiles([...e.dataTransfer.files]); }}>
      {children}
      {over && <div className="drop-overlay"><Icon name="attach" size={40} />فایل‌ها را اینجا رها کنید</div>}
    </div>
  );
}
