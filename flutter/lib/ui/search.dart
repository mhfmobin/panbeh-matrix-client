import 'dart:async';

import 'package:flutter/material.dart';
import 'package:matrix/matrix.dart';

import '../logic.dart';
import '../matrix.dart';
import '../theme.dart';
import 'common.dart';
import 'message.dart';

const _min = 2;
int _ts(Event e) => e.originServerTs.millisecondsSinceEpoch;

/// Deduped by event id, newest first.
List<Event> _merge(Iterable<Event> evs) {
  final seen = <String>{};
  return [for (final e in evs) if (seen.add(e.eventId)) e]..sort((a, b) => _ts(b).compareTo(_ts(a)));
}

/// Loaded messages containing the term, newest first.
List<Event> scanLoaded(Timeline tl, String term) {
  final t = normalize(term.trim());
  return _merge([for (final e in tl.events) if (isMessage(e) && normalize(previewText(e.getDisplayEvent(tl))).contains(t)) e]);
}

/// Server-side search over unencrypted rooms (one room, or all of them), newest first.
Future<List<Event>> searchServer(String term, {Room? room}) async {
  try {
    final r = await client.search(Categories(roomEvents: RoomEventsCriteria(
      searchTerm: term, orderBy: SearchOrder.recent,
      filter: SearchFilter(rooms: room == null ? null : [room.id]), // Conduit rejects a missing filter: always send one
    )));
    return [
      for (final x in r.searchCategories.roomEvents?.results ?? const <Result>[])
        if (x.result != null && client.getRoomById(x.result!.roomId ?? '') != null) Event.fromMatrixEvent(x.result!, client.getRoomById(x.result!.roomId!)!),
    ];
  } catch (_) {
    return [];
  }
}

/// Page the timeline back (and decrypt what arrived). Resolves to whether older history remains.
Future<bool> searchOlder(Timeline tl, {int pages = 5}) async {
  try {
    for (var i = 0; i < pages && tl.canRequestHistory; i++) {
      await tl.requestHistory(historyCount: 40);
    }
  } catch (_) {} // offline: keep what we have
  return tl.canRequestHistory;
}

class _Row extends StatelessWidget {
  final Event ev;
  final String term;
  final bool showRoom;
  final Timeline? tl;
  final VoidCallback onTap;
  const _Row({required this.ev, required this.term, required this.onTap, this.showRoom = false, this.tl});

  @override
  Widget build(BuildContext context) {
    final t = context.tk;
    final text = previewText(tl == null ? ev : ev.getDisplayEvent(tl!)), q = term.trim();
    final i = text.toLowerCase().indexOf(q.toLowerCase());
    final name = senderName(ev);
    final style = TextStyle(color: t.muted, fontSize: 14);
    return ListTile(
      onTap: onTap,
      leading: Avatar(mxc: ev.senderFromMemoryOrFallback.avatarUrl, name: name, id: ev.senderId, size: 42),
      title: Text(showRoom ? '${roomTitle(ev.room)} · ${bdi(name)}' : bdi(name), maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 15)),
      subtitle: i < 0 ? Text(text, maxLines: 2, overflow: TextOverflow.ellipsis, style: style) : Text.rich(
        TextSpan(style: style, children: [
          TextSpan(text: text.substring(0, i)),
          TextSpan(text: text.substring(i, i + q.length), style: TextStyle(color: t.accent, fontWeight: FontWeight.w700, backgroundColor: t.accent.withValues(alpha: .15))),
          TextSpan(text: text.substring(i + q.length)),
        ]),
        maxLines: 2, overflow: TextOverflow.ellipsis,
      ),
      trailing: Text(stamp(_ts(ev)), style: TextStyle(color: t.muted, fontSize: 12)),
    );
  }
}

/// Debounced (300 ms) term → results; `run` is only called from `_min` characters on.
mixin _Debounced<T extends StatefulWidget> on State<T> {
  List<Event>? res;
  Timer? _h;
  int _gen = 0;

  Future<List<Event>> run(String term);

  @override
  void dispose() {
    _h?.cancel();
    super.dispose();
  }

  void query(String term) {
    final q = term.trim(), gen = ++_gen;
    _h?.cancel();
    if (q.length < _min) return setState(() => res = null);
    setState(() => res = null);
    _h = Timer(const Duration(milliseconds: 300), () async {
      final r = await run(q);
      if (mounted && gen == _gen) setState(() => res = r);
    });
  }
}

/// In-chat search. Pops with the picked event's id; the chat then jumps (and pages back up to 50 times) to it.
class SearchPage extends StatefulWidget {
  final Room room;
  final Timeline timeline;
  const SearchPage({super.key, required this.room, required this.timeline});
  @override
  State<SearchPage> createState() => _SearchPageState();
}

