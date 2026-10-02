import { useMemo, useState, type FormEvent } from "react";
import { createPortal } from "react-dom";
import { M_POLL_END, M_POLL_KIND_DISCLOSED, M_POLL_KIND_UNDISCLOSED, M_POLL_RESPONSE, M_POLL_START, PollEvent, type MatrixEvent, type Poll, type Room } from "matrix-js-sdk";
import { PollStartEvent } from "matrix-js-sdk/lib/extensible_events_v1/PollStartEvent.js";
import { PollResponseEvent } from "matrix-js-sdk/lib/extensible_events_v1/PollResponseEvent.js";
import { PollEndEvent } from "matrix-js-sdk/lib/extensible_events_v1/PollEndEvent.js";
import { client } from "../matrix.ts";
import { usePromise, useTick } from "../hooks.ts";
import { num, tallyPoll } from "../logic.ts";
import { Icon } from "../icons.tsx";
import { errText, me, parsePoll, Sheet } from "./common.tsx";

const MAX_ANSWERS = 20;

// Poll.getResponses() resolves to undefined if a fetch is already running (e.g. the row remounted while scrolling),
// so every PollBody for a poll shares the first call's promise
const responses = new WeakMap<Poll, ReturnType<Poll["getResponses"]>>();
const responsesOf = (poll: Poll) => {
  if (!responses.has(poll)) {
    const p = poll.getResponses();
    p.catch(() => responses.delete(poll)); // retry on next render
    responses.set(poll, p);
  }
  return responses.get(poll)!;
};

/** New poll, or (edit) a replacement for one nobody has voted on yet. Sent with the unstable types, which every client reads. */
export function PollForm({ room, threadId, edit, onClose }: { room: Room; threadId: string | null; edit?: MatrixEvent; onClose: () => void }) {
  const old = edit ? parsePoll(edit) : null;
  const [question, setQuestion] = useState(old?.question.text ?? "");
  const [answers, setAnswers] = useState(old ? old.answers.map((a) => a.text) : ["", ""]);
  const [multi, setMulti] = useState((old?.maxSelections ?? 1) > 1);
  const [hidden, setHidden] = useState(!!old && M_POLL_KIND_UNDISCLOSED.matches(old.rawKind));
  const [busy, setBusy] = useState(false);
  const opts = answers.map((a) => a.trim()).filter(Boolean);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    const kind = (hidden ? M_POLL_KIND_UNDISCLOSED : M_POLL_KIND_DISCLOSED).name;
    const content = PollStartEvent.from(question.trim(), opts, kind, multi ? opts.length : 1).serialize().content;
    try {
      if (edit) await client.sendEvent(room.roomId, M_POLL_START.name as never, {
        ...content, "m.new_content": content, "m.relates_to": { rel_type: "m.replace", event_id: edit.getId()! },
      } as never);
      else await client.sendEvent(room.roomId, threadId, M_POLL_START.name as never, content as never);
      onClose();
    } catch (err) {
      alert(errText(err));
      setBusy(false);
    }
  }

  const setAnswer = (i: number, v: string) => setAnswers((a) => a.map((x, j) => (j === i ? v : x)));
  return (
    <Sheet title={edit ? "ویرایش نظرسنجی" : "نظرسنجی جدید"} onClose={onClose}>
      <form className="form" onSubmit={submit}>
        <input value={question} onChange={(e) => setQuestion(e.target.value)} placeholder="پرسش" aria-label="پرسش" dir="auto" required autoFocus />
        <h3>گزینه‌ها</h3>
        {answers.map((a, i) => (
          <div key={i} className="poll-edit-opt">
            <input value={a} onChange={(e) => setAnswer(i, e.target.value)} placeholder={`گزینه‌ی ${num(i + 1)}`} aria-label={`گزینه‌ی ${num(i + 1)}`} dir="auto" />
            {answers.length > 2 && (
              <button type="button" className="icon-btn" aria-label="حذف گزینه" onClick={() => setAnswers((x) => x.filter((_, j) => j !== i))}><Icon name="close" size={18} /></button>
            )}
          </div>
        ))}
        {answers.length < MAX_ANSWERS && (
          <div className="row-actions"><button type="button" onClick={() => setAnswers((a) => [...a, ""])}><Icon name="plus" /> افزودن گزینه</button></div>
        )}
        <label className="switch-row"><span>چند گزینه‌ای<small>هر نفر می‌تواند چند گزینه را انتخاب کند</small></span>
          <input type="checkbox" role="switch" checked={multi} onChange={(e) => setMulti(e.target.checked)} /></label>
        <label className="switch-row"><span>نتایج پس از پایان<small>تا پایان نظرسنجی، کسی نتیجه را نمی‌بیند</small></span>
          <input type="checkbox" role="switch" checked={hidden} onChange={(e) => setHidden(e.target.checked)} /></label>
        <button className="primary" disabled={busy || !question.trim() || opts.length < 2}>{edit ? "ذخیره" : "ارسال نظرسنجی"}</button>
      </form>
    </Sheet>
  );
}

