import 'package:flutter/material.dart';
import 'package:matrix/matrix.dart';

import '../logic.dart';
import 'common.dart';

/// Who (besides me) has read up to `ev`, newest first.
/// ponytail: ts is the user's *latest* receipt, which can be later than when they read this exact message;
/// Matrix only keeps the latest receipt per user, so it can't be exact.
List<({String id, int ts})> seenBy(Event ev, Timeline tl) {
  final room = ev.room, rs = room.receiptState, at = tl.events.indexWhere((e) => e.eventId == ev.eventId);
  if (at < 0 || !ev.status.isSynced) return [];
  final out = <String, int>{};
  // anyone whose receipt sits on a later event read this one too (events are newest first)
  for (final r in [...rs.global.otherUsers.entries, ...?rs.mainThread?.otherUsers.entries]) {
    if (r.key == me() || room.getState(EventTypes.RoomMember, r.key)?.content['membership'] != 'join') continue;
    final i = tl.events.indexWhere((e) => e.eventId == r.value.eventId);
    // a receipt outside the loaded page: judge by time
    if (i >= 0 ? i <= at : r.value.ts >= ev.originServerTs.millisecondsSinceEpoch) {
      if (r.value.ts > (out[r.key] ?? -1)) out[r.key] = r.value.ts;
    }
  }
  return [for (final e in out.entries) (id: e.key, ts: e.value)]..sort((a, b) => b.ts - a.ts);
}

void showSeenBy(BuildContext context, Event ev, Timeline tl) {
  final seen = seenBy(ev, tl), room = ev.room;
  showModalBottomSheet<void>(context: context, isScrollControlled: true, builder: (c) => SafeArea(child: ConstrainedBox(
    constraints: BoxConstraints(maxHeight: MediaQuery.sizeOf(c).height * .6),
    child: Column(mainAxisSize: MainAxisSize.min, children: [
      Padding(padding: const EdgeInsets.all(16), child: Text(seen.isEmpty ? 'هنوز کسی ندیده است' : 'دیده‌شده توسط ${faNum(seen.length)} نفر', style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600))),
      Flexible(child: ListView(shrinkWrap: true, children: [for (final s in seen) Builder(builder: (_) {
        final u = room.unsafeGetUserFromMemoryOrFallback(s.id);
        return ListTile(
          leading: Avatar(mxc: u.avatarUrl, name: u.calcDisplayname(), id: s.id, size: 40),
          title: Text(u.calcDisplayname(), maxLines: 1, overflow: TextOverflow.ellipsis),
          subtitle: s.ts > 0 ? Text(stamp(s.ts)) : null,
        );
      })])),
    ]),
  )));
}
