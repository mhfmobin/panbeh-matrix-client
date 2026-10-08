import 'package:flutter/material.dart';
import 'package:matrix/matrix.dart';

import '../logic.dart';
import '../matrix.dart';
import '../theme.dart';
import 'chat.dart';
import 'common.dart';
import 'message.dart';

/// Content for a copy of `ev` (its display event, edits applied) in another room; remembers the original author across re-forwards.
Map<String, Object?> forwardContent(Event ev) {
  final c = Map<String, Object?>.from(ev.content)..remove('m.relates_to')..remove('m.new_content');
  c['m.mentions'] = {}; // a forward must not ping anyone
  if (c['body'] is String) c['body'] = stripReplyFallback(c['body'] as String);
  if (c['formatted_body'] is String) c['formatted_body'] = (c['formatted_body'] as String).replaceFirst(RegExp(r'<mx-reply>[\s\S]*?</mx-reply>'), '');
  c['app.panbeh.forwarded'] = ev.content['app.panbeh.forwarded'] ?? {'sender': ev.senderId, 'name': senderName(ev)};
  return c;
}

Future<void> forwardTo(Room to, Event ev) => to.sendEvent(forwardContent(ev), type: ev.type);

/// `evs` are display events in timeline order; polls can't be forwarded. onSent runs after a successful send (the chat leaves selection mode).
void showForward(BuildContext context, List<Event> evs, {VoidCallback? onSent}) => showModalBottomSheet<void>(
  context: context, isScrollControlled: true, useSafeArea: true,
  builder: (c) => _ForwardSheet(evs, Navigator.of(context), onSent),
);

class _ForwardSheet extends StatefulWidget {
  final List<Event> evs;
  final NavigatorState nav;
  final VoidCallback? onSent;
  const _ForwardSheet(this.evs, this.nav, this.onSent);
  @override
  State<_ForwardSheet> createState() => _ForwardSheetState();
}

class _ForwardSheetState extends State<_ForwardSheet> {
  var _q = '';
  final _sel = <String>[];
  var _busy = false;

  Future<void> _send() async {
    setState(() => _busy = true);
    final list = widget.evs.where((e) => !pollStart.contains(e.type)).toList(), from = widget.evs.first.room;
    final rooms = [for (final id in _sel) client.getRoomById(id)!];
    try {
      await Future.wait([for (final r in rooms) () async { for (final e in list) { await forwardTo(r, e); } }()]); // rooms in parallel, messages in order
      widget.onSent?.call();
      if (!mounted) return;
      Navigator.pop(context);
      if (rooms.length == 1 && rooms.first.id != from.id) widget.nav.push(MaterialPageRoute<void>(builder: (_) => ChatPage(room: rooms.first)));
    } catch (e) {
      if (mounted) { setState(() => _busy = false); alert(context, errText(e)); }
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tk, q = _q.trim().toLowerCase();
    final shown = [for (final r in client.rooms) if (r.membership == Membership.join && roomTitle(r).toLowerCase().contains(q)) r];
    return Padding(padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom), child: Column(children: [
      const Padding(padding: EdgeInsets.fromLTRB(16, 16, 16, 8), child: Align(alignment: AlignmentDirectional.centerStart, child: Text('هدایت به…', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600)))),
      Padding(padding: const EdgeInsets.symmetric(horizontal: 12), child: TextField(
        onChanged: (v) => setState(() => _q = v),
        decoration: InputDecoration(hintText: 'جستجو', prefixIcon: const Icon(Icons.search, size: 20), isDense: true, filled: true, fillColor: t.hover, border: OutlineInputBorder(borderRadius: BorderRadius.circular(20), borderSide: BorderSide.none)),
      )),
      Expanded(child: ListView(children: [for (final r in shown) ListTile(
        leading: RoomAvatar(r, size: 42),
        title: Text(roomTitle(r), maxLines: 1, overflow: TextOverflow.ellipsis),
        trailing: _sel.contains(r.id) ? Icon(Icons.check_circle, color: t.accent) : Icon(Icons.radio_button_unchecked, color: t.muted),
        onTap: () => setState(() => _sel.contains(r.id) ? _sel.remove(r.id) : _sel.add(r.id)),
      )])),
      Padding(padding: const EdgeInsets.all(12), child: SizedBox(width: double.infinity, child: FilledButton(
        onPressed: _sel.isEmpty || _busy ? null : _send,
        child: Text(_busy ? 'در حال ارسال…' : 'ارسال به ${faNum(_sel.length)} گفتگو'),
      ))),
    ]));
  }
}

/// The chat's app bar while messages are selected: count, copy, forward, delete. `picked` is in timeline order.
AppBar selectionBar(BuildContext context, Timeline tl, List<Event> picked, VoidCallback exit) {
  void copy() {
    final parts = [for (final e in picked) (e, copyTextOf(e.getDisplayEvent(tl)))].where((p) => p.$2.isNotEmpty)
        .map((p) => picked.length == 1 ? p.$2 : '${senderName(p.$1)}, [${stamp(p.$1.originServerTs.millisecondsSinceEpoch)}]:\n${p.$2}');
    if (parts.isNotEmpty) copyText(context, parts.join('\n\n'));
    exit();
  }
  Future<void> del() async {
    if (!await confirm(context, '${faNum(picked.length)} پیام برای همه حذف شود؟', ok: 'حذف', danger: true) || !context.mounted) return;
    exit();
    await attempt(context, () async { await Future.wait([for (final e in picked) e.room.redactEvent(e.eventId)]); });
  }
  return AppBar(
    leading: IconButton(icon: const Icon(Icons.close), onPressed: exit),
    title: Text(faNum(picked.length)),
    actions: [
      IconButton(icon: const Icon(Icons.copy_outlined), tooltip: 'کپی', onPressed: copy),
      IconButton(icon: const Icon(Icons.forward), tooltip: 'هدایت', onPressed: () => showForward(context, [for (final e in picked) e.getDisplayEvent(tl)], onSent: exit)),
      if (picked.isNotEmpty && picked.every((e) => e.canRedact)) IconButton(icon: const Icon(Icons.delete_outline), tooltip: 'حذف', onPressed: del),
    ],
  );
}