export function PollBody({ ev, room }: { ev: MatrixEvent; room: Room }) {
  useTick(room, [PollEvent.New]); // the SDK builds the Poll after (decrypting) the start event
  const poll = room.polls.get(ev.getId()!);
  useTick(poll, [PollEvent.Responses, PollEvent.End, PollEvent.Update]);
  const rels = usePromise(useMemo(() => (poll ? responsesOf(poll) : null), [poll]));
  const [pending, setPending] = useState<{ answers: string[]; at: number } | null>(null);
  const [editing, setEditing] = useState(false);

  const p = parsePoll(ev);
  if (!p) return <p className="msg-text muted">نظرسنجی نامعتبر</p>;
  const ids = p.answers.map((a) => a.id);
  const max = Math.max(1, p.maxSelections);
  const votes = (rels?.getRelations() ?? []).map((r) => {
    const c = r.getContent();
    return { sender: r.getSender()!, ts: r.getTs(), answers: (c[M_POLL_RESPONSE.name] ?? c[M_POLL_RESPONSE.altName])?.answers };
  });
  const { voters, picks } = tallyPoll(votes, ids, max);
  // my vote shows right away; the server's copy takes over once any new response arrives
  const mine = pending && pending.at === votes.length ? pending.answers : picks.get(me()) ?? [];
  const ended = !!poll?.isEnded;
  const undisclosed = M_POLL_KIND_UNDISCLOSED.matches(p.rawKind);
  const results = ended || (!undisclosed && mine.length > 0);
  const total = picks.size;
  const top = Math.max(...ids.map((id) => voters.get(id)!.length));
  const name = (id: string) => room.getMember(id)?.name ?? id;

  function vote(id: string) {
    if (ended || !poll) return;
    const next = max === 1 ? [id] : mine.includes(id) ? mine.filter((x) => x !== id) : [...mine, id].slice(-max);
    if (max === 1 && mine[0] === id) return;
    setPending({ answers: next, at: votes.length });
    // empty selection = a spoiled vote, which is how a multi-choice voter takes their vote back
    client.sendEvent(room.roomId, M_POLL_RESPONSE.name as never, PollResponseEvent.from(next, poll.pollId).serialize().content as never)
      .catch((e) => { setPending(null); alert(errText(e)); });
  }

  const canEnd = !!poll && !ended && (ev.getSender() === me() || room.currentState.maySendRedactionForEvent(ev, me()));
  const canEdit = !!rels && !ended && total === 0 && votes.length === 0 && ev.getSender() === me();
  const end = () => confirm("نظرسنجی پایان یابد؟ پس از آن کسی نمی‌تواند رأی بدهد.") &&
    client.sendEvent(room.roomId, M_POLL_END.name as never, PollEndEvent.from(poll!.pollId, "The poll has ended").serialize().content as never)
      .catch((e) => alert(errText(e)));

  return (
    // the bubble toggles its action bar on click; voting shouldn't
    <div className="poll" onClick={(e) => e.stopPropagation()}>
      <b className="poll-q" dir="auto">{p.question.text}</b>
      <small className="poll-kind">
        {ended ? "نظرسنجی پایان‌یافته" : [undisclosed ? "نتایج پس از پایان" : "نظرسنجی", max > 1 && "چند گزینه‌ای"].filter(Boolean).join(" · ")}
      </small>
      {p.answers.map((a) => {
        const who = voters.get(a.id)!;
        const pct = total ? Math.round((who.length / total) * 100) : 0;
        const on = mine.includes(a.id);
        return (
          <button key={a.id} className={"poll-opt" + (on ? " on" : "") + (ended && who.length && who.length === top ? " win" : "")}
            disabled={ended || !poll} onClick={() => vote(a.id)} title={results && who.length ? who.map(name).join("، ") : undefined}>
            <span className={"poll-mark" + (max > 1 ? " square" : "")}>{on && <Icon name="check" size={13} />}</span>
            <span className="poll-text" dir="auto">{a.text}</span>
            {results && <span className="poll-pct">{num(pct)}٪</span>}
            {results && <span className="poll-bar" style={{ width: pct + "%" }} />}
          </button>
        );
      })}
      <div className="poll-foot">
        <span>{total ? `${num(total)} رأی` : "هنوز رأیی نیست"}</span>
        {canEdit && <button onClick={() => setEditing(true)}>ویرایش</button>}
        {canEnd && <button onClick={end}>پایان نظرسنجی</button>}
      </div>
      {/* portal: a fixed sheet inside the virtualized bubble would be clipped and catch its clicks */}
      {editing && createPortal(<PollForm room={room} threadId={null} edit={ev} onClose={() => setEditing(false)} />, document.body)}
    </div>
  );
}
