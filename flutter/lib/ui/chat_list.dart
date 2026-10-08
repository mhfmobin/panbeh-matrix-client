import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:matrix/matrix.dart';

import '../chats.dart';
import '../logic.dart';
import '../main.dart';
import '../matrix.dart';
import '../prefs.dart';
import '../theme.dart';
import 'chat.dart';
import 'common.dart';
import 'settings.dart';

String _folderKey() => 'panbeh.folder:${client.userID}';

class ChatList extends StatefulWidget {
  const ChatList({super.key});
  @override
  State<ChatList> createState() => _ChatListState();
}

class _ChatListState extends State<ChatList> with TickerProviderStateMixin {
  final subs = <StreamSubscription>[];
  final search = TextEditingController();
  TabController? tabs;
  List<String> tabIds = [];
  late String folder = prefs.store.getString(_folderKey()) ?? 'all';
  List<String>? mine; // our reorder, until the server's copy catches up
  bool queued = false, searching = false, archive = false, connecting = false;
  final selected = <String>{};
  final hidden = <String>{}; // swiped away, until the sync shows it

  /// Rebuild on SDK changes, at most once a frame: the SDK is the store.
  void tick() {
    if (queued || !mounted) return;
    queued = true;
    SchedulerBinding.instance.scheduleFrameCallback((_) {
      queued = false;
      if (mounted) setState(() {});
    });
  }

  @override
  void initState() {
    super.initState();
    subs.add(client.onSync.stream.listen((_) => tick()));
    subs.add(client.onSyncStatus.stream.listen((s) {
      connecting = s.status == SyncStatus.error;
      tick();
    }));
  }

  @override
  void dispose() {
    for (final s in subs) {
      s.cancel();
    }
    tabs?.dispose();
    search.dispose();
    super.dispose();
  }

  void pick(String id) {
    folder = id;
    prefs.store.setString(_folderKey(), id);
  }

  /// One TabController per folder set; rebuilt when a space is joined or left.
  void syncTabs(List<Folder> folders) {
    final ids = [for (final f in folders) f.id];
    if (tabs != null && ids.join('\n') == tabIds.join('\n')) return;
    final old = tabs;
    tabIds = ids;
    if (!ids.contains(folder)) folder = 'all'; // a remembered space we left
    final c = tabs = TabController(length: ids.length, vsync: this, initialIndex: ids.indexOf(folder));
    c.addListener(() {
      if (c != tabs || ids[c.index] == folder) return;
      setState(() => pick(ids[c.index]));
    });
    if (old != null) WidgetsBinding.instance.addPostFrameCallback((_) => old.dispose());
  }

  void open(RoomRow r) {
    if (r.invite) {
      Navigator.push(context, MaterialPageRoute(builder: (_) => InvitePage(room: r.room)));
      return;
    }
    if (r.room.markedUnread) r.room.markUnread(false).catchError((_) {}); // opening a chat clears "marked unread"
    Navigator.push(context, MaterialPageRoute(builder: (_) => ChatPage(room: r.room)));
  }

  void toggle(RoomRow r) => setState(() => selected.remove(r.id) || selected.add(r.id));

  Future<void> bulk(List<RoomRow> rows, Future<void> Function(RoomRow) f) async {
    final picked = rows.where((r) => selected.contains(r.id)).toList();
    setState(selected.clear);
    await attempt(context, () async {
      for (final r in picked) {
        await f(r);
      }
    });
    tick();
  }

  Future<void> swipeArchive(RoomRow r) async {
    final to = !r.archived;
    setState(() => hidden.add(r.id));
    try {
      await setTag(r.room, archivedTag, to);
      await client.onSync.stream.first.timeout(const Duration(seconds: 5), onTimeout: () => SyncUpdate(nextBatch: ''));
    } catch (e) {
      if (mounted) toast(context, errText(e));
    }
    if (mounted) setState(() => hidden.remove(r.id));
  }

  void newThing() => showModalBottomSheet(
    context: context,
    builder: (c) => SafeArea(child: Column(mainAxisSize: MainAxisSize.min, children: [
      for (final (icon, label) in const [(Icons.edit_outlined, 'پیام جدید'), (Icons.group_add_outlined, 'گروه جدید'),
          (Icons.workspaces_outlined, 'فضای جدید'), (Icons.login, 'پیوستن به گفتگو')])
        ListTile(leading: Icon(icon), title: Text(label), onTap: () { Navigator.pop(c); toast(context, 'به‌زودی'); }),
    ])),
  );

