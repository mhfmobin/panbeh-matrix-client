import { useMemo } from "react";
import type { IPreviewUrlResponse, MatrixEvent } from "matrix-js-sdk";
import { client, mediaUrl } from "../matrix.ts";
import { usePromise } from "../hooks.ts";
import { stripReplyFallback } from "./common.tsx";
import { LINK_SRC, linkHref } from "../logic.ts";
import { loadPrefs } from "./Settings.tsx";

const TEXT = ["m.text", "m.notice", "m.emote"];
const URL_RE = new RegExp(LINK_SRC, "gi");
const cache = new Map<string, Promise<IPreviewUrlResponse | null>>();

/** First web link worth previewing: not a matrix.to pill, not inside code. */
function firstUrl(c: Record<string, unknown>) {
  // bodies keep their Markdown, so code is still in backticks
  const body = typeof c.body === "string" ? stripReplyFallback(c.body).replace(/```[\s\S]*?```|`[^`\n]*`/g, "") : "";
  return body.match(URL_RE)?.map(linkHref).find((u) => !u.startsWith("https://matrix.to/"));
}

/** Card under a text message for its first link, from the homeserver's preview_url. Nothing on failure. */
export function LinkPreview({ ev }: { ev: MatrixEvent }) {
  const c = ev.getContent();
  const url = TEXT.includes(c.msgtype ?? "") && !ev.isRedacted() ? firstUrl(c) : undefined;
  const p = useMemo(() => {
    if (!url || !loadPrefs().previews) return null;
    let q = cache.get(url);
    if (!q) cache.set(url, (q = client.getUrlPreview(url, ev.getTs()).catch(() => null))); // no previews on this server: stays null
    return q;
  }, [url]); // eslint-disable-line react-hooks/exhaustive-deps
  const data = usePromise(p);
  const mxc = data?.["og:image"];
  const img = usePromise(useMemo(() => (mxc?.startsWith("mxc://") ? mediaUrl({ url: mxc }, { w: 640, h: 640 }) : null), [mxc]));
  const str = (k: string) => (typeof data?.[k] === "string" ? (data[k] as string).trim() : "");
  const title = str("og:title"), desc = str("og:description");
  if (!url || !(title || desc)) return null;
  return (
    <a className="link-preview" href={url} target="_blank" rel="noreferrer noopener" dir="auto" onClick={(e) => e.stopPropagation()}>
      <span className="lp-text">
        <small dir="auto">{str("og:site_name") || url.split("/")[2]}</small>
        {title && <b dir="auto">{title}</b>}
        {desc && <span className="lp-desc" dir="auto">{desc}</span>}
      </span>
      {img && <img src={img} alt="" />}
    </a>
  );
}