class _SearchPageState extends State<SearchPage> with _Debounced<SearchPage> {
  final _c = TextEditingController();
  late bool _more = widget.timeline.canRequestHistory;
  bool _paging = false;

  Room get room => widget.room;
  Timeline get tl => widget.timeline;

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Future<List<Event>> run(String term) async => room.encrypted
      ? scanLoaded(tl, term)
      // the server doesn't fold ي/ك or ZWNJ; the local scan catches loaded messages it misses
      : _merge([...await searchServer(term, room: room), ...scanLoaded(tl, term)]);

  Future<void> _older() async {
    setState(() => _paging = true);
    final more = await searchOlder(tl);
    if (!mounted) return;
    setState(() { _paging = false; _more = more; });
    query(_c.text); // rescan
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tk, ready = _c.text.trim().length >= _min, r = res;
    return Scaffold(
      appBar: AppBar(
        title: TextField(
          controller: _c, autofocus: true, onChanged: query,
          style: const TextStyle(color: Colors.white, fontSize: 18), cursorColor: Colors.white,
          decoration: const InputDecoration(hintText: 'جستجو در گفتگو', hintStyle: TextStyle(color: Colors.white70), border: InputBorder.none)),
        actions: [if (_c.text.isNotEmpty) IconButton(icon: const Icon(Icons.close), onPressed: () { _c.clear(); query(''); })],
      ),
      body: ListView(children: [
        if (room.encrypted) Padding(padding: const EdgeInsets.all(12), child: Text('در گفتگوهای رمزنگاری‌شده فقط پیام‌های بارگذاری‌شده جستجو می‌شوند', style: TextStyle(color: t.muted, fontSize: 13))),
        if (ready && r == null) const Padding(padding: EdgeInsets.all(24), child: Center(child: CircularProgressIndicator(strokeWidth: 2))),
        if (r != null && r.isEmpty) Padding(padding: const EdgeInsets.all(24), child: Center(child: Text('پیامی پیدا نشد', style: TextStyle(color: t.muted)))),
        for (final e in r ?? const <Event>[]) _Row(ev: e, term: _c.text, tl: tl, onTap: () => Navigator.pop(context, e.eventId)),
        if (room.encrypted && ready && _more) Padding(padding: const EdgeInsets.all(12), child: FilledButton(
          onPressed: _paging ? null : _older, child: Text(_paging ? 'در حال جستجو…' : 'جستجو در پیام‌های قدیمی‌تر'))),
      ]),
    );
  }
}

/// Chat list: messages matching across all joined chats («پیام‌ها»).
// ponytail: encrypted rooms are scanned from the 30 most recently active ones' loaded page only; per-chat search can page back.
class MessageResults extends StatefulWidget {
  final String term;
  final void Function(Event ev) onPick;
  const MessageResults({super.key, required this.term, required this.onPick});
  @override
  State<MessageResults> createState() => _MessageResultsState();
}

class _MessageResultsState extends State<MessageResults> with _Debounced<MessageResults> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) { if (mounted) query(widget.term); });
  }

  @override
  void didUpdateWidget(MessageResults old) {
    super.didUpdateWidget(old);
    if (old.term != widget.term) query(widget.term);
  }

  @override
  Future<List<Event>> run(String term) async {
    final joined = client.rooms.where((r) => r.membership == Membership.join && !r.isSpace).toList();
    final server = (await searchServer(term)).where((e) => e.room.membership == Membership.join && !e.room.isSpace && !e.room.encrypted);
    final local = <Event>[];
    final enc = joined.where((r) => r.encrypted).toList()
      ..sort((a, b) => (b.lastEvent?.originServerTs ?? DateTime(0)).compareTo(a.lastEvent?.originServerTs ?? DateTime(0)));
    for (final r in enc.take(30)) {
      try {
        final tl = await r.getTimeline();
        local.addAll(scanLoaded(tl, term));
        tl.cancelSubscriptions();
      } catch (_) {}
    }
    return _merge([...server, ...local]).take(50).toList();
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tk, r = res;
    if (widget.term.trim().length < _min) return const SizedBox.shrink();
    return ListView(children: [
      Padding(padding: const EdgeInsets.fromLTRB(16, 10, 16, 4), child: Text('پیام‌ها', style: TextStyle(color: t.accent, fontWeight: FontWeight.w600))),
      if (r == null) const Padding(padding: EdgeInsets.all(24), child: Center(child: CircularProgressIndicator(strokeWidth: 2))),
      if (r != null && r.isEmpty) Padding(padding: const EdgeInsets.all(16), child: Text('پیامی پیدا نشد', style: TextStyle(color: t.muted))),
      for (final e in r ?? const <Event>[]) _Row(ev: e, term: widget.term, showRoom: true, onTap: () => widget.onPick(e)),
    ]);
  }
}
