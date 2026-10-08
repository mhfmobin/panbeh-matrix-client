import 'package:flutter/material.dart';
import 'package:matrix/matrix.dart';

import '../logic.dart';
import '../theme.dart';
import 'common.dart';

const quickReactions = ['👍', '❤️', '😂', '😮', '😢', '🔥'];

/// emoji → the reaction events, biggest group first.
List<MapEntry<String, List<Event>>> reactionsOf(Event ev, Timeline tl) {
  final by = <String, List<Event>>{};
  for (final r in ev.aggregatedEvents(tl, RelationshipTypes.reaction)) {
    final key = r.content.tryGetMap<String, Object?>('m.relates_to')?.tryGet<String>('key');
    if (key != null && !r.redacted) (by[key] ??= []).add(r);
  }
  return by.entries.toList()..sort((a, b) => b.value.length - a.value.length);
}

/// Taking back my own reaction = redacting my annotation. A reaction in a thread needs no thread marker here: the annotation is all that's sent.
Future<void> toggleReaction(BuildContext context, Event ev, Timeline tl, String key) async {
  final mine = reactionsOf(ev, tl).where((e) => e.key == key).expand((e) => e.value).where((r) => r.senderId == me()).firstOrNull;
  if (mine != null && !mine.status.isSynced) return; // still sending
  await attempt(context, () async { await (mine != null ? ev.room.redactEvent(mine.eventId) : ev.room.sendReaction(ev.eventId, key)); });
}

/// Telegram's strip on top of the message menu.
class QuickReactions extends StatelessWidget {
  final void Function(String) onPick;
  final VoidCallback onMore;
  const QuickReactions({super.key, required this.onPick, required this.onMore});
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
    child: Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
      for (final k in quickReactions) InkResponse(onTap: () => onPick(k), radius: 22, child: Padding(padding: const EdgeInsets.all(5), child: Text(k, style: const TextStyle(fontSize: 26)))),
      IconButton(onPressed: onMore, icon: Icon(Icons.keyboard_arrow_down, color: context.tk.muted), visualDensity: VisualDensity.compact),
    ]),
  );
}

/// Chips under the bubble: emoji + count, mine highlighted; tap toggles, long-press lists who reacted.
class ReactionChips extends StatelessWidget {
  final Event ev;
  final Timeline timeline;
  const ReactionChips(this.ev, this.timeline, {super.key});
  @override
  Widget build(BuildContext context) {
    final list = reactionsOf(ev, timeline);
    if (list.isEmpty) return const SizedBox.shrink();
    final t = context.tk;
    return Padding(padding: const EdgeInsets.only(top: 3), child: Wrap(spacing: 4, runSpacing: 4, children: [
      for (final e in list) Builder(builder: (_) {
        final on = e.value.any((r) => r.senderId == me());
        return Material(
          color: on ? t.accent : t.accent.withValues(alpha: .16), borderRadius: BorderRadius.circular(14),
          child: InkWell(
            borderRadius: BorderRadius.circular(14),
            onTap: () => toggleReaction(context, ev, timeline, e.key),
            onLongPress: () => showReactors(context, ev, timeline),
            child: Padding(padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3), child: Row(mainAxisSize: MainAxisSize.min, children: [
              Text(e.key, style: const TextStyle(fontSize: 15)),
              const SizedBox(width: 4),
              Text(faNum(e.value.length), style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: on ? Colors.white : t.accent)),
            ])),
          ),
        );
      }),
    ]));
  }
}

/// «واکنش‌ها»: who reacted with what, a tab per emoji.
void showReactors(BuildContext context, Event ev, Timeline tl) {
  final list = reactionsOf(ev, tl);
  if (list.isEmpty) return;
  final all = [for (final e in list) for (final r in e.value) (emoji: e.key, ev: r)]..sort((a, b) => b.ev.originServerTs.compareTo(a.ev.originServerTs));
  showModalBottomSheet<void>(context: context, isScrollControlled: true, builder: (c) => SafeArea(child: SizedBox(
    height: MediaQuery.sizeOf(c).height * .6,
    child: DefaultTabController(length: list.length + 1, child: Column(children: [
      TabBar(isScrollable: true, tabAlignment: TabAlignment.start, tabs: [
        Tab(text: 'همه ${faNum(all.length)}'),
        for (final e in list) Tab(text: '${e.key} ${faNum(e.value.length)}'),
      ]),
      Expanded(child: TabBarView(children: [
        for (final rows in [all, for (final e in list) [for (final r in e.value) (emoji: e.key, ev: r)]])
          ListView(children: [for (final r in rows) ListTile(
            leading: Avatar(mxc: r.ev.senderFromMemoryOrFallback.avatarUrl, name: senderName(r.ev), id: r.ev.senderId, size: 40),
            title: Text(senderName(r.ev), maxLines: 1, overflow: TextOverflow.ellipsis),
            trailing: Text(r.emoji, style: const TextStyle(fontSize: 22)),
          )]),
      ])),
    ])),
  )));
}