  void reorder(List<Folder> folders) => showModalBottomSheet(
    context: context,
    isScrollControlled: true,
    builder: (c) {
      var ids = [for (final f in folders) f.id];
      final label = {for (final f in folders) f.id: f.label};
      return StatefulBuilder(builder: (c, set) => SafeArea(child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: MediaQuery.sizeOf(c).height * .7),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          const Padding(padding: EdgeInsets.all(16), child: Text('ترتیب پوشه‌ها', style: TextStyle(fontWeight: FontWeight.w600, fontSize: 16))),
          Flexible(child: ReorderableListView(
            shrinkWrap: true,
            buildDefaultDragHandles: false,
            onReorderItem: (from, to) {
              from++; to++; // "all" stays first and isn't listed
              ids = moveFolder(ids, from, to);
              set(() {});
              mine = ids;
              setOrder(ids);
            },
            children: [
              for (var i = 1; i < ids.length; i++)
                ListTile(key: ValueKey(ids[i]), title: Text(label[ids[i]]!),
                  trailing: ReorderableDragStartListener(index: i - 1, child: const Icon(Icons.drag_handle))),
            ],
          )),
        ]),
      )));
    },
  );

  void setOrder(List<String> ids) {
    setState(() => mine = ids);
    setFolderOrder(ids).catchError((_) => setState(() => mine = null));
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tk;
    final (:rows, :spaces) = roomRows();
    final order = mine ?? remoteFolderOrder() ?? cachedFolderOrder();
    final folders = applyFolderOrder([...baseFolders, for (final s in spaces) Folder(s.id, roomTitle(s))], order);
    syncTabs(folders);
    final visible = rows.where((r) => !hidden.contains(r.id)).toList();
    final archived = visible.where((r) => inFolder(r, 'archive')).toList();
    if (archive && archived.isEmpty) archive = false; // last one unarchived
    int unreadIn(String id) => visible.where((r) => isUnread(r) && inFolder(r, id)).length;
    final q = normalize(search.text.trim());
    final selecting = selected.isNotEmpty;
    final picked = visible.where((r) => selected.contains(r.id)).toList();
    final inList = !searching && !archive;

    Widget list(List<RoomRow> items, {bool showArchive = false}) {
      if (items.isEmpty && !showArchive) return Center(child: Text(searching ? 'گفتگویی پیدا نشد' : 'هنوز چیزی اینجا نیست', style: TextStyle(color: t.muted)));
      final extra = showArchive ? 1 : 0;
      return ListView.builder(
        itemExtent: 72,
        itemCount: items.length + extra,
        itemBuilder: (c, i) {
          if (i < extra) return _ArchiveRow(count: archived.length, unread: unreadIn('archive'), onTap: () => setState(() => archive = true));
          final r = items[i - extra];
          final tile = _Tile(row: r, selected: selected.contains(r.id), onTap: () => selecting ? toggle(r) : open(r),
            onLong: r.invite ? null : () => toggle(r));
          if (selecting || r.invite) return KeyedSubtree(key: ValueKey(r.id), child: tile);
          return Dismissible(
            key: ValueKey(r.id),
            direction: DismissDirection.endToStart,
            background: Container(
              color: t.accent, alignment: AlignmentDirectional.centerEnd, padding: const EdgeInsetsDirectional.only(end: 24),
              child: Column(mainAxisSize: MainAxisSize.min, children: [
                Icon(r.archived ? Icons.unarchive_outlined : Icons.archive_outlined, color: Colors.white),
                Text(r.archived ? 'خروج از بایگانی' : 'بایگانی', style: const TextStyle(color: Colors.white, fontSize: 12)),
              ]),
            ),
            onDismissed: (_) {
              swipeArchive(r);
              ScaffoldMessenger.of(context).hideCurrentSnackBar();
              ScaffoldMessenger.of(context).showSnackBar(SnackBar(
                content: Text(r.archived ? 'از بایگانی خارج شد' : 'بایگانی شد'),
                action: SnackBarAction(label: 'بازگرداندن', onPressed: () => setTag(r.room, archivedTag, r.archived).catchError((_) {})),
              ));
            },
            child: tile,
          );
        },
      );
    }

    final Widget body = searching
        ? list(visible.where((r) => q.isEmpty || normalize(roomTitle(r.room)).contains(q)).toList())
        : archive
            ? list(archived)
            : TabBarView(controller: tabs, children: [
                for (final f in folders) list(visible.where((r) => inFolder(r, f.id)).toList(), showArchive: f.id == 'all' && archived.isNotEmpty),
              ]);

    final PreferredSizeWidget? tabBar = inList && !selecting
        ? TabBar(
            controller: tabs, isScrollable: true, tabAlignment: TabAlignment.start,
            labelColor: Colors.white, unselectedLabelColor: Colors.white70, indicatorColor: Colors.white, dividerColor: Colors.transparent,
            tabs: [
              for (final f in folders)
                Tab(child: GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onLongPress: () => reorder(folders),
                  child: Row(mainAxisSize: MainAxisSize.min, spacing: 6, children: [
                    Text(f.label),
                    if (f.id != 'unread' && unreadIn(f.id) > 0) _Badge(faNum(unreadIn(f.id)), bg: Colors.white, fg: t.accent),
                  ]),
                )),
            ],
          )
        : null;

    return PopScope(
      canPop: !(selecting || searching || archive),
      onPopInvokedWithResult: (didPop, _) {
        if (didPop) return;
        setState(() {
          if (selecting) { selected.clear(); } else if (searching) { searching = false; search.clear(); } else { archive = false; }
        });
      },
      child: Scaffold(
        drawer: const _Drawer(),
        appBar: AppBar(
          leading: selecting ? IconButton(icon: const Icon(Icons.close), onPressed: () => setState(selected.clear))
              : searching || archive ? IconButton(icon: const Icon(Icons.arrow_back), onPressed: () => setState(() { searching = false; archive = false; search.clear(); }))
              : null,
          title: selecting ? Text(faNum(selected.length))
              : searching ? TextField(
                  controller: search, autofocus: true, onChanged: (_) => setState(() {}),
                  style: const TextStyle(color: Colors.white, fontSize: 18), cursorColor: Colors.white,
                  decoration: const InputDecoration(hintText: 'جستجو', hintStyle: TextStyle(color: Colors.white70), border: InputBorder.none))
              : archive ? const Text('بایگانی')
              : connecting ? const Row(mainAxisSize: MainAxisSize.min, spacing: 10, children: [
                  SizedBox.square(dimension: 16, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white)),
                  Text('در حال اتصال…'),
                ])
              : const Text('پنبه'),
          actions: selecting ? [
            IconButton(tooltip: 'سنجاق', icon: const Icon(Icons.push_pin_outlined),
              onPressed: () { final on = !picked.every((r) => r.pinned); bulk(visible, (r) => setTag(r.room, pinnedTag, on)); }),
            IconButton(tooltip: 'بی‌صدا', icon: Icon(picked.every((r) => r.muted) ? Icons.notifications_outlined : Icons.notifications_off_outlined),
              onPressed: () { final on = !picked.every((r) => r.muted); bulk(visible, (r) => setMuted(r.room, on)); }),
            IconButton(tooltip: 'بایگانی', icon: Icon(picked.every((r) => r.archived) ? Icons.unarchive_outlined : Icons.archive_outlined),
              onPressed: () { final on = !picked.every((r) => r.archived); bulk(visible, (r) => setTag(r.room, archivedTag, on)); }),
            IconButton(tooltip: 'خوانده‌شده/خوانده‌نشده', icon: Icon(picked.any(isUnread) ? Icons.done_all : Icons.mark_chat_unread_outlined),
              onPressed: () { final read = picked.any(isUnread); bulk(visible, (r) => read ? markRead(r.room) : setMarkedUnread(r.room, true)); }),
            IconButton(tooltip: 'حذف و خروج', icon: const Icon(Icons.delete_outline), onPressed: () async {
              final names = picked.length == 1 ? '«${roomTitle(picked.first.room)}»' : '${faNum(picked.length)} گفتگو';
              if (await confirm(context, 'از $names خارج می‌شوید و از فهرست حذف می‌شود؟', ok: 'حذف و خروج', danger: true)) bulk(visible, (r) => leaveAndForget(r.room));
            }),
          ] : [
            if (searching) IconButton(icon: const Icon(Icons.close), onPressed: () => setState(search.clear))
            else if (!archive) IconButton(icon: const Icon(Icons.search), onPressed: () => setState(() => searching = true)),
          ],
          bottom: tabBar,
        ),
        floatingActionButton: selecting ? null : FloatingActionButton(onPressed: newThing, child: const Icon(Icons.edit)),
        body: body,
      ),
    );
  }
}

