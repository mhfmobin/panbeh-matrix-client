import { useEffect } from "react";
import { createRoot } from "react-dom/client";
import { pushBack } from "../back.ts";

type Opts = { ok?: string; danger?: boolean };

function Dialog({ text, ok, danger, cancel, done }: Opts & { text: string; cancel: boolean; done: (v: boolean) => void }) {
  useEffect(() => pushBack(() => { done(false); }), [done]);
  return (
    <div className="modal-backdrop" onClick={() => done(false)}>
      <div className="modal" role="dialog" aria-modal="true" aria-label={text} onClick={(e) => e.stopPropagation()}
        onKeyDown={(e) => { if (e.key === "Escape") { e.stopPropagation(); done(false); } }}>
        <p dir="auto">{text}</p>
        <div className="modal-actions">
          {cancel && <button onClick={() => done(false)}>لغو</button>}
          <button className={danger ? "danger" : "primary"} autoFocus onClick={() => done(true)}>{ok ?? (cancel ? "تأیید" : "باشه")}</button>
        </div>
      </div>
    </div>
  );
}

function show(text: string, cancel: boolean, o: Opts = {}) {
  return new Promise<boolean>((resolve) => {
    const host = document.body.appendChild(document.createElement("div"));
    const root = createRoot(host);
    const done = (v: boolean) => { root.unmount(); host.remove(); resolve(v); };
    root.render(<Dialog text={text} cancel={cancel} done={done} {...o} />);
  });
}

/** In-app replacement for window.confirm. */
export const confirmDialog = (text: string, o?: Opts) => show(text, true, o);
/** In-app replacement for window.alert. */
export const alertDialog = (text: string) => { void show(text, false); };
