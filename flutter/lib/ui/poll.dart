import 'dart:math';

import 'package:flutter/material.dart';
import 'package:matrix/matrix.dart';

import '../logic.dart';
import '../theme.dart';
import 'common.dart';

const _start = 'org.matrix.msc3381.poll.start', _response = 'org.matrix.msc3381.poll.response', _end = 'org.matrix.msc3381.poll.end';
const _responses = [_response, 'm.poll.response'], _ends = [_end, 'm.poll.end'];
const _maxAnswers = 20;

// ponytail: the SDK's startPoll/answerPoll/getPollResponses are not used: startPoll words the fallback text differently from the web app,
// and getPollResponses doesn't treat spoiled votes (the way to retract a multi-choice vote) as replacing the earlier one.

String? _txt(Object? v) => v is String ? v : v is List ? v.whereType<Map>().map((m) => m['body']).firstOrNull?.toString() : null;

typedef PollData = ({String question, List<({String id, String text})> answers, int max, bool undisclosed});

/// Poll start content, stable or unstable names; null if malformed.
PollData? parsePoll(Map c) {
  final p = (c[_start] ?? c['m.poll']) as Map?;
  if (p == null) return null;
  final q = p['question'];
  final question = q is Map ? _txt(q['org.matrix.msc1767.text'] ?? q['m.text']) ?? _txt(q['body']) : null;
  final raw = p['answers'];
  if (question == null || raw is! List) return null;
  final answers = [
    for (final a in raw.take(_maxAnswers))
      if (a is Map && a['id'] is String && a['id'] != '')
        (id: a['id'] as String, text: _txt(a['org.matrix.msc1767.text'] ?? a['m.text']) ?? _txt(a['body']) ?? ''),
  ];
  if (answers.isEmpty) return null;
  final m = p['max_selections'];
  return (question: question, answers: answers, max: m is int && m > 0 ? m : 1, undisclosed: '${p['kind']}'.endsWith('undisclosed'));
}

String _id() {
  const l = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
  final r = Random.secure();
  return List.generate(16, (_) => l[r.nextInt(l.length)]).join();
}

/// The newest edit by the author (edits of polls aren't `m.room.message`, so getDisplayEvent skips them).
Map<String, Object?> pollContent(Event ev, Timeline tl) {
  final edits = ev.aggregatedEvents(tl, RelationshipTypes.edit).where((e) => e.senderId == ev.senderId && pollStart.contains(e.type) && !e.redacted).toList()
    ..sort((a, b) => a.originServerTs.compareTo(b.originServerTs));
  final c = edits.lastOrNull?.content['m.new_content'];
  return c is Map ? Map<String, Object?>.from(c) : ev.content;
}

/// New poll, or (edit) a replacement for one nobody has voted on yet. Sent with the unstable types, which every client reads.
void showPollForm(BuildContext context, Room room, {Event? edit, Timeline? timeline}) => showModalBottomSheet<void>(
  context: context, isScrollControlled: true, useSafeArea: true,
  builder: (_) => _PollForm(room, edit, edit == null || timeline == null ? null : parsePoll(pollContent(edit, timeline))),
);

class _PollForm extends StatefulWidget {
  final Room room;
  final Event? edit;
  final PollData? old;
  const _PollForm(this.room, this.edit, this.old);
  @override
  State<_PollForm> createState() => _PollFormState();
}

class _PollFormState extends State<_PollForm> {
  late final _q = TextEditingController(text: widget.old?.question ?? '');
  late final List<TextEditingController> _a = [for (final a in widget.old?.answers ?? const <({String id, String text})>[]) TextEditingController(text: a.text)];
  late bool _multi = (widget.old?.max ?? 1) > 1, _hidden = widget.old?.undisclosed ?? false;
  var _busy = false;

  @override
  void initState() {
    super.initState();
    while (_a.length < 2) {
      _a.add(TextEditingController());
    }
  }

