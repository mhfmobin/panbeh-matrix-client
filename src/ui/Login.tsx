import { useEffect, useRef, useState, type FormEvent } from "react";
import { login, loginMode, startOAuth } from "../matrix.ts";
import { normalizeServer } from "../logic.ts";
import { errText } from "./common.tsx";
import { isDesktop } from "../desktop.ts";

export function Login({ onDone, initialError, onCancel }: { onDone: () => void; initialError?: unknown; onCancel?: () => void }) {
  const [server, setServer] = useState(localStorage.getItem("panbeh.lastServer") ?? "");
  const [user, setUser] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(initialError ? errText(initialError) : "");
  const [inBrowser, setInBrowser] = useState(false); // desktop: the login page is open in the browser
  // "" = not checked yet (or unreachable)
  const [mode, setMode] = useState<"" | "checking" | "oauth" | "password">("");
  const latest = useRef(server);
  latest.current = server;

  async function check(s: string) {
    setMode("checking");
    try {
      const m = await loginMode(normalizeServer(s));
      if (latest.current === s) setMode(m);
    } catch (err) {
      if (latest.current === s) { setMode(""); setError(errText(err)); }
    }
  }

  // once typing stops, ask the server how it logs in
  useEffect(() => {
    setMode("");
    if (!server.trim()) return;
    const t = setTimeout(() => check(server), 700);
    return () => clearTimeout(t);
  }, [server]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (mode === "") return check(server); // Enter before the check ran, or retry after an error
    setBusy(true);
    setError("");
    try {
      localStorage.setItem("panbeh.lastServer", server);
      if (mode === "oauth") {
        await startOAuth(server); // navigates away; the desktop app opens the browser and stays
        if (isDesktop) { setInBrowser(true); setBusy(false); }
        return;
      }
      await login(server, user, password);
      onDone();
    } catch (err) {
      setError(errText(err));
      setBusy(false);
    }
  }

  return (
    <div className="login wallpaper">
      <form className="login-card" onSubmit={submit}>
        <div className="login-logo">✦</div>
        <h1>ورود به ماتریکس</h1>
        <label>
          سرور
          <input dir="ltr" value={server} onChange={(e) => { setServer(e.target.value); setError(""); }} placeholder="matrix.example.org:8448" required autoFocus />
        </label>
        {mode === "password" && <>
          <label>
            نام کاربری
            <input dir="ltr" value={user} onChange={(e) => setUser(e.target.value)} placeholder="alice" autoComplete="username" required autoFocus />
          </label>
          <label>
            رمز عبور
            <input dir="ltr" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
          </label>
        </>}
        {error && <p className="error" role="alert">{error}</p>}
        {inBrowser && !error && <p className="muted">ورود را در مرورگر ادامه دهید؛ پس از آن به پنبه برمی‌گردید.</p>}
        <button className="primary" disabled={busy || mode === "checking"}>
          {busy ? "در حال ورود…" : { "": "ادامه", checking: "در حال بررسی سرور…", oauth: "ادامه در صفحه‌ی ورود سرور", password: "ورود" }[mode]}
        </button>
        {onCancel && <button type="button" onClick={onCancel}>انصراف</button>}
      </form>
    </div>
  );
}
