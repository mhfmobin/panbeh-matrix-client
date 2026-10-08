import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:matrix/matrix.dart';

import '../chats.dart' show isMuted, setMuted;
import '../logic.dart';
import '../matrix.dart';
import '../theme.dart';
import 'common.dart';
import 'composer.dart';
import 'message.dart';
import 'search.dart';
import 'thread.dart';
import 'forward.dart';
import 'pinned.dart';
import 'poll.dart';
import 'voice.dart';

class ChatPage extends StatefulWidget {
  final Room room;
  final String? eventId; // jump to this message once loaded (search result, link)
  final bool fromSearch; // looks further back
  const ChatPage({super.key, required this.room, this.eventId, this.fromSearch = false});
  @override
  State<ChatPage> createState() => _ChatPageState();
}

// ponytail: a fixed-height estimate (and no scroll anchoring when new messages arrive while scrolled up) instead of a virtual-list package
const _seenTtl = 60000;
final _seen = <String, ({bool online, int? ts, int at})>{};

class _ChatPageState extends State<ChatPage> with WidgetsBindingObserver {
  Room get room => widget.room;
  Timeline? _tl;
  final _sc = ScrollController();
  final _listKey = GlobalKey();
  final _keys = <String, GlobalKey>{};
  StreamSubscription? _sync;
  Timer? _hide, _flashT, _presence;
  Mode? _mode;
  List<TimelineRow> _rows = [];
  String? _unreadAfter, _bottomId, _sentRead, _flash;
  Set<String>? _sel; // selected event ids (multi-select mode)
  bool _atBottom = true, _far = false, _resumed = true, _busy = false;
  int _triedAt = -1;
  ({String label, bool show}) _floating = (label: '', show: false);

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _sc.addListener(_onScroll);
    _sync = client.onSync.stream.listen((_) { if (mounted) setState(() {}); });
    // lazy-loaded sync only has members who spoke recently
    room.requestParticipants().then((_) { if (mounted) setState(() {}); }, onError: (_) {});
    _loadSeen();
    _presence = Timer.periodic(const Duration(seconds: 60), (_) => _loadSeen());
    room.getTimeline(onUpdate: () { if (mounted) setState(() {}); }).then((tl) {
      if (!mounted) return tl.cancelSubscriptions();
      _tl = tl;
      _unreadAfter = _anchor(tl.events);
      final anchored = _unreadAfter == null ? null : _byRowId(tl.events, _unreadAfter!);
      _bottomId = (anchored ?? tl.events.firstOrNull)?.eventId;
      setState(() {});
      // opening at the divider mustn't mark everything read
      final go = widget.eventId;
      if (go != null) {
        WidgetsBinding.instance.addPostFrameCallback((_) => _jump(go, pages: widget.fromSearch ? 50 : 10));
      } else if (_unreadAfter != null) {
        WidgetsBinding.instance.addPostFrameCallback((_) => _reveal('unread', .3));
      }
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _tl?.cancelSubscriptions();
    _sync?.cancel();
    _hide?.cancel();
    _flashT?.cancel();
    _presence?.cancel();
    _sc.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState s) {
    _resumed = s == AppLifecycleState.resumed;
    if (mounted) setState(() {});
  }

  // ---------- data ----------

  // thread replies live in their thread, not the main timeline
  static bool _shown(Event ev) => (isMessage(ev) || noticeText(ev) != null) && ev.relationshipType != RelationshipTypes.thread;
  static String _rowId(Event ev) => ev.transactionId ?? ev.eventId; // stable across local echo → remote echo, so rows don't remount
  static Event? _byRowId(List<Event> evs, String id) => evs.where((e) => _rowId(e) == id).firstOrNull;

  /// Row id of the last visible event I've read (fully-read marker, receipt or my own event, whichever is newest),
  /// if messages from others follow it.
  String? _anchor(List<Event> newestFirst) {
    final evs = newestFirst.reversed.toList();
    final marker = room.fullyRead, receipt = room.receiptState.global.latestOwnReceipt?.eventId;
    var read = -1;
    for (var i = 0; i < evs.length; i++) {
      if (evs[i].eventId == marker || evs[i].eventId == receipt || evs[i].senderId == me()) read = i;
    }
    // ponytail: a read point older than the loaded page gives no divider
    if (read < 0 || !evs.skip(read + 1).any((e) => isMessage(e) && e.senderId != me())) return null;
    for (var i = read; i >= 0; i--) {
      if (_shown(evs[i])) return _rowId(evs[i]);
    }
    return null;
  }

  Future<void> _loadSeen() async {
    final peer = room.directChatMatrixID;
    if (peer == null || room.membership != Membership.join) return;
    final c = _seen[peer];
    if (c != null && DateTime.now().millisecondsSinceEpoch - c.at < _seenTtl) return;
    try {
      final p = await client.fetchCurrentPresence(peer);
      _seen[peer] = (online: p.presence == PresenceType.online || p.currentlyActive == true, ts: p.lastActiveTimestamp?.millisecondsSinceEpoch, at: DateTime.now().millisecondsSinceEpoch);
      if (mounted) setState(() {});
    } catch (_) {} // not every server has presence on
  }

  String _subtitle() {
    final typing = [for (final u in room.typingUsers) if (u.id != me()) u.calcDisplayname()];
    if (typing.isNotEmpty) {
      return '${typing.length > 2 ? '${typing.take(2).join('، ')} و ${faNum(typing.length - 2)} نفر دیگر' : typing.join(' و ')} در حال نوشتن…';
    }
    if (room.membership == Membership.invite) return 'دعوت‌نامه'; // invites carry no member counts
    final peer = room.directChatMatrixID, s = peer == null ? null : _seen[peer];
    final text = s == null ? null : lastSeen(s.online, s.ts);
    return text ?? '${faNum(room.summary.mJoinedMemberCount ?? room.getParticipants([Membership.join]).length)} عضو';
  }

  // ---------- scrolling ----------

  void _onScroll() {
    final p = _sc.position;
    final bottom = p.pixels < 60, far = p.pixels > 300;
    if (p.extentAfter < 800) _older();
    final label = _topLabel();
    final f = (label: label ?? '', show: !bottom && label != null);
    if (bottom != _atBottom || far != _far || f != _floating) {
      _atBottom = bottom;
      _far = far;
      _floating = f;
      setState(() {});
    }
    if (!bottom) {
      _hide?.cancel();
      _hide = Timer(const Duration(milliseconds: 1200), () { if (mounted) setState(() => _floating = (label: _floating.label, show: false)); });
    }
  }

  Future<void> _older() async {
    final tl = _tl;
    if (tl == null || _busy || !tl.canRequestHistory) return;
    _busy = true;
    setState(() {});
    try {
      await tl.requestHistory();
    } catch (_) {}
    _busy = false;
    if (mounted) setState(() {});
  }

  /// Day of the topmost visible row; null while a day pill itself is on top (no double pill).
  String? _topLabel() {
    RenderSliverMultiBoxAdaptor? sliver;
    void visit(Element e) {
      if (sliver != null) return;
      final ro = e is RenderObjectElement ? e.renderObject : null;
      if (ro is RenderSliverMultiBoxAdaptor) { sliver = ro; return; }
      e.visitChildren(visit);
    }
    _listKey.currentContext?.visitChildElements(visit);
    if (sliver == null || !_sc.hasClients) return null;
    final top = _sc.position.pixels, bottom = top + _sc.position.viewportDimension;
    var idx = -1;
    for (var c = sliver!.firstChild; c != null; c = sliver!.childAfter(c)) {
      final pd = c.parentData as SliverMultiBoxAdaptorParentData, off = pd.layoutOffset ?? 0;
      if (off < bottom && off + c.size.height > top && pd.index != null && pd.index! > idx) idx = pd.index!;
    }
    if (idx < 0 || idx >= _rows.length || _rows[idx].type == 'day') return null;
    for (var j = idx; j < _rows.length; j++) {
      if (_rows[j].type == 'day') return _rows[j].label;
    }
    return null;
  }

  /// Scrolls a row into view: rows far away aren't built, so aim by an estimate first and settle once its key exists.
  Future<void> _reveal(String key, double alignment) async {
    for (var i = 0; i < 8 && mounted; i++) {
      final idx = _rows.indexWhere((r) => r.key == key);
      if (idx < 0 || !_sc.hasClients) return;
      final ctx = _keys[key]?.currentContext;
      if (ctx != null && ctx.mounted) return Scrollable.ensureVisible(ctx, alignment: alignment, duration: const Duration(milliseconds: 200));
      _sc.jumpTo((idx * 90.0).clamp(0, _sc.position.maxScrollExtent));
      await WidgetsBinding.instance.endOfFrame;
    }
  }

  Future<void> _jump(String eventId, {int pages = 10}) async {
    final tl = _tl;
    if (tl == null) return;
    for (var i = 0; i < pages && mounted && !tl.events.any((e) => e.eventId == eventId) && tl.canRequestHistory; i++) {
      while (_busy && mounted) { await Future<void>.delayed(const Duration(milliseconds: 100)); }
      await _older();
    }
    final ev = tl.events.where((e) => e.eventId == eventId).firstOrNull;
    if (!mounted) return;
    if (ev == null) return toast(context, 'این پیام خیلی قدیمی است');
    if (ev.relationshipType == RelationshipTypes.thread) return openThread(context, room, ev.relationshipEventId!); // replies aren't in this timeline
    setState(() => _flash = _rowId(ev));
    _flashT?.cancel();
    _flashT = Timer(const Duration(milliseconds: 1600), () { if (mounted) setState(() => _flash = null); });
    await WidgetsBinding.instance.endOfFrame;
    await _reveal(_rowId(ev), .5);
  }

  Future<void> _search() async {
    final tl = _tl;
    if (tl == null) return;
    final id = await Navigator.push<String>(context, MaterialPageRoute(builder: (_) => SearchPage(room: room, timeline: tl)));
    if (id != null && mounted) _jump(id, pages: 50);
  }

  void _toBottom() => _sc.animateTo(0, duration: const Duration(milliseconds: 250), curve: Curves.easeOut);

  /// Read receipt + fully-read marker for the newest event while we're looking at it.
  void _markRead(Timeline tl) {
    final ev = tl.events.firstOrNull;
    if (!_atBottom || !_resumed || ev == null || !ev.status.isSynced || ev.senderId == me() || ev.eventId == _sentRead) return;
    if (room.receiptState.global.latestOwnReceipt?.eventId == ev.eventId && room.fullyRead == ev.eventId) return;
    _sentRead = ev.eventId;
    tl.setReadMarker(eventId: ev.eventId).catchError((_) {});
  }

  GlobalKey _gk(String k) => _keys.putIfAbsent(k, GlobalKey.new);

  // ---------- build ----------

  @override
  Widget build(BuildContext context) {
    final t = context.tk;
    final tl = _tl;
    final invite = room.membership == Membership.invite;
    return PopScope(canPop: _sel == null, onPopInvokedWithResult: (didPop, _) { if (!didPop) setState(() => _sel = null); }, child: Scaffold(
      appBar: _sel != null && tl != null ? selectionBar(context, tl, [for (final e in tl.events.reversed) if (_sel!.contains(e.eventId)) e], () => setState(() => _sel = null)) : AppBar(
        titleSpacing: 0,
        title: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: () => toast(context, 'به‌زودی'),
          child: Row(children: [
            RoomAvatar(room, size: 40),
            const SizedBox(width: 10),
            Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
              Text(roomTitle(room), maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w600)),
              Text(_subtitle(), maxLines: 1, overflow: TextOverflow.ellipsis,
                style: TextStyle(fontSize: 13, fontWeight: FontWeight.normal, color: Colors.white.withValues(alpha: room.typingUsers.any((u) => u.id != me()) ? 1 : .75))),
            ])),
          ]),
        ),
        actions: [
          if (!invite) IconButton(icon: const Icon(Icons.search), tooltip: 'جستجو', onPressed: _search),
          if (!invite) PopupMenuButton<String>(
            onSelected: (_) => attempt(context, () => setMuted(room, !isMuted(room))).then((_) { if (mounted) setState(() {}); }),
            itemBuilder: (_) => [PopupMenuItem(value: 'mute', child: Text(isMuted(room) ? 'صدادار' : 'بی‌صدا'))],
          ),
        ],
      ),
      body: invite ? _Invite(room) : Column(children: [
        NowPlaying(room: room, onJump: _jump),
        PinnedBar(room: room, onJump: _jump),
        Expanded(child: Wallpaper(child: tl == null ? const SizedBox.shrink() : _timeline(tl, t))),
        if (tl != null) _bottomBar(tl),
      ]),
    ));
  }

  Widget _timeline(Timeline tl, Tokens t) {
    final evs = tl.events;
    final msgs = <Msg>[];
    final byId = <String, Event>{};
    final idx = <String, int>{};
    for (var i = evs.length - 1; i >= 0; i--) {
      final ev = evs[i];
      if (!_shown(ev)) continue;
      final id = _rowId(ev);
      byId[id] = ev;
      idx[id] = i;
      msgs.add(Msg(id, ev.senderId, ev.originServerTs.millisecondsSinceEpoch, isMessage(ev) ? 'msg' : 'notice'));
    }
    // oldest-first rows, reversed for the bottom-anchored list
    _rows = buildRows(msgs, null, _unreadAfter).reversed.toList();

    // anyone whose receipt sits on a later event read an earlier one too
    final rs = room.receiptState;
    var readIdx = 1 << 30;
    for (final r in [...rs.global.otherUsers.entries, ...?rs.mainThread?.otherUsers.entries]) {
      if (r.key == me()) continue;
      final i = evs.indexWhere((e) => e.eventId == r.value.eventId);
      if (i >= 0 && i < readIdx) readIdx = i;
    }
    Tick tickOf(Event ev) => ev.senderId != me() ? Tick.none
        : ev.status.isError ? Tick.failed : ev.status.isSending ? Tick.sending
        : (idx[_rowId(ev)] ?? 1 << 30) >= readIdx ? Tick.read : Tick.sent;

    if (_atBottom) _bottomId = evs.firstOrNull?.eventId;
    final bi = evs.indexWhere((e) => e.eventId == _bottomId);
    final unseen = bi <= 0 ? 0 : evs.take(bi).where((e) => isMessage(e) && e.senderId != me()).length;

    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      _markRead(tl);
      // fill the screen if there are only a handful of messages; one try per event count, so a failing request can't loop
      if (!_busy && msgs.length < 20 && _triedAt != evs.length && tl.canRequestHistory) {
        _triedAt = evs.length;
        _older();
      }
    });

    final actions = MsgActions(reply: (e) => setState(() => _mode = Mode.reply(e)), edit: (e) => setState(() => _mode = Mode.edit(e)), jump: _jump,
      thread: (e) => openThread(context, room, e.eventId),
      select: (e) => setState(() { // toggle; empty = leave selection mode
        final n = {...?_sel};
        if (!n.remove(e.eventId)) n.add(e.eventId);
        _sel = n.isEmpty ? null : n;
      }), selection: _sel);
    return Stack(children: [
      ListView.builder(
        key: _listKey, controller: _sc, reverse: true,
        padding: const EdgeInsets.symmetric(vertical: 8),
        itemCount: _rows.length + 1,
        findChildIndexCallback: (k) { final i = _rows.indexWhere((r) => ValueKey(r.key) == k); return i < 0 ? null : i; },
        itemBuilder: (context, i) {
          if (i == _rows.length) return SizedBox(height: 48, child: _busy ? const Center(child: SizedBox(width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2))) : null);
          final row = _rows[i];
          final ev = row.id == null ? null : byId[row.id!];
          final child = switch (row.type) {
            'day' => _Pill(row.label!),
            'notice' => ev == null ? const SizedBox.shrink() : _Pill(noticeText(ev) ?? ''),
            'unread' => Container(
              width: double.infinity, margin: const EdgeInsets.symmetric(vertical: 6), padding: const EdgeInsets.symmetric(vertical: 4),
              color: t.pill.withValues(alpha: .35), alignment: Alignment.center,
              child: const Text('پیام‌های خوانده‌نشده', style: TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.w600))),
            _ => ev == null ? const SizedBox.shrink() : ChatMessage(ev: ev, timeline: tl, first: row.first, last: row.last, tick: tickOf(ev), actions: actions, flash: _flash == row.key),
          };
          return KeyedSubtree(key: ValueKey(row.key), child: KeyedSubtree(key: _gk(row.key), child: child));
        },
      ),
      Positioned(top: 8, left: 0, right: 0, child: IgnorePointer(child: AnimatedOpacity(
        opacity: _floating.show ? 1 : 0, duration: const Duration(milliseconds: 200),
        child: Center(child: _Pill(_floating.label)),
      ))),
      PositionedDirectional(end: 12, bottom: 12, child: AnimatedScale(
        scale: _far ? 1 : 0, duration: const Duration(milliseconds: 150),
        child: Stack(clipBehavior: Clip.none, children: [
          FloatingActionButton.small(heroTag: null, backgroundColor: t.panel, foregroundColor: t.muted, onPressed: _toBottom, child: const Icon(Icons.keyboard_arrow_down, size: 28)),
          if (unseen > 0) PositionedDirectional(top: -6, start: 0, end: 0, child: Center(child: Container(
            constraints: const BoxConstraints(minWidth: 20), height: 20, padding: const EdgeInsets.symmetric(horizontal: 6), alignment: Alignment.center,
            decoration: BoxDecoration(color: t.accent, borderRadius: BorderRadius.circular(10)),
            child: Text(faNum(unseen), style: const TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.w600)),
          ))),
        ]),
      )),
    ]);
  }

  Widget _bottomBar(Timeline tl) {
    final t = context.tk;
    final tomb = room.getState(EventTypes.RoomTombstone);
    Widget bar(List<Widget> children) => Material(color: t.panel, child: SafeArea(top: false, child: Padding(
      padding: const EdgeInsets.all(12), child: Column(mainAxisSize: MainAxisSize.min, children: children))));
    if (tomb != null) {
      final to = tomb.content.tryGet<String>('replacement_room');
      return bar([
        const Text('این گروه ارتقا یافته و دیگر پیامی در آن فرستاده نمی‌شود', textAlign: TextAlign.center),
        if (to != null) TextButton(onPressed: () => attempt(context, () => _goNew(tomb.senderId, to)), child: const Text('رفتن به گروه جدید')),
      ]);
    }
    if (!room.canSendDefaultMessages) return bar([Text('فقط مدیران می‌توانند در این گروه پیام بفرستند', style: TextStyle(color: t.muted))]);
    return Composer(room: room, timeline: tl, mode: _mode, onMode: (m) => setState(() => _mode = m), onPoll: () => showPollForm(context, room));
  }

  Future<void> _goNew(String upgrader, String to) async {
    // the upgrader's server is in the new room for sure
    if (client.getRoomById(to)?.membership != Membership.join) {
      await client.joinRoom(to, via: [upgrader.replaceFirst(RegExp('^[^:]*:'), '')]);
      await client.waitForRoomInSync(to, join: true);
    }
    final next = client.getRoomById(to);
    if (next != null && mounted) await Navigator.pushReplacement(context, MaterialPageRoute<void>(builder: (_) => ChatPage(room: next)));
  }
}

