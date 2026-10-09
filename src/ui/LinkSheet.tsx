import { useMemo } from "react";
import { renderSVG } from "uqr";
import { Icon } from "../icons.tsx";
import { Sheet } from "./common.tsx";
import { copyMessages } from "./Message.tsx";

/** A matrix.to link as a QR code (always dark on white, so it scans in the dark theme too), with copy and share. */
export function LinkSheet({ title, link, hint, onClose }: { title: string; link: string; hint?: string; onClose: () => void }) {
  const svg = useMemo(() => renderSVG(link, { border: 2 }), [link]);
  return (
    <Sheet title={title} onClose={onClose}>
      <div className="qr" role="img" aria-label="کد QR" dangerouslySetInnerHTML={{ __html: svg }} />
      <bdi dir="ltr" className="qr-link">{link}</bdi>
      {hint && <p className="muted">{hint}</p>}
      <button className="primary" onClick={() => void copyMessages(link)}><Icon name="copy" size={18} /> کپی پیوند</button>
      {"share" in navigator && (
        <button className="secondary" onClick={() => navigator.share({ url: link }).catch(() => { /* cancelled */ })}><Icon name="forward" size={18} /> اشتراک‌گذاری</button>
      )}
    </Sheet>
  );
}