  @override
  void dispose() {
    _q.dispose();
    for (final c in _a) {
      c.dispose();
    }
    super.dispose();
  }

  List<String> get _opts => [for (final c in _a) if (c.text.trim().isNotEmpty) c.text.trim()];

  Future<void> _submit() async {
    setState(() => _busy = true);
    final q = _q.text.trim(), opts = _opts;
    final content = <String, Object?>{
      _start: {
        'question': {'org.matrix.msc1767.text': q},
        'kind': _hidden ? 'org.matrix.msc3381.poll.undisclosed' : 'org.matrix.msc3381.poll.disclosed',
        'max_selections': _multi ? opts.length : 1,
        // an edit keeps the answer ids where the text is unchanged
        'answers': [for (var i = 0; i < opts.length; i++) {'id': widget.old != null && i < widget.old!.answers.length && widget.old!.answers[i].text == opts[i] ? widget.old!.answers[i].id : _id(), 'org.matrix.msc1767.text': opts[i]}],
      },
      'org.matrix.msc1767.text': '$q\n${[for (var i = 0; i < opts.length; i++) '${i + 1}. ${opts[i]}'].join('\n')}',
    };
    final e = widget.edit;
    try {
      await widget.room.sendEvent(e == null ? content : {...content, 'm.new_content': content, 'm.relates_to': {'rel_type': RelationshipTypes.edit, 'event_id': e.eventId}}, type: _start);
      if (mounted) Navigator.pop(context);
    } catch (err) {
      if (mounted) { setState(() => _busy = false); alert(context, errText(err)); }
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tk;
    return Padding(padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom), child: ListView(padding: const EdgeInsets.all(16), shrinkWrap: true, children: [
      Text(widget.edit != null ? 'ویرایش نظرسنجی' : 'نظرسنجی جدید', style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w600)),
      const SizedBox(height: 12),
      TextField(controller: _q, autofocus: true, onChanged: (_) => setState(() {}), decoration: const InputDecoration(labelText: 'پرسش')),
      const SizedBox(height: 16),
      Text('گزینه‌ها', style: TextStyle(color: t.accent, fontWeight: FontWeight.w600)),
      for (var i = 0; i < _a.length; i++) Row(children: [
        Expanded(child: TextField(controller: _a[i], onChanged: (_) => setState(() {}), decoration: InputDecoration(labelText: 'گزینه‌ی ${faNum(i + 1)}'))),
        if (_a.length > 2) IconButton(icon: const Icon(Icons.close, size: 20), tooltip: 'حذف گزینه', onPressed: () => setState(() => _a.removeAt(i).dispose())),
      ]),
      if (_a.length < _maxAnswers) Align(alignment: AlignmentDirectional.centerStart, child: TextButton.icon(
        onPressed: () => setState(() => _a.add(TextEditingController())), icon: const Icon(Icons.add), label: const Text('افزودن گزینه'))),
      SwitchListTile(contentPadding: EdgeInsets.zero, title: const Text('چند گزینه‌ای'), subtitle: const Text('هر نفر می‌تواند چند گزینه را انتخاب کند'), value: _multi, onChanged: (v) => setState(() => _multi = v)),
      SwitchListTile(contentPadding: EdgeInsets.zero, title: const Text('نتایج پس از پایان'), subtitle: const Text('تا پایان نظرسنجی، کسی نتیجه را نمی‌بیند'), value: _hidden, onChanged: (v) => setState(() => _hidden = v)),
      const SizedBox(height: 8),
      FilledButton(onPressed: _busy || _q.text.trim().isEmpty || _opts.length < 2 ? null : _submit, child: Text(widget.edit != null ? 'ذخیره' : 'ارسال نظرسنجی')),
    ]));
  }
}

class PollBody extends StatefulWidget {
  final Event ev;
  final Timeline timeline;
  final bool mine;
  const PollBody(this.ev, this.timeline, {super.key, required this.mine});
  @override
  State<PollBody> createState() => _PollBodyState();
}

