import 'package:flutter/material.dart';
import 'package:matrix/matrix.dart';

import '../logic.dart';
import '../prefs.dart';
import '../theme.dart';
import 'common.dart';
import 'composer.dart';
import 'message.dart';

// The Famedly SDK has no Thread class: a thread is the root plus the events related to it by m.thread,
// which every Timeline already aggregates (and `fetchAggregatedEvents` completes from /relations).

/// Loaded replies of a thread, oldest first.
List<Event> threadReplies(Timeline tl, String rootId) => [
  for (final e in tl.aggregatedEvents[rootId]?[RelationshipTypes.thread] ?? const <Event>{}) if (isMessage(e)) e,
]..sort((a, b) => a.originServerTs.compareTo(b.originServerTs));

/// `m.relates_to` of a message sent in a thread (or a plain reply outside one), like matrix-js-sdk's `sendMessage(room, threadId, …)`.
/// Without an explicit reply the in_reply_to is only the fallback for clients without thread support.
Map<String, dynamic>? relatesTo(String? threadId, Event? replyTo, [String? lastId]) {
  if (threadId == null) return replyTo == null ? null : {'m.in_reply_to': {'event_id': replyTo.eventId}};
  return {
    'rel_type': RelationshipTypes.thread, 'event_id': threadId,
    'is_falling_back': replyTo == null,
    'm.in_reply_to': {'event_id': replyTo?.eventId ?? lastId ?? threadId},
  };
}

int _replyCount(Event root, Timeline tl) {
  final server = (root.unsigned?['m.relations'] as Map?)?['m.thread'] is Map ? ((root.unsigned!['m.relations'] as Map)['m.thread'] as Map)['count'] : null;
  return [if (server is int) server, threadReplies(tl, root.eventId).length].reduce((a, b) => a > b ? a : b);
}

// reply timestamps already seen per thread: the opened thread's is stored, a never-opened one gets this session's first look as baseline
final _baseline = <String, int>{};
int _seenTs(String root, int newest) => prefs.get<int?>('thr:$root') ?? _baseline.putIfAbsent(root, () => newest);

/// «N پاسخ» under a thread root (+ how many came from others since it was last open).
class ThreadSummary extends StatelessWidget {
  final Event root;
  final Timeline timeline;
  final VoidCallback onTap;
  final bool mine;
  const ThreadSummary({super.key, required this.root, required this.timeline, required this.onTap, required this.mine});

  @override
  Widget build(BuildContext context) {
    final n = _replyCount(root, timeline);
    if (n == 0) return const SizedBox.shrink();
    final t = context.tk, replies = threadReplies(timeline, root.eventId);
    final seen = _seenTs(root.eventId, replies.isEmpty ? 0 : replies.last.originServerTs.millisecondsSinceEpoch);
    final unread = replies.where((e) => e.senderId != me() && e.originServerTs.millisecondsSinceEpoch > seen).length;
    final color = mine ? t.outText : t.accent;
    return Directionality(textDirection: TextDirection.rtl, child: GestureDetector(
      behavior: HitTestBehavior.opaque, onTap: onTap,
      child: Padding(padding: const EdgeInsets.only(top: 4, bottom: 14), child: Row(mainAxisSize: MainAxisSize.min, children: [
        Icon(Icons.forum_outlined, size: 17, color: color),
        const SizedBox(width: 6),
        Text('${faNum(n)} پاسخ', style: TextStyle(color: color, fontWeight: FontWeight.w600, fontSize: 13.5)),
        if (unread > 0) ...[
          const SizedBox(width: 6),
          Container(
            constraints: const BoxConstraints(minWidth: 18), height: 18, padding: const EdgeInsets.symmetric(horizontal: 5), alignment: Alignment.center,
            decoration: BoxDecoration(color: t.accent, borderRadius: BorderRadius.circular(9)),
            child: Text(faNum(unread), style: const TextStyle(color: Colors.white, fontSize: 11.5, fontWeight: FontWeight.w600))),
        ],
        Icon(Icons.chevron_left, size: 18, color: color),
      ])),
    ));
  }
}

void openThread(BuildContext context, Room room, String rootId) =>
    Navigator.push(context, MaterialPageRoute<void>(builder: (_) => ThreadPage(room: room, rootId: rootId)));

