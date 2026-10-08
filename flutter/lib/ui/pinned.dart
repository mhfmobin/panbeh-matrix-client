import 'package:flutter/material.dart';
import 'package:matrix/matrix.dart';

import '../logic.dart';
import '../theme.dart';
import 'common.dart';

List<String> pinnedIds(Room room) => room.pinnedEventIds;

Future<void> togglePin(Room room, String id) {
  final ids = pinnedIds(room);
  return room.setPinnedEvents(ids.contains(id) ? ids.where((x) => x != id).toList() : [...ids, id]);
}

bool canPin(Room room) => room.canChangeStateEvent(EventTypes.RoomPinnedEvents);

final _fetched = <String, Future<Event?>>{};

/// An event by id: from the server/db (and decrypted) when it isn't loaded. Cached.
Future<Event?> loadEvent(Room room, String id) => _fetched.putIfAbsent(id, () async {
  try {
    final e = await room.getEventById(id);
    if (e == null) _fetched.remove(id);
    return e;
  } catch (_) {
    _fetched.remove(id);
    return null;
  }
});

/// Telegram-style bar under the header: one pin at a time; a tap jumps to it and moves on to the next older one.
class PinnedBar extends StatefulWidget {
  final Room room;
  final Future<void> Function(String id) onJump;
  const PinnedBar({super.key, required this.room, required this.onJump});
  @override
  State<PinnedBar> createState() => _PinnedBarState();
}

class _PinnedBarState extends State<PinnedBar> {
  int? _idx; // null = the newest

  @override
  Widget build(BuildContext context) {
    final t = context.tk, ids = pinnedIds(widget.room);
    if (ids.isEmpty) return const SizedBox.shrink();
    final i = _idx == null || _idx! >= ids.length ? ids.length - 1 : _idx!;
    final shown = ids.length > 4 ? ids.skip(ids.length - 4).toList() : ids;
    return Material(color: t.panel, child: Container(
      decoration: BoxDecoration(border: Border(bottom: BorderSide(color: t.border))),
      height: 48,
      child: Row(children: [
        Expanded(child: InkWell(
          onTap: () { widget.onJump(ids[i]); setState(() => _idx = (i - 1 + ids.length) % ids.length); },
          child: Padding(padding: const EdgeInsetsDirectional.only(start: 12), child: Row(children: [
            if (ids.length > 1) Padding(padding: const EdgeInsetsDirectional.only(end: 8), child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
              for (final id in shown) Container(width: 2.5, height: 36 / shown.length - 2, margin: const EdgeInsets.symmetric(vertical: 1),
                decoration: BoxDecoration(color: id == ids[i] ? t.accent : t.accent.withValues(alpha: .3), borderRadius: BorderRadius.circular(2))),
            ])) else Container(width: 2.5, height: 34, margin: const EdgeInsetsDirectional.only(end: 8), decoration: BoxDecoration(color: t.accent, borderRadius: BorderRadius.circular(2))),
            Expanded(child: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('پیام سنجاق‌شده${ids.length > 1 ? ' ${faNum(i + 1)} از ${faNum(ids.length)}' : ''}', style: TextStyle(color: t.accent, fontSize: 13, fontWeight: FontWeight.w600)),
              FutureBuilder(future: loadEvent(widget.room, ids[i]), builder: (_, s) => Text(s.data == null ? '…' : previewText(s.data!),
                maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(color: t.text, fontSize: 13.5))),
            ])),
          ])),
        )),
        IconButton(icon: Icon(Icons.format_list_bulleted, color: t.muted), tooltip: 'همه‌ی پیام‌های سنجاق‌شده', onPressed: () => _list(context)),
      ]),
    ));
  }

  void _list(BuildContext context) => showModalBottomSheet<void>(context: context, isScrollControlled: true, builder: (c) => SafeArea(child: StreamBuilder(
    stream: widget.room.client.onSync.stream,
    builder: (_, _) {
      final ids = pinnedIds(widget.room);
      if (ids.isEmpty) WidgetsBinding.instance.addPostFrameCallback((_) { if (c.mounted) Navigator.pop(c); });
      return ConstrainedBox(constraints: BoxConstraints(maxHeight: MediaQuery.sizeOf(c).height * .7), child: Column(mainAxisSize: MainAxisSize.min, children: [
        Padding(padding: const EdgeInsets.all(16), child: Text('پیام‌های سنجاق‌شده (${faNum(ids.length)})', style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600))),
        Flexible(child: ListView(shrinkWrap: true, children: [for (final id in ids.reversed) _Row(widget.room, id, (id) { Navigator.pop(c); widget.onJump(id); })])),
      ]));
    },
  )));
}

class _Row extends StatelessWidget {
  final Room room;
  final String id;
  final void Function(String) onJump;
  const _Row(this.room, this.id, this.onJump);
  @override
  Widget build(BuildContext context) => FutureBuilder(future: loadEvent(room, id), builder: (_, s) {
    final ev = s.data;
    return ListTile(
      onTap: () => onJump(id),
      leading: ev == null ? Avatar(mxc: null, name: '…', id: id, size: 42) : Avatar(mxc: ev.senderFromMemoryOrFallback.avatarUrl, name: senderName(ev), id: ev.senderId, size: 42),
      title: Text(ev == null ? '…' : senderName(ev), maxLines: 1, overflow: TextOverflow.ellipsis),
      subtitle: Text(ev == null ? (s.connectionState == ConnectionState.done ? 'پیام در دسترس نیست' : 'در حال بارگذاری…') : previewText(ev), maxLines: 1, overflow: TextOverflow.ellipsis),
      trailing: canPin(room) ? IconButton(icon: const Icon(Icons.close, size: 20), tooltip: 'برداشتن سنجاق', onPressed: () => attempt(context, () => togglePin(room, id))) : null,
    );
  });
}