class _Pill extends StatelessWidget {
  final String text;
  const _Pill(this.text);
  @override
  Widget build(BuildContext context) => Center(child: Container(
    margin: const EdgeInsets.symmetric(vertical: 4, horizontal: 24),
    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 3),
    decoration: BoxDecoration(color: context.tk.pill, borderRadius: BorderRadius.circular(14)),
    child: Text(text, textAlign: TextAlign.center, style: const TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.w500)),
  ));
}

class _Invite extends StatefulWidget {
  final Room room;
  const _Invite(this.room);
  @override
  State<_Invite> createState() => _InviteState();
}

class _InviteState extends State<_Invite> {
  bool _busy = false;
  Future<void> _act(Future<void> Function() f) async {
    setState(() => _busy = true);
    await attempt(context, f);
    if (mounted) setState(() => _busy = false);
  }

  @override
  Widget build(BuildContext context) {
    final room = widget.room;
    final inviter = room.getState(EventTypes.RoomMember, me())?.senderId;
    return Center(child: Padding(padding: const EdgeInsets.all(24), child: Column(mainAxisSize: MainAxisSize.min, children: [
      RoomAvatar(room, size: 88),
      const SizedBox(height: 12),
      Text(roomTitle(room), style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w600)),
      const SizedBox(height: 6),
      Text(room.isDirectChat && inviter != null ? '${bdi(room.unsafeGetUserFromMemoryOrFallback(inviter).calcDisplayname())} شما را دعوت کرد' : 'به این گفتگو دعوت شده‌اید'),
      const SizedBox(height: 16),
      Row(mainAxisSize: MainAxisSize.min, children: [
        OutlinedButton(onPressed: _busy ? null : () => _act(() async { await room.leave(); if (context.mounted) Navigator.pop(context); }), child: const Text('رد کردن')),
        const SizedBox(width: 12),
        FilledButton(onPressed: _busy ? null : () => _act(() => room.join(waitForSync: true)), child: const Text('پیوستن')),
      ]),
    ])));
  }
}
