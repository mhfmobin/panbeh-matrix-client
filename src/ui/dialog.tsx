import { t } from "../i18n.ts";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { createRoot } from "react-dom/client";
import { pushBack } from "../back.ts";
import { reducedMotion } from "./useDismiss.ts";
import type { Room } from "matrix-js-sdk";

type Opts = { ok?: string; danger?: boolean; body?: ReactNode }; // body: shown instead of the text, which still names the dialog
/** filter: applied as the user types, so disallowed characters never appear; hint: a line under the field. */
type Input = { placeholder?: string; value?: string; filter?: (v: string) => string; hint?: string };

function Dialog({ text, ok, danger, body, cancel, input, done }: Opts & { text: string; cancel: boolean; input?: Input; done: (v: boolean, value: string) => void }) {
  const [closing, setClosing] = useState(false);
  const [value, setValue] = useState(input?.value ?? "");
  const left = useRef(false);
  const finish = (v: boolean) => { // let the exit animation play before the root unmounts
    if (left.current) return;
    left.current = true;
    if (reducedMotion()) return done(v, value);
    setClosing(true);
    setTimeout(() => done(v, value), 150);
  };
  useEffect(() => pushBack(() => { finish(false); }), []);
  return (
    <div className={"modal-backdrop" + (closing ? " closing" : "")} onClick={() => finish(false)}>
      <div className="modal" role="dialog" aria-modal="true" aria-label={text} onClick={(e) => e.stopPropagation()}
        onKeyDown={(e) => { if (e.key === "Escape") { e.stopPropagation(); finish(false); } }}>
        {body ?? <p dir="auto">{text}</p>}
        {input && <input dir="auto" autoFocus value={value} placeholder={input.placeholder} aria-label={text}
          // not mid-composition (IME keyboards): the composed text comes through again once it's done
          onChange={(e) => setValue(input.filter && !(e.nativeEvent as InputEvent).isComposing ? input.filter(e.target.value) : e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); finish(true); } }} />}
        {input?.hint && <small className="muted">{input.hint}</small>}
        <div className="modal-actions">
          {cancel && <button onClick={() => finish(false)}>{t("common.cancel")}</button>}
          <button className={danger ? "danger" : "primary"} autoFocus={!input} onClick={() => finish(true)}>{ok ?? (cancel ? t("common.confirm") : t("common.ok"))}</button>
        </div>
      </div>
    </div>
  );
}

function show(text: string, cancel: boolean, o: Opts = {}, input?: Input) {
  return new Promise<string | null>((resolve) => {
    const host = document.body.appendChild(document.createElement("div"));
    const root = createRoot(host);
    const done = (v: boolean, value: string) => { root.unmount(); host.remove(); resolve(v ? value : null); };
    root.render(<Dialog text={text} cancel={cancel} input={input} done={done} {...o} />);
  });
}

/** In-app replacement for window.confirm. */
export const confirmDialog = (text: string, o?: Opts) => show(text, true, o).then((v) => v !== null);
/** In-app replacement for window.prompt: the trimmed text, "" if left empty, null if cancelled. */
export const promptDialog = (text: string, o?: Opts & Input) => show(text, true, o, o ?? {}).then((v) => v?.trim() ?? null);
/** In-app replacement for window.alert. */
export const alertDialog = (text: string, o?: Opts) => { void show(text, false, o); };

/** Photos the editor takes: not GIFs (they'd stop moving) or SVGs. */
export const editable = (f: File) => /^image\/(jpeg|png|webp|bmp|avif|heic|heif)$/.test(f.type);

/** The photo editor, full screen: the edited file (the same one if nothing changed), or null if cancelled.
 *  avatar: a square crop shown as a circle. Loaded on first use, it stays out of the main bundle. */
/** A picked profile or room photo, cropped square in the editor first (GIFs and SVGs as they are); null: cancelled. */
export const editAvatar = (f: File) => (editable(f) ? editImage(f, { avatar: true }) : Promise.resolve(f));

export async function editImage(file: File, o: { avatar?: boolean; room?: Room } = {}): Promise<File | null> {
  const { ImageEditor } = await import("./ImageEditor.tsx");
  return new Promise((resolve) => {
    const host = document.body.appendChild(document.createElement("div"));
    const root = createRoot(host);
    root.render(<ImageEditor file={file} {...o} done={(f) => { root.unmount(); host.remove(); resolve(f); }} />);
  });
}