class _PollBodyState extends State<PollBody> {
  ({List<String> answers, int at})? _pending; // my vote shows right away; the server's copy takes over once any new response arrives
  var _fetched = false;

  Event get ev => widget.ev;
  Room get room => ev.room;

  @override
  void initState() {
    super.initState();
    // a fragmented timeline may not hold the responses
    widget.timeline.fetchAggregatedEvents(ev.eventId, RelationshipTypes.reference).whenComplete(() { if (mounted) setState(() => _fetched = true); });
  }

  /// A poll.end only counts from the author or someone who can redact.
  Event? _ended() {
    final redact = room.getState(EventTypes.RoomPowerLevels)?.content.tryGet<int>('redact') ?? 50;
    return ev.aggregatedEvents(widget.timeline, RelationshipTypes.reference).where((e) => _ends.contains(e.type) &&
        (e.senderId == ev.senderId || e.senderFromMemoryOrFallback.powerLevel.level >= redact)).firstOrNull;
  }

  void _vote(String id, List<String> mine, int max) {
    final next = max == 1 ? [id] : mine.contains(id) ? mine.where((x) => x != id).toList() : [...mine, id].reversed.take(max).toList().reversed.toList();
    if (max == 1 && mine.firstOrNull == id) return;
    final at = ev.aggregatedEvents(widget.timeline, RelationshipTypes.reference).where((e) => _responses.contains(e.type)).length;
    setState(() => _pending = (answers: next, at: at));
    // empty selection = a spoiled vote, which is how a multi-choice voter takes their vote back
    room.sendEvent({'m.relates_to': {'rel_type': RelationshipTypes.reference, 'event_id': ev.eventId}, _response: next.isEmpty ? {} : {'answers': next}}, type: _response)
        .catchError((Object e) { if (mounted) { setState(() => _pending = null); alert(context, errText(e)); } return null; });
  }

  Future<void> _finish() async {
    if (!await confirm(context, 'نظرسنجی پایان یابد؟ پس از آن کسی نمی‌تواند رأی بدهد.') || !mounted) return;
    await attempt(context, () async {
      await room.sendEvent({'m.relates_to': {'rel_type': RelationshipTypes.reference, 'event_id': ev.eventId}, 'org.matrix.msc1767.text': 'The poll has ended', _end: {}}, type: _end);
    });
  }

  String _name(String id) => room.unsafeGetUserFromMemoryOrFallback(id).calcDisplayname();

