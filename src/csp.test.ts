import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const read = (f: string) => readFileSync(new URL("../" + f, import.meta.url), "utf8");

test("nginx and desktop send index.html's CSP without inline scripts, plus no framing", () => {
  const meta = read("index.html").match(/http-equiv="Content-Security-Policy" content="([^"]+)"/)![1];
  const header = meta.replace("script-src 'self' 'unsafe-inline'", "script-src 'self'") + "; frame-ancestors 'none'";
  assert.notEqual(header, meta + "; frame-ancestors 'none'");
  assert.equal(read("nginx.conf").match(/Content-Security-Policy "([^"]+)"/)![1], header);
  assert.equal(read("desktop/main.cjs").match(/const CSP = "([^"]+)"/)![1], header);
});