class _Badge extends StatelessWidget {
  final String text;
  final Color bg, fg;
  const _Badge(this.text, {required this.bg, this.fg = Colors.white});
  @override
  Widget build(BuildContext context) => Container(
    constraints: const BoxConstraints(minWidth: 20, minHeight: 20),
    padding: const EdgeInsets.symmetric(horizontal: 6),
    alignment: Alignment.center,
    decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(10)),
    child: Text(text, style: TextStyle(color: fg, fontSize: 12, fontWeight: FontWeight.w600, height: 1.3)),
  );
}

class _ArchiveRow extends StatelessWidget {
  final int count, unread;
  final VoidCallback onTap;
  const _ArchiveRow({required this.count, required this.unread, required this.onTap});
  @override
  Widget build(BuildContext context) {
    final t = context.tk;
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12),
        child: Row(spacing: 12, children: [
          CircleAvatar(radius: 27, backgroundColor: t.muted.withValues(alpha: .6), child: const Icon(Icons.archive_outlined, color: Colors.white)),
          const Expanded(child: Text('بایگانی', style: TextStyle(fontWeight: FontWeight.w600, fontSize: 16))),
          Text(faNum(count), style: TextStyle(color: t.muted)),
          if (unread > 0) _Badge(faNum(unread), bg: t.muted),
        ]),
      ),
    );
  }
}