  void _who(String text, List<String> ids) => showModalBottomSheet<void>(context: context, builder: (c) => SafeArea(child: Column(mainAxisSize: MainAxisSize.min, children: [
    Padding(padding: const EdgeInsets.all(16), child: Text(text, textDirection: textDir(text) == 'rtl' ? TextDirection.rtl : TextDirection.ltr, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600))),
    Flexible(child: ListView(shrinkWrap: true, children: [for (final id in ids) ListTile(
      leading: Avatar(mxc: room.unsafeGetUserFromMemoryOrFallback(id).avatarUrl, name: _name(id), id: id, size: 38), title: Text(_name(id)))])),
  ])));

  @override
  Widget build(BuildContext context) {
    final t = context.tk, fg = widget.mine ? t.outText : t.text, meta = widget.mine ? t.outMeta : t.muted;
    final p = parsePoll(pollContent(ev, widget.timeline));
    if (p == null) return Text('نظرسنجی نامعتبر', style: TextStyle(color: meta));
    final end = _ended(), ended = end != null;
    final ids = [for (final a in p.answers) a.id];
    final votes = [
      for (final r in ev.aggregatedEvents(widget.timeline, RelationshipTypes.reference))
        if (_responses.contains(r.type) && (end == null || !r.originServerTs.isAfter(end.originServerTs)))
          Vote(r.senderId, r.originServerTs.millisecondsSinceEpoch, (r.content[r.type] as Map?)?['answers']),
    ];
    final all = ev.aggregatedEvents(widget.timeline, RelationshipTypes.reference).where((e) => _responses.contains(e.type)).length;
    final (:voters, :picks) = tallyPoll(votes, ids, p.max);
    final mine = _pending != null && _pending!.at == all ? _pending!.answers : picks[me()] ?? const <String>[];
    final results = ended || (!p.undisclosed && mine.isNotEmpty), total = picks.length;
    final top = voters.values.map((v) => v.length).fold(0, max);
    final canEnd = !ended && ev.canRedact, canEdit = _fetched && !ended && total == 0 && votes.isEmpty && ev.senderId == me();
    final w = min(280.0, MediaQuery.sizeOf(context).width * .8 - 24);
    TextDirection dir(String s) => textDir(s) == 'rtl' ? TextDirection.rtl : TextDirection.ltr;
    return Directionality(textDirection: TextDirection.rtl, child: SizedBox(width: w, child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
      Text(p.question, textDirection: dir(p.question), style: TextStyle(color: fg, fontSize: 15.5, fontWeight: FontWeight.w700)),
      Text(ended ? 'نظرسنجی پایان‌یافته' : [p.undisclosed ? 'نتایج پس از پایان' : 'نظرسنجی', if (p.max > 1) 'چند گزینه‌ای'].join(' · '), style: TextStyle(color: meta, fontSize: 12)),
      const SizedBox(height: 4),
      for (final a in p.answers) Builder(builder: (_) {
        final who = voters[a.id]!, pct = total == 0 ? 0 : (who.length / total * 100).round(), on = mine.contains(a.id), win = ended && who.isNotEmpty && who.length == top;
        return InkWell(
          onTap: ended ? null : () => _vote(a.id, mine, p.max),
          onLongPress: results && who.isNotEmpty ? () => _who(a.text, who) : null,
          child: Padding(padding: const EdgeInsets.symmetric(vertical: 4), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Container(width: 20, height: 20, margin: const EdgeInsetsDirectional.only(end: 8),
                decoration: BoxDecoration(color: on ? t.accent : null, shape: p.max > 1 ? BoxShape.rectangle : BoxShape.circle, borderRadius: p.max > 1 ? BorderRadius.circular(5) : null,
                  border: Border.all(color: on ? t.accent : meta, width: 1.5)),
                child: on ? const Icon(Icons.check, size: 14, color: Colors.white) : null),
              Expanded(child: Text(a.text, textDirection: dir(a.text), style: TextStyle(color: fg, fontSize: 14.5, fontWeight: win ? FontWeight.w700 : null))),
              if (results) Padding(padding: const EdgeInsetsDirectional.only(start: 8), child: Text('${faNum(pct)}٪', style: TextStyle(color: fg, fontSize: 13, fontWeight: FontWeight.w600))),
            ]),
            if (results) Padding(padding: const EdgeInsetsDirectional.only(top: 3, start: 28), child: ClipRRect(borderRadius: BorderRadius.circular(3), child: LinearProgressIndicator(
              value: pct / 100, minHeight: 5, backgroundColor: fg.withValues(alpha: .1), color: win ? t.readTick : t.accent))),
          ])),
        );
      }),
      const SizedBox(height: 4),
      Wrap(crossAxisAlignment: WrapCrossAlignment.center, spacing: 12, children: [
        Text(total > 0 ? '${faNum(total)} رأی' : 'هنوز رأیی نیست', style: TextStyle(color: meta, fontSize: 12.5)),
        if (canEdit) InkWell(onTap: () => showPollForm(context, room, edit: ev, timeline: widget.timeline), child: Text('ویرایش', style: TextStyle(color: t.accent, fontSize: 12.5, fontWeight: FontWeight.w600))),
        if (canEnd) InkWell(onTap: _finish, child: Text('پایان نظرسنجی', style: TextStyle(color: t.accent, fontSize: 12.5, fontWeight: FontWeight.w600))),
      ]),
    ])));
  }
}
