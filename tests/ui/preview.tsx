import { useState } from "react";
import { createRoot } from "react-dom/client";
import { createClient, EventType, MatrixEvent, Room, RoomEvent } from "matrix-js-sdk";
import { __usePreviewClient } from "../../src/matrix.ts";
import { useTick } from "../../src/hooks.ts";
import { Message, type Actions } from "../../src/ui/Message.tsx";
import "../../src/styles.css";

const userId = "@preview:local", roomId = "!preview:local";
const sdk = createClient({ baseUrl: "http://127.0.0.1:1", userId, fetchFn: async () => { throw new Error("Offline preview"); } });
const room = new Room(roomId, sdk, userId);
sdk.store.storeRoom(room);
__usePreviewClient(sdk);
const messages = [
  "روی این پیام کلیک کن یا راست‌کلیک کن تا منو باز شود. ری‌اکشن‌های بیشتر را باز کن و بیرونش بزن تا به منوی قبلی برگردی.",
  "برای انتخاب بخشی از این متن، اول روی پیام نگه دار تا خود پیام انتخاب شود. انگشتت را بردار؛ بعد دوباره روی یکی از کلمه‌ها نگه دار.",
  "این هم پیام خودت برای امتحان گزینه‌های ویرایش و کپی. باز و بسته شدن منو باید کوتاه و نرم باشد.",
].map((body, i) => new MatrixEvent({
  type: EventType.RoomMessage, event_id: `$preview${i}`, room_id: roomId,
  sender: i === 2 ? userId : "@friend:local", origin_server_ts: Date.now() - (3 - i) * 60000,
  content: { msgtype: "m.text", body },
}));
await room.addLiveEvents(messages, {});

// Reactions stay in this in-memory room, exercising the real picker and reaction chips.
let nextId = 0;
sdk.sendEvent = async (_roomId, threadOrType, typeOrContent, eventContent) => {
  const type = typeof threadOrType === "string" && threadOrType.startsWith("m.") ? threadOrType : typeOrContent;
  const content = typeof typeOrContent === "object" ? typeOrContent : eventContent;
  const event_id = `$reaction${++nextId}`;
  await room.addLiveEvents([new MatrixEvent({ type, content, sender: userId, room_id: roomId, event_id, origin_server_ts: Date.now() })], {});
  return { event_id };
};
sdk.redactEvent = async (_roomId, redacts) => {
  const event_id = `$redaction${++nextId}`;
  await room.addLiveEvents([new MatrixEvent({ type: EventType.RoomRedaction, redacts, content: {}, sender: userId, room_id: roomId, event_id, origin_server_ts: Date.now() })], {});
  return { event_id };
};
sdk.sendStateEvent = async (_roomId, type, content, state_key = "") => {
  const event_id = `$state${++nextId}`;
  room.currentState.setStateEvents([new MatrixEvent({ type, content, state_key, sender: userId, room_id: roomId, event_id, origin_server_ts: Date.now() })]);
  return { event_id };
};

const pause = (ms = 40) => new Promise((resolve) => setTimeout(resolve, ms));
function check(value: unknown, label: string) { if (!value) throw new Error(label); }
function pointer(el: Element, type: string) {
  el.dispatchEvent(new PointerEvent(type, { bubbles: true, cancelable: true, pointerType: "touch", pointerId: 1, clientX: 160, clientY: 260 }));
}