class _Tile extends StatelessWidget {
  final RoomRow row;
  final bool selected;
  final VoidCallback onTap;
  final VoidCallback? onLong;
  const _Tile({required this.row, required this.selected, required this.onTap, this.onLong});

  String preview() {
    final last = row.last;
    if (row.invite) return 'دعوت‌نامه';
    final typing = row.room.typingUsers.where((u) => u.id != me()).toList();
    if (typing.isNotEmpty) return '${row.isDM ? '' : '${bdi(typing.first.calcDisplayname().split(' ').first)} '}در حال نوشتن…';
    if (last == null) return '';
    if (isCallStart(last)) return '📞 ${noticeText(last) ?? ''}'; // names the caller itself
    final who = last.senderId == me() ? 'شما' : row.isDM ? '' : bdi(senderName(last).split(' ').first);
    return '${who.isEmpty ? '' : '$who: '}${previewText(last)}';
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tk;
    final room = row.room, last = row.last;
    final typing = !row.invite && room.typingUsers.any((u) => u.id != me());
    final mentioned = row.unread > 0 && room.highlightCount > 0;
    final own = last != null && !row.invite && last.senderId == me();
    final badge = row.muted ? t.muted : t.accent;
    Widget? tick;
    if (own) {
      tick = last.status.isError ? const Icon(Icons.error_outline, size: 16, color: Color(0xffe53935))
          : last.status.isSending ? Icon(Icons.schedule, size: 15, color: t.muted)
          : last.receipts.any((r) => r.user.id != me()) ? Icon(Icons.done_all, size: 17, color: t.readTick)
          : Icon(Icons.done, size: 17, color: t.readTick);
    }
    return Material(
      color: selected ? t.accent.withValues(alpha: .12) : Colors.transparent,
      child: InkWell(
        onTap: onTap, onLongPress: onLong,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12),
          child: Row(spacing: 12, children: [
            Stack(children: [
              RoomAvatar(room, size: 54),
              if (selected) PositionedDirectional(end: 0, bottom: 0, child: CircleAvatar(radius: 10, backgroundColor: t.accent,
                child: const Icon(Icons.check, size: 14, color: Colors.white))),
            ]),
            Expanded(child: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.stretch, spacing: 3, children: [
              Row(spacing: 4, children: [
                Expanded(child: Text(roomTitle(room), maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 16))),
                if (row.muted) Icon(Icons.volume_off, size: 15, color: t.muted),
                ?tick,
                Text(listTime(row.ts), style: TextStyle(color: t.muted, fontSize: 12.5)),
              ]),
              Row(spacing: 6, children: [
                Expanded(child: Text(preview(), maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(color: typing ? t.accent : t.muted, fontSize: 14.5))),
                if (mentioned) _Badge('@', bg: badge),
                if (row.invite) _Badge('!', bg: badge)
                else if (row.unread > 0) _Badge(faNum(row.unread), bg: badge)
                else if (row.marked) Container(width: 12, height: 12, decoration: BoxDecoration(color: badge, shape: BoxShape.circle))
                else if (row.pinned) Icon(Icons.push_pin, size: 16, color: t.muted),
              ]),
            ])),
          ]),
        ),
      ),
    );
  }
}

