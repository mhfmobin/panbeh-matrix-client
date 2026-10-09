import { useEffect, useRef, useState, type ReactNode } from "react";
import { App } from "@capacitor/app";
import { biometricAvailable, biometricUnlock } from "../native.ts";
import { pushBack } from "../back.ts";
import { logoutAll } from "../matrix.ts";
import { checkPin, lockConf, removeLock, setLockOpts, setPin, unlock, unlockedByBiometric, waitLeft } from "../lock.ts";
import { num } from "../logic.ts";
import { Icon } from "../icons.tsx";
import { Select, Sheet, toast } from "./common.tsx";

const MIN = 4, MAX = 8;

/** Digits by tap or keyboard. With `len` it ends by itself at that length; otherwise a confirm key once there are MIN.
 *  `shake` bumps on a wrong PIN; `extra` sits left of 0 (the fingerprint key). */
function PinPad({ title, sub, len, disabled, shake, extra, onDone }:
  { title: string; sub?: string; len?: number; disabled?: boolean; shake?: number; extra?: ReactNode; onDone: (pin: string) => void }) {
  const [pin, setPin] = useState("");
  useEffect(() => { setPin(""); }, [shake, title]);
  const press = (k: string) => {
    if (disabled) return;
    if (k === "⌫") return setPin((p) => p.slice(0, -1));
    if (k === "ok") { if (pin.length >= MIN) onDone(pin); return; }
    const next = (pin + k).slice(0, len ?? MAX);
    setPin(next);
    if (len && next.length === len) onDone(next);
  };
  const pressRef = useRef(press);
  pressRef.current = press;
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.target instanceof Element && e.target.closest("input, textarea")) return;
      const d = "۰۱۲۳۴۵۶۷۸۹".indexOf(e.key); // Persian keyboard digits too
      if (/^[0-9]$/.test(e.key) || d >= 0) pressRef.current(String(d >= 0 ? d : e.key));
      else if (e.key === "Backspace") pressRef.current("⌫");
      else if (e.key === "Enter") pressRef.current("ok");
      else return;
      e.preventDefault();
    };
    addEventListener("keydown", onKey);
    return () => removeEventListener("keydown", onKey);
  }, []); // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <div className="pinpad">
      <b>{title}</b>
      {sub && <small className="muted">{sub}</small>}
      <div className={"pin-dots" + (shake ? " shake" : "")} key={shake} aria-label={`${num(pin.length)} رقم`}>
        {Array.from({ length: len ?? Math.max(MIN, pin.length) }, (_, i) => <i key={i} className={i < pin.length ? "on" : ""} />)}
      </div>
      <div className="pin-keys" dir="ltr">
        {["1", "2", "3", "4", "5", "6", "7", "8", "9"].map((k) => <button key={k} disabled={disabled} onClick={() => press(k)}>{num(+k)}</button>)}
        {len ? (extra ?? <span />) : <button className="pin-ok" disabled={disabled || pin.length < MIN} onClick={() => press("ok")} aria-label="تأیید"><Icon name="check" /></button>}
        <button disabled={disabled} onClick={() => press("0")}>{num(0)}</button>
        <button disabled={disabled || !pin} onClick={() => press("⌫")} aria-label="پاک کردن"><Icon name="backspace" /></button>
      </div>
    </div>
  );
}

/** Seconds left to wait after too many wrong tries, ticking down. */
function useWait() {
  const [left, setLeft] = useState(waitLeft);
  useEffect(() => {
    if (!left) return;
    const t = setTimeout(() => setLeft(waitLeft()), 1000);
    return () => clearTimeout(t);
  }, [left]);
  return [Math.ceil(left / 1000), () => setLeft(waitLeft())] as const;
}