function Preview() {
  useTick(room, [RoomEvent.Timeline, RoomEvent.Redaction]);
  const [selection, setSelection] = useState<ReadonlySet<string>>();
  const [status, setStatus] = useState("پیام‌ها نمونه‌اند؛ برای تست نیازی به ورود نیست.");
  const [results, setResults] = useState<string[]>([]);
  const [running, setRunning] = useState(false);
  const [version, setVersion] = useState(0);
  const reset = () => { getSelection()?.removeAllRanges(); setSelection(undefined); setVersion((n) => n + 1); };
  const report = (name: string) => () => setStatus(name);
  const actions: Actions = {
    reply: report("پاسخ انتخاب شد"), edit: report("ویرایش انتخاب شد"), forward: report("ارسال انتخاب شد"),
    view: report("نمایش رسانه"), info: report("اطلاعات پیام"), reactions: report("فهرست ری‌اکشن‌ها"), profile: report("پروفایل"),
    selection,
    select: (ev) => setSelection((old) => {
      const next = new Set(old);
      if (!next.delete(ev.getId()!)) next.add(ev.getId()!);
      return next.size ? next : undefined;
    }),
  };
  const run = async () => {
    setRunning(true); setResults([]); reset();
    const passed: string[] = [];
    const pass = (label: string) => { passed.push("✓ " + label); setResults([...passed]); };
    try {
      await pause();
      const text = document.querySelector(".msg-text")!;
      const row = text.closest(".msg")!;
      pointer(text, "pointerdown"); await pause(550);
      check(row.classList.contains("selected"), "پیام با نگه‌داشتن انتخاب نشد");
      check(!row.classList.contains("text-selectable"), "انتخاب متن با نگه‌داشتن اول فعال شد");
      const first = new MouseEvent("contextmenu", { bubbles: true, cancelable: true });
      text.dispatchEvent(first);
      check(first.defaultPrevented, "منوی بومی در نگه‌داشتن اول مسدود نشد");
      pointer(text, "pointerup"); text.dispatchEvent(new MouseEvent("click", { bubbles: true }));
      pass("نگه‌داشتن اول فقط پیام را انتخاب می‌کند");
      await pause(400);
      pointer(text, "pointerdown"); await pause();
      check(row.classList.contains("text-selectable"), "انتخاب متن با لمس دوم فعال نشد");
      const second = new MouseEvent("contextmenu", { bubbles: true, cancelable: true });
      text.dispatchEvent(second);
      check(!second.defaultPrevented, "انتخاب بومی متن در لمس دوم مسدود شد");
      // Emulate the browser's resulting selection; real touch handles need a phone.
      const range = document.createRange(); range.selectNodeContents(text);
      getSelection()!.removeAllRanges(); getSelection()!.addRange(range);
      pointer(text, "pointerup"); text.dispatchEvent(new MouseEvent("click", { bubbles: true })); await pause();
      check(row.classList.contains("selected") && !getSelection()!.isCollapsed, "پایان انتخاب متن، پیام را از انتخاب خارج کرد");
      pass("لمس دوم اجازهٔ انتخاب متن می‌دهد و انتخاب پیام حفظ می‌شود");
      reset(); await pause();
      const target = document.querySelector(".msg-text")!;
      pointer(target, "pointerdown"); pointer(target, "pointerup"); target.dispatchEvent(new MouseEvent("click", { bubbles: true, clientX: 160, clientY: 260 }));
      await pause(400);
      check(document.querySelector('[role="menu"]'), "منوی پیام باز نشد");
      (document.querySelector(".msg-menu-react .more") as HTMLButtonElement).click(); await pause(300);
      check(document.querySelector(".react-picker .emoji-panel") && !document.querySelector('[role="menu"]'), "پنل ری‌اکشن جای منوی پیام باز نشد");
      (document.querySelector(".react-picker .menu-backdrop") as HTMLElement).click(); await pause(400);
      check(document.querySelector('[role="menu"]') && !document.querySelector(".react-picker"), "کلیک بیرون، منوی پیام را برنگرداند");
      pass("بستن ری‌اکشن‌های بیشتر به منوی قبلی برمی‌گردد");
      window.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true })); await pause(200);
      check(!document.querySelector('[role="menu"]'), "منوی پیام بسته نشد");
      pass("بستن منوی برگشته هم درست کار می‌کند");
      setStatus("تست‌های تعاملی موفق بودند. انتخاب کلمه با انگشت را روی گوشی هم امتحان کن.");
    } catch (error) { setResults([...passed, "✗ " + (error as Error).message]); setStatus("یک تست ناموفق بود."); }
    finally { reset(); setRunning(false); }
  };
  return <main className="preview">
    <header className="preview-head"><h1>تست منوی پیام</h1>
      <p>با ماوس راست‌کلیک کن؛ روی گوشی یک ضربه منو را باز می‌کند. نگه‌داشتن اول پیام و نگه‌داشتن دوم متن را انتخاب می‌کند.</p>
      <div className="preview-controls">
        <button disabled={running} onClick={run}>اجرای تست‌های تعاملی</button>
        <button disabled={running} onClick={reset}>پاک کردن انتخاب</button>
        <button onClick={() => { document.documentElement.dataset.theme = document.documentElement.dataset.theme === "dark" ? "light" : "dark"; }}>روشن / تیره</button>
      </div>
    </header>
    {selection && <div className="select-bar"><span>پیام انتخاب شد</span><button onClick={() => setSelection(undefined)}>لغو انتخاب</button></div>}
    <div className="preview-chat" key={version}>{messages.map((ev) => <Message key={ev.getId()} ev={ev} room={room} first last actions={actions} />)}</div>
    <div className="preview-status" role="status">{status}</div>
    <ul className="preview-results">{results.map((result) => <li key={result}>{result}</li>)}</ul>
  </main>;
}

createRoot(document.getElementById("root")!).render(<Preview />);
