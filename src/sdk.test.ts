// Guards for matrix-js-sdk internals Panbeh relies on: they aren't public API, so an upgrade could drop them silently.
import { test } from "node:test";
import assert from "node:assert/strict";
import { RustCrypto } from "matrix-js-sdk/lib/rust-crypto/rust-crypto.js";

test("scheduleMessage: the crypto backend (client.getCrypto()) can still encrypt an event", () => {
  assert.equal(typeof RustCrypto.prototype.encryptEvent, "function");
});

test("historyCache liveRun: linking a timeline to an older one drops its back token (the older one's is the run's)", async () => {
  const { EventTimeline } = await import("matrix-js-sdk");
  const set = {} as ConstructorParameters<typeof EventTimeline>[0];
  const live = new EventTimeline(set), older = new EventTimeline(set);
  live.setPaginationToken("t1", EventTimeline.BACKWARDS);
  live.setNeighbouringTimeline(older, EventTimeline.BACKWARDS);
  assert.equal(live.getPaginationToken(EventTimeline.BACKWARDS), null);
});
