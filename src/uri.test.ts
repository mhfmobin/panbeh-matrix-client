import { test } from "node:test";
import assert from "node:assert/strict";
import { parseMatrixHash, parseMatrixLink } from "./uri.ts";

test("matrix.to user, room, alias, event and via", () => {
  assert.deepEqual(parseMatrixLink("https://matrix.to/#/@a:x.org"), { kind: "user", id: "@a:x.org", via: [] });
  assert.deepEqual(parseMatrixLink("https://matrix.to/#/%23r%3Ax.org"), { kind: "roomAlias", id: "#r:x.org", via: [] });
  assert.deepEqual(parseMatrixLink("https://matrix.to/#/!id:x.org/$ev?via=a.org&via=b.org"),
    { kind: "room", id: "!id:x.org", eventId: "$ev", via: ["a.org", "b.org"] });
  assert.equal(parseMatrixLink("https://matrix.to/#/#r:x.org/$ev")?.eventId, "$ev");
});

test("matrix: URIs", () => {
  assert.deepEqual(parseMatrixLink("matrix:u/a:x.org?action=chat"), { kind: "user", id: "@a:x.org", via: [], action: "chat" });
  assert.deepEqual(parseMatrixLink("matrix:r/r:x.org"), { kind: "roomAlias", id: "#r:x.org", via: [] });
  assert.deepEqual(parseMatrixLink("matrix:roomid/id:x.org/e/ev?via=a.org&action=join"),
    { kind: "room", id: "!id:x.org", eventId: "$ev", via: ["a.org"], action: "join" });
  assert.equal(parseMatrixLink("matrix:r/r%3Ax.org/e/ev")?.eventId, "$ev");
  assert.equal(parseMatrixLink("MATRIX:u/a:x.org")?.kind, "user");
});

test("rejects malformed and foreign links", () => {
  for (const s of ["https://example.com/#/@a:x.org", "http://matrix.to/#/@a:x.org", "https://matrix.to/#/foo",
    "https://matrix.to/#/@a:x.org/$ev", "https://matrix.to/#/!r:x/notevent", "https://matrix.to/#/!r:x/$e/extra",
    "matrix:x/a:b", "matrix:u", "matrix:u/a:x/e/ev", "matrix:r/r:x/z/ev", "matrix:r/%E0%A4%A", "", "hello"])
    assert.equal(parseMatrixLink(s), null, s);
});

test("page hash form", () => {
  assert.equal(parseMatrixHash("#/!id:x.org/$ev")?.eventId, "$ev");
  assert.equal(parseMatrixHash("#!id:x.org"), null);
});