class ThreadPage extends StatefulWidget {
  final Room room;
  final String rootId;
  const ThreadPage({super.key, required this.room, required this.rootId});
  @override
  State<ThreadPage> createState() => _ThreadPageState();
}

class _ThreadPageState extends State<ThreadPage> {
  Room get room => widget.room;
  Timeline? _tl;
  Event? _root;
  Mode? _mode;
  bool _missing = false;

  @override
  void initState() {
    super.initState();
    room.getTimeline(onUpdate: () { if (mounted) setState(() {}); }).then((tl) async {
      if (!mounted) return tl.cancelSubscriptions();
      _tl = tl;
      setState(() {});
      // a thread whose root was in the main timeline already has its summary; the server fills in the replies we haven't loaded
      _root = tl.events.where((e) => e.eventId == widget.rootId).firstOrNull ?? await room.getEventById(widget.rootId).catchError((_) => null);
      if (mounted) setState(() => _missing = _root == null);
      tl.fetchAggregatedEvents(widget.rootId, RelationshipTypes.thread).catchError((_) {});
    });
  }

  @override
  void dispose() {
    _tl?.cancelSubscriptions();
    _markSeen();
    super.dispose();
  }

  void _markSeen() {
    final tl = _tl;
    if (tl == null) return;
    final r = threadReplies(tl, widget.rootId);
    if (r.isNotEmpty) prefs.set({'thr:${widget.rootId}': r.last.originServerTs.millisecondsSinceEpoch});
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tk, tl = _tl, root = _root;
    final replies = tl == null ? <Event>[] : threadReplies(tl, widget.rootId);
    return Scaffold(
      appBar: AppBar(titleSpacing: 0, title: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
        const Text('رشته‌ی گفتگو', style: TextStyle(fontSize: 17, fontWeight: FontWeight.w600)),
        Text('${faNum(replies.length)} پاسخ · ${roomTitle(room)}', maxLines: 1, overflow: TextOverflow.ellipsis,
          style: TextStyle(fontSize: 13, fontWeight: FontWeight.normal, color: Colors.white.withValues(alpha: .75))),
      ])),
      body: Column(children: [
        Expanded(child: Wallpaper(child: tl == null || root == null
            ? Center(child: _missing ? Text('پیام اصلی پیدا نشد', style: TextStyle(color: t.muted)) : const CircularProgressIndicator())
            : _list(tl, [root, ...replies.where((e) => e.eventId != root.eventId)]))),
        if (tl != null && room.canSendDefaultMessages) Composer(
          room: room, timeline: tl, threadId: widget.rootId, mode: _mode, onMode: (m) => setState(() => _mode = m)),
      ]),
    );
  }

  Widget _list(Timeline tl, List<Event> evs) {
    final byId = {for (final e in evs) e.transactionId ?? e.eventId: e};
    final rows = buildRows([for (final e in evs) Msg(e.transactionId ?? e.eventId, e.senderId, e.originServerTs.millisecondsSinceEpoch, 'msg')], null, null).reversed.toList();
    final actions = MsgActions(reply: (e) => setState(() => _mode = Mode.reply(e)), edit: (e) => setState(() => _mode = Mode.edit(e)), jump: (_) {});
    return ListView.builder(
      reverse: true, padding: const EdgeInsets.symmetric(vertical: 8), itemCount: rows.length,
      itemBuilder: (context, i) {
        final row = rows[i], ev = row.id == null ? null : byId[row.id!];
        final child = switch (row.type) {
          'day' => Center(child: Container(
            margin: const EdgeInsets.symmetric(vertical: 4), padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 3),
            decoration: BoxDecoration(color: context.tk.pill, borderRadius: BorderRadius.circular(14)),
            child: Text(row.label!, style: const TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.w500)))),
          _ => ev == null ? const SizedBox.shrink() : ChatMessage(ev: ev, timeline: tl, first: row.first, last: row.last, tick: _tick(ev), actions: actions),
        };
        return KeyedSubtree(key: ValueKey(row.key), child: child);
      },
    );
  }

  Tick _tick(Event ev) => ev.senderId != me() ? Tick.none : ev.status.isError ? Tick.failed : ev.status.isSending ? Tick.sending : Tick.sent;
}