class _Drawer extends StatefulWidget {
  const _Drawer();
  @override
  State<_Drawer> createState() => _DrawerState();
}

class _DrawerState extends State<_Drawer> {
  bool open = false;
  late final profile = ownProfile();
  late final others = otherAccounts();

  @override
  Widget build(BuildContext context) {
    final t = context.tk;
    final root = context.findAncestorStateOfType<RootState>();
    return Drawer(
      child: ListView(padding: EdgeInsets.zero, children: [
        InkWell(
          onTap: () => setState(() => open = !open),
          child: Container(
            color: Theme.of(context).appBarTheme.backgroundColor,
            padding: EdgeInsets.fromLTRB(16, MediaQuery.paddingOf(context).top + 16, 16, 12),
            child: FutureBuilder(
              future: profile,
              builder: (context, s) => Column(crossAxisAlignment: CrossAxisAlignment.start, spacing: 4, children: [
                Avatar(mxc: s.data?.avatar, name: s.data?.name ?? '', id: client.userID!, size: 64),
                const SizedBox(height: 6),
                Row(children: [
                  Expanded(child: Text(s.data?.name ?? '', maxLines: 1, overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: Colors.white, fontSize: 17, fontWeight: FontWeight.w600))),
                  Icon(open ? Icons.expand_less : Icons.expand_more, color: Colors.white),
                ]),
                Text(client.userID!, textDirection: TextDirection.ltr, style: const TextStyle(color: Colors.white70, fontSize: 13)),
              ]),
            ),
          ),
        ),
        if (open) ...[
          FutureBuilder(
            future: others,
            builder: (context, s) => Column(children: [
              for (final a in s.data ?? const <({String name, String userId})>[])
                ListTile(
                  leading: Avatar(mxc: null, name: a.userId, id: a.userId, size: 40),
                  title: Text(a.userId, textDirection: TextDirection.ltr, style: const TextStyle(fontSize: 14)),
                  onTap: () async { await switchAccount(a.name); root?.restart(); },
                ),
            ]),
          ),
          ListTile(leading: const Icon(Icons.add), title: const Text('افزودن حساب'), onTap: () { Navigator.pop(context); root?.addAccount(); }),
          Divider(height: 1, color: t.border),
        ],
        ListTile(leading: const Icon(Icons.settings_outlined), title: const Text('تنظیمات'), onTap: () {
          Navigator.pop(context);
          Navigator.push(context, MaterialPageRoute(builder: (_) => const SettingsPage()));
        }),
        ListTile(leading: const Icon(Icons.logout), title: const Text('خروج از این حساب'), onTap: () => confirmLogout(context)),
      ]),
    );
  }
}

/// A pending invite: who asked, and join or decline.
class InvitePage extends StatefulWidget {
  final Room room;
  const InvitePage({super.key, required this.room});
  @override
  State<InvitePage> createState() => _InvitePageState();
}

class _InvitePageState extends State<InvitePage> {
  bool busy = false;

  Future<void> act(Future<void> Function() f, {bool open = false}) async {
    setState(() => busy = true);
    await attempt(context, () async {
      await f();
      if (!mounted) return;
      if (open) {
        // DM invites are recorded in m.direct by room.join()
        Navigator.pushReplacement(context, MaterialPageRoute(builder: (_) => ChatPage(room: widget.room)));
      } else {
        Navigator.pop(context);
      }
    });
    if (mounted) setState(() => busy = false);
  }

  @override
  Widget build(BuildContext context) {
    final room = widget.room, inviter = room.directChatMatrixID;
    return Scaffold(
      appBar: AppBar(),
      body: Center(child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(mainAxisSize: MainAxisSize.min, spacing: 12, children: [
          RoomAvatar(room, size: 88),
          Text(roomTitle(room), style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w600)),
          Text(inviter != null ? '${bdi(room.unsafeGetUserFromMemoryOrFallback(inviter).calcDisplayname())} شما را دعوت کرد' : 'به این گفتگو دعوت شده‌اید',
            style: TextStyle(color: context.tk.muted)),
          const SizedBox(height: 12),
          Row(mainAxisSize: MainAxisSize.min, spacing: 12, children: [
            OutlinedButton(onPressed: busy ? null : () => act(room.leave), child: const Text('رد')),
            FilledButton(onPressed: busy ? null : () => act(() => room.join(waitForSync: true), open: true), child: const Text('پیوستن')),
          ]),
        ]),
      )),
    );
  }
}