/** Covers the app until the PIN (or a finger) is given. */
export function LockScreen() {
  const [c] = useState(() => lockConf()!); // kept: forgetting deletes it while this is still on screen
  const [shake, setShake] = useState(0);
  const [wait, rewait] = useWait();
  const [bio, setBio] = useState(false);
  const tryBio = () => void biometricUnlock().then((ok) => { if (ok) unlockedByBiometric(); });
  useEffect(() => pushBack(() => { void App.minimizeApp(); }), []); // Android back leaves the app, it doesn't reach what's underneath
  useEffect(() => {
    if (!c.bio) return;
    biometricAvailable().then((a) => { setBio(a); if (a) tryBio(); });
  }, []); // eslint-disable-line react-hooks/exhaustive-deps
  // asked here, not with confirmDialog: dialogs sit under the lock screen, and lifting them would show the app's over it
  const [forgetting, setForgetting] = useState<"ask" | "busy" | null>(null);
  const forget = () => { setForgetting("busy"); removeLock(true); void logoutAll(); }; // logout reloads
  return (
    <div className="lock-screen wallpaper" role="dialog" aria-modal="true" aria-label="پنبه قفل است">
      <div className="login-logo">✦</div>
      {forgetting ? (
        <div className="lock-forget">
          <p>رمز را فراموش کرده‌اید؟ قفل برداشته می‌شود و همه‌ی حساب‌ها از این دستگاه خارج می‌شوند. پس از ورود دوباره، پیام‌های رمزنگاری‌شده‌ی قبلی فقط با کلید بازیابی یا تأیید از دستگاهی دیگر خوانده می‌شوند.</p>
          <div className="modal-actions">
            <button disabled={forgetting === "busy"} onClick={() => setForgetting(null)}>لغو</button>
            <button className="danger" disabled={forgetting === "busy"} onClick={forget}>{forgetting === "busy" ? "در حال خروج…" : "خروج از همه‌ی حساب‌ها"}</button>
          </div>
        </div>
      ) : <>
      <PinPad title="رمز پنبه را وارد کنید" len={c.len} shake={shake} disabled={wait > 0}
        sub={wait > 0 ? `${num(wait)} ثانیه‌ی دیگر دوباره امتحان کنید` : undefined}
        extra={bio ? <button onClick={tryBio} aria-label="اثر انگشت"><Icon name="fingerprint" /></button> : undefined}
        onDone={(pin) => void unlock(pin).then((ok) => { if (!ok) { setShake((n) => n + 1); rewait(); navigator.vibrate?.(200); } })} />
      <button className="plain lock-forgot" onClick={() => setForgetting("ask")}>رمز را فراموش کرده‌اید؟</button>
      </>}
    </div>
  );
}

const AFTER: [number, string][] = [[0, "بلافاصله"], [1, "پس از ۱ دقیقه"], [5, "پس از ۵ دقیقه"], [60, "پس از ۱ ساعت"]];

/** Settings › قفل برنامه: set a PIN; once there is one, the current PIN first, then timing, fingerprint, change, remove. */
export function LockSettings({ onClose }: { onClose: () => void }) {
  const [conf, setConf] = useState(lockConf);
  const [step, setStep] = useState<"verify" | "new" | "repeat" | "options">(conf ? "verify" : "new");
  const [first, setFirst] = useState("");
  const [shake, setShake] = useState(0);
  const [wait, rewait] = useWait();
  const [bioOk, setBioOk] = useState(false);
  useEffect(() => { biometricAvailable().then(setBioOk); }, []);
  const refresh = () => setConf(lockConf());
  const wrong = () => { setShake((n) => n + 1); navigator.vibrate?.(200); };

  return (
    <Sheet title="قفل برنامه" onClose={onClose}>
      {step === "verify" && conf && (
        <PinPad title="رمز فعلی را وارد کنید" len={conf.len} shake={shake} disabled={wait > 0}
          sub={wait > 0 ? `${num(wait)} ثانیه‌ی دیگر دوباره امتحان کنید` : undefined}
          onDone={(pin) => void checkPin(pin).then((ok) => { if (ok) setStep("options"); else { wrong(); rewait(); } })} />
      )}
      {step === "new" && (
        <PinPad title="رمز تازه" sub={`${num(MIN)} تا ${num(MAX)} رقم`} shake={shake}
          onDone={(pin) => { setFirst(pin); setStep("repeat"); }} />
      )}
      {step === "repeat" && (
        <PinPad title="رمز را دوباره وارد کنید" len={first.length} shake={shake}
          onDone={(pin) => {
            if (pin !== first) { wrong(); setStep("new"); toast("دو رمز یکی نبود؛ دوباره"); return; }
            void setPin(pin).then(() => { refresh(); setStep("options"); toast("قفل برنامه روشن شد"); });
          }} />
      )}
      {step === "options" && conf && <>
        <p className="muted">پنبه با باز شدن و پس از مدتی بیرون بودن از برنامه، رمز می‌خواهد. این قفل صفحه است و داده‌های روی دستگاه را رمزنگاری نمی‌کند.</p>
        <label className="select-row">قفل خودکار
          <Select value={conf.after} options={AFTER} onChange={(v) => { setLockOpts({ after: v }); refresh(); }} />
        </label>
        {bioOk && (
          <label className="switch-row"><span>باز کردن با اثر انگشت یا چهره</span>
            <input type="checkbox" role="switch" checked={conf.bio} onChange={(e) => { setLockOpts({ bio: e.target.checked }); refresh(); }} /></label>
        )}
        <button className="user-row" onClick={() => setStep("new")}>
          <span className="device-box"><Icon name="lock" /></span><span><b>تغییر رمز</b></span>
        </button>
        <button className="danger" onClick={() => { removeLock(); onClose(); toast("قفل برنامه خاموش شد"); }}>خاموش کردن قفل</button>
      </>}
    </Sheet>
  );
}
