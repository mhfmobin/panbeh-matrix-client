import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:html/dom.dart' as dom;
import 'package:html/parser.dart' as html;
import 'package:matrix/matrix.dart';
import 'package:url_launcher/url_launcher.dart';

import '../logic.dart';
import '../theme.dart';
import '../uri.dart';
import 'common.dart';
import 'encryption.dart';
import 'voice.dart';
import 'link_preview.dart';
import 'media.dart';
import 'emoji.dart' show pickEmoji;
import 'forward.dart';
import 'pinned.dart';
import 'poll.dart';
import 'reactions.dart';
import 'seen_by.dart';
import 'thread.dart';
import '../open_target.dart';

enum Tick { none, sending, sent, read, failed }

class MsgActions {
  final void Function(Event ev) reply, edit;
  final void Function(String id) jump;
  final void Function(Event ev)? thread; // null inside a thread itself
  final void Function(Event ev)? select; // toggles multi-select membership
  final Set<String>? selection; // event ids; non-null = selecting
  const MsgActions({required this.reply, required this.edit, required this.jump, this.thread, this.select, this.selection});
}

const editable = ['m.text', 'm.emote', 'm.notice'];
const _swipeAt = 60.0, _swipeMax = 80.0;

/// Message events worth a bubble (blocked senders are hidden too: some servers ignore m.ignored_user_list).
bool isMessage(Event ev) =>
    (ev.type == EventTypes.Message || ev.type == EventTypes.Sticker || ev.type == EventTypes.Encrypted || pollStart.contains(ev.type)) &&
    !ev.redacted && ev.relationshipType != RelationshipTypes.edit && !ev.room.client.ignoredUsers.contains(ev.senderId) &&
    ev.content['msgtype'] != verifyRequest;

/// Text a copy should give: body without the reply fallback; empty for things that have no text (yet).
String copyTextOf(Event ev) {
  if (ev.type == EventTypes.Encrypted || pollStart.contains(ev.type)) return '';
  final c = ev.content;
  if (c['msgtype'] == 'm.text' || c['msgtype'] == 'm.notice' || c['msgtype'] == 'm.emote') return stripReplyFallback('${c['body'] ?? ''}');
  return '';
}

Future<void> openLink(BuildContext context, String href) async {
  final target = parseMatrixLink(href);
  if (target != null) return openTarget(context, target);
  if (href.startsWith('matrix:')) return toast(context, 'باز کردن پیوند ممکن نشد');
  final ok = await launchUrl(Uri.parse(linkHref(href)), mode: LaunchMode.externalApplication).catchError((_) => false);
  if (!ok && context.mounted) toast(context, 'باز کردن پیوند ممکن نشد');
}

class ChatMessage extends StatefulWidget {
  final Event ev;
  final Timeline timeline;
  final bool first, last, flash;
  final Tick tick;
  final MsgActions actions;
  const ChatMessage({super.key, required this.ev, required this.timeline, required this.first, required this.last, required this.tick, required this.actions, this.flash = false});
  @override
  State<ChatMessage> createState() => _ChatMessageState();
}

class _ChatMessageState extends State<ChatMessage> {
  double _dx = 0;
  bool _dragging = false, _crossed = false;
  final _recs = <TapGestureRecognizer>[];

  @override
  void dispose() {
    for (final r in _recs) {
      r.dispose();
    }
    super.dispose();
  }

  Event get ev => widget.ev;
  bool get mine => ev.senderId == me();
  bool get live => ev.status.isSynced && !ev.status.isError;

  bool get selecting => widget.actions.selection != null;

  void _end() {
    final go = _dx <= -_swipeAt;
    setState(() { _dragging = false; _dx = 0; _crossed = false; });
    if (go && live) widget.actions.reply(ev);
  }

  void _menu(Offset at) {
    if (!live) return;
    final disp = ev.getDisplayEvent(widget.timeline);
    final text = copyTextOf(disp), pinned = pinnedIds(ev.room).contains(ev.eventId);
    HapticFeedback.mediumImpact();
    showMsgMenu(context, at, [
      MenuItem(Icons.reply, 'پاسخ', () => widget.actions.reply(ev)),
      if (widget.actions.thread != null) MenuItem(Icons.forum_outlined, 'پاسخ در رشته', () => widget.actions.thread!(ev)),
      if (text.isNotEmpty) MenuItem(Icons.copy_outlined, 'کپی', () => copyText(context, text)),
      if (!pollStart.contains(ev.type)) MenuItem(Icons.forward, 'هدایت', () => showForward(context, [disp])),
      if (canPin(ev.room)) MenuItem(pinned ? Icons.push_pin : Icons.push_pin_outlined, pinned ? 'برداشتن سنجاق' : 'سنجاق', () => attempt(context, () => togglePin(ev.room, ev.eventId))),
      if (reactionsOf(ev, widget.timeline).isNotEmpty) MenuItem(Icons.emoji_emotions_outlined, 'واکنش‌ها', () => showReactors(context, ev, widget.timeline)),
      if (mine) MenuItem(Icons.done_all, 'دیده شده', () => showSeenBy(context, ev, widget.timeline)),
      ...mediaMenu(context, ev, disp),
      if (mine && editable.contains(disp.content['msgtype'])) MenuItem(Icons.edit_outlined, 'ویرایش', () => widget.actions.edit(ev)),
      if (widget.actions.select != null) MenuItem(Icons.check_circle_outline, 'انتخاب', () => widget.actions.select!(ev)),
      if (ev.canRedact) MenuItem(Icons.delete_outline, 'حذف', () async {
        if (await confirm(context, 'این پیام برای همه حذف شود؟', ok: 'حذف', danger: true) && mounted) {
          await attempt(context, () async { await ev.room.redactEvent(ev.eventId); });
        }
      }, danger: true),
    ], header: (close) => QuickReactions(
      onPick: (k) { close(); toggleReaction(context, ev, widget.timeline, k); },
      onMore: () async {
        close();
        final k = await pickEmoji(context);
        if (k != null && mounted) toggleReaction(context, ev, widget.timeline, k);
      },
    ));
  }

  void _failed() => showModalBottomSheet<void>(context: context, builder: (c) => SafeArea(child: Column(mainAxisSize: MainAxisSize.min, children: [
    ListTile(leading: const Icon(Icons.send), title: const Text('ارسال دوباره'), onTap: () { Navigator.pop(c); attempt(context, () async { await ev.sendAgain(); }); }),
    ListTile(leading: const Icon(Icons.delete_outline, color: Color(0xffe53935)), title: const Text('حذف', style: TextStyle(color: Color(0xffe53935))),
      onTap: () { Navigator.pop(c); attempt(context, ev.cancelSend); }),
  ])));

  @override
  Widget build(BuildContext context) {
    final t = context.tk;
    final group = isGroupChat(ev.room), showAvatar = !mine && group;
    // corner radii exactly like styles.css `.msg.theirs:not(.first)` etc.
    const r = 16.0, tight = 6.0, tail = 4.0;
    final inner = Radius.circular(tight);
    final bubble = _Bubble(this, widget.first, widget.last);
    final radius = mine
        ? BorderRadius.only(topLeft: const Radius.circular(r), bottomLeft: const Radius.circular(r),
            topRight: widget.first ? const Radius.circular(r) : inner, bottomRight: widget.last ? const Radius.circular(tail) : inner)
        : BorderRadius.only(topRight: const Radius.circular(r), bottomRight: const Radius.circular(r),
            topLeft: widget.first ? const Radius.circular(r) : inner, bottomLeft: widget.last ? const Radius.circular(tail) : inner);
    final user = ev.senderFromMemoryOrFallback;
    return Directionality(
      textDirection: TextDirection.ltr,
      child: GestureDetector(
        behavior: HitTestBehavior.translucent,
        onLongPressStart: (d) => _menu(d.globalPosition),
        onTap: selecting && live ? () => widget.actions.select!(ev) : null, // taps only toggle while selecting
        onHorizontalDragStart: (_) => setState(() => _dragging = true),
        onHorizontalDragUpdate: (d) {
          if (!live || selecting) return;
          // swipe LEFT to reply (Telegram's direction); resistance past the max
          final raw = (-_dx + -d.delta.dx).clamp(0.0, double.infinity);
          setState(() => _dx = -(raw <= _swipeMax ? raw : _swipeMax + (raw - _swipeMax) * .25).clamp(0.0, _swipeMax + 30));
          if ((-_dx >= _swipeAt) != _crossed) {
            _crossed = !_crossed;
            if (_crossed) HapticFeedback.lightImpact();
          }
        },
        onHorizontalDragEnd: (_) => _end(),
        onHorizontalDragCancel: _end,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 300),
          color: widget.flash || (widget.actions.selection?.contains(ev.eventId) ?? false) ? t.accent.withValues(alpha: .25) : Colors.transparent,
          padding: EdgeInsets.fromLTRB(8, widget.first ? 5 : 1.5, 8, widget.last ? 3 : 1.5),
          child: Stack(children: [
            if (live) Positioned.fill(child: Align(alignment: Alignment.centerRight, child: Opacity(
              opacity: (-_dx / _swipeAt).clamp(0.0, 1.0),
              child: Container(width: 30, height: 30, decoration: BoxDecoration(color: t.pill, shape: BoxShape.circle), child: const Icon(Icons.reply, size: 18, color: Colors.white))))),
            AnimatedContainer(
              duration: _dragging ? Duration.zero : const Duration(milliseconds: 200),
              transform: Matrix4.translationValues(_dx, 0, 0),
              child: AbsorbPointer(absorbing: selecting, child: Row(
                mainAxisAlignment: mine ? MainAxisAlignment.end : MainAxisAlignment.start,
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  if (selecting) Padding(padding: const EdgeInsets.only(right: 6, bottom: 4), child: _SelectMark(widget.actions.selection!.contains(ev.eventId))),
                  if (showAvatar) SizedBox(width: 38, child: widget.last ? Avatar(mxc: user.avatarUrl, name: senderName(ev), id: ev.senderId, size: 34) : null),
                  Flexible(child: ConstrainedBox(
                    constraints: BoxConstraints(maxWidth: MediaQuery.sizeOf(context).width * .8),
                    child: Column(crossAxisAlignment: mine ? CrossAxisAlignment.end : CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
                      DecoratedBox(decoration: BoxDecoration(color: mine ? t.bubbleOut : t.bubbleIn, borderRadius: radius,
                        boxShadow: const [BoxShadow(color: Color(0x14000000), blurRadius: 1, offset: Offset(0, 1))]), child: bubble),
                      ReactionChips(ev, widget.timeline),
                    ]),
                  )),
                ],
              )),
            ),
          ]),
        ),
      ),
    );
  }
}

class _SelectMark extends StatelessWidget {
  final bool on;
  const _SelectMark(this.on);
  @override
  Widget build(BuildContext context) => Container(width: 22, height: 22,
    decoration: BoxDecoration(shape: BoxShape.circle, color: on ? context.tk.accent : Colors.transparent, border: Border.all(color: on ? context.tk.accent : context.tk.muted, width: 1.5)),
    child: on ? const Icon(Icons.check, size: 15, color: Colors.white) : null);
}

class _Bubble extends StatelessWidget {
  final _ChatMessageState s;
  final bool first, last;
  const _Bubble(this.s, this.first, this.last);

  @override
  Widget build(BuildContext context) {
    final ev = s.ev, t = context.tk, mine = s.mine;
    final disp = ev.getDisplayEvent(s.widget.timeline);
    final edited = !identical(disp, ev);
    final c = disp.content;
    final fg = mine ? t.outText : t.text, metaColor = mine ? t.outMeta : t.muted;
    final base = TextStyle(color: fg, fontSize: 15.5, height: 1.35, fontFamily: 'Vazirmatn');

    String text;
    var muted = false, jumbo = false;
    List<Object>? blocks;
    final emote = c['msgtype'] == 'm.emote' ? '* ${senderName(ev)} ' : '';
    if (ev.type == EventTypes.Encrypted) {
      muted = true;
      text = ev.messageType == MessageTypes.BadEncrypted ? '🔒 فعلاً رمزگشایی نشد. رمزنگاری را در تنظیمات باز کنید.' : 'در حال رمزگشایی…';
    } else if (ev.type == EventTypes.Message && (c['msgtype'] == 'm.text' || c['msgtype'] == 'm.notice' || c['msgtype'] == 'm.emote')) {
      text = emote + stripReplyFallback('${c['body'] ?? ''}');
      // ignore: valid_regexps (the analyzer lacks Extended_Pictographic; the VM has it)
      jumbo = RegExp(r'^(?:\p{Extended_Pictographic}\uFE0F?){1,3}$', unicode: true).hasMatch(text);
      final fb = c['formatted_body'];
      if (c['format'] == 'org.matrix.custom.html' && fb is String) {
        blocks = _htmlBlocks(context, fb.replaceAll(RegExp(r'<mx-reply>[\s\S]*?</mx-reply>', caseSensitive: false), ''), ev.room, base, mine, s._recs, emote);
      }
    } else {
      text = previewText(disp);
    }
    // photos, videos, gifs, files, locations: the picture goes in the bubble, the caption is its text
    final mc = ev.type == EventTypes.Encrypted ? null : mediaContent(context, s.widget.timeline, ev, disp, s.widget.actions);
    if (mc != null) text = mc.caption;
    final inset = mc?.visual == true, overlay = inset && mc!.caption.isEmpty; // overlay: no caption, so the time sits on the picture
    Widget pad(Widget w) => inset ? Padding(padding: const EdgeInsets.fromLTRB(7, 3, 7, 3), child: w) : w;
    final poll = pollStart.contains(ev.type);
    final dir = textDir(text) == 'rtl' ? TextDirection.rtl : TextDirection.ltr;
    // the time/ticks sit on the side the text leaves free
    final audio = ev.type == EventTypes.Message && c['msgtype'] == 'm.audio';
    final metaLeft = dir == TextDirection.rtl && !audio;

    final mcol = overlay ? Colors.white : metaColor;
    final meta = Row(mainAxisSize: MainAxisSize.min, children: [
      if (pinnedIds(ev.room).contains(ev.eventId)) Padding(padding: const EdgeInsetsDirectional.only(end: 3), child: Icon(Icons.push_pin, size: 11, color: mcol)),
      if (edited) Text('ویرایش‌شده  ', style: TextStyle(fontSize: 11, color: mcol)),
      Text(clock(ev.originServerTs.millisecondsSinceEpoch), style: TextStyle(fontSize: 11, color: mcol)),
      if (mine) ...[const SizedBox(width: 3), _TickIcon(s.widget.tick, mcol, t.readTick, s._failed)],
    ]);
    final metaW = (mine ? 62.0 : 40.0) + (edited ? 52 : 0) + (pinnedIds(ev.room).contains(ev.eventId) ? 14 : 0);

    final trailing = WidgetSpan(child: SizedBox(width: metaW, height: 14));
    final body = overlay || poll ? <Widget>[] : blocks != null
        ? [for (var i = 0; i < blocks.length; i++)
            blocks[i] is List<InlineSpan>
                ? Text.rich(TextSpan(children: [...(blocks[i] as List<InlineSpan>), if (i == blocks.length - 1) trailing]), textDirection: dir, style: base)
                : blocks[i] as Widget,
          ]
        : [Text.rich(TextSpan(children: [
            ..._linkify(context, text, base.copyWith(fontSize: jumbo ? 44 : null, color: muted ? metaColor : null), mine ? fg : t.accent, s._recs),
            trailing,
          ]), textDirection: dir)];
    final lastIsBlock = blocks != null && blocks.isNotEmpty && blocks.last is! List<InlineSpan>;

    final replyId = ev.inReplyToEventId(includingFallback: false);
    final locked = ev.type == EventTypes.Encrypted && ev.messageType == MessageTypes.BadEncrypted && recovery.value != Recovery.ok;
    final bubble = Padding(
      padding: inset ? const EdgeInsets.all(3) : const EdgeInsets.fromLTRB(10, 6, 10, 6),
      child: Stack(children: [
        ConstrainedBox(constraints: BoxConstraints(maxWidth: inset ? mc!.width : double.infinity), child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
          if (!mine && isGroupChat(ev.room) && first)
            pad(Padding(padding: const EdgeInsets.only(bottom: 2), child: Text(senderName(ev), textDirection: TextDirection.ltr,
              style: TextStyle(color: colorFor(ev.senderId), fontWeight: FontWeight.w600, fontSize: 13.5)))),
          if (c['app.panbeh.forwarded'] is Map) pad(_Forwarded(c['app.panbeh.forwarded'] as Map, ev.room, t.accent, mine)),
          if (replyId != null) pad(_Quote(s.widget.timeline, replyId, mine, s.widget.actions.jump)),
          if (mc != null) mc.widget,
          if (poll) ...[PollBody(ev, s.widget.timeline, mine: mine), const SizedBox(height: 16)],
          if (audio) VoiceBubble(ev: ev, timeline: s.widget.timeline, mine: mine, metaW: metaW) else ...body.map(pad),
          if (ev.type == EventTypes.Message) LinkPreview(disp),
          if (s.widget.actions.thread != null) ThreadSummary(root: ev, timeline: s.widget.timeline, mine: mine, onTap: () => s.widget.actions.thread!(ev)),
          if (lastIsBlock) const SizedBox(height: 16),
        ])),
        Positioned(bottom: overlay ? 6 : inset ? 3 : 0, left: overlay ? null : metaLeft ? (inset ? 7 : 0) : null, right: overlay ? 6 : metaLeft ? null : (inset ? 7 : 0),
          child: Directionality(textDirection: TextDirection.rtl, child: overlay
            ? Container(padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1), decoration: BoxDecoration(color: Colors.black45, borderRadius: BorderRadius.circular(10)), child: meta) : meta)),
      ]),
    );
    // not decryptable yet: a tap goes to where it gets fixed
    return locked ? GestureDetector(onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => const EncryptionPage())), child: bubble) : bubble;
  }
}

class _Forwarded extends StatelessWidget {
  final Map f;
  final Room room;
  final Color accent;
  final bool mine;
  const _Forwarded(this.f, this.room, this.accent, this.mine);
  @override
  Widget build(BuildContext context) {
    final id = '${f['sender']}', known = room.getState(EventTypes.RoomMember, id) != null;
    final name = known ? room.unsafeGetUserFromMemoryOrFallback(id).calcDisplayname() : '${f['name'] ?? id}';
    return Padding(padding: const EdgeInsets.only(bottom: 2), child: Text.rich(TextSpan(children: [
      TextSpan(text: 'هدایت‌شده از ', style: TextStyle(color: mine ? context.tk.outMeta : context.tk.muted)),
      TextSpan(text: bdi(name), style: TextStyle(color: mine ? context.tk.outText : accent, fontWeight: FontWeight.w600)),
    ]), textDirection: TextDirection.rtl, style: const TextStyle(fontSize: 13.5)));
  }
}

class _TickIcon extends StatelessWidget {
  final Tick tick;
  final Color color, read;
  final VoidCallback onFailed;
  const _TickIcon(this.tick, this.color, this.read, this.onFailed);
  @override
  Widget build(BuildContext context) => switch (tick) {
    Tick.none => const SizedBox.shrink(),
    Tick.sending => Icon(Icons.schedule, size: 13, color: color),
    Tick.sent => Icon(Icons.done, size: 15, color: color),
    Tick.read => Icon(Icons.done_all, size: 15, color: read),
    Tick.failed => GestureDetector(onTap: onFailed, child: Container(width: 16, height: 16, alignment: Alignment.center,
      decoration: const BoxDecoration(color: Color(0xffe53935), shape: BoxShape.circle),
      child: const Text('!', style: TextStyle(color: Colors.white, fontSize: 11, fontWeight: FontWeight.bold, height: 1)))),
  };
}

class _Quote extends StatelessWidget {
  final Timeline timeline;
  final String id;
  final bool mine;
  final void Function(String) jump;
  const _Quote(this.timeline, this.id, this.mine, this.jump);
  @override
  Widget build(BuildContext context) {
    final t = context.tk;
    final ev = timeline.events.where((e) => e.eventId == id).firstOrNull;
    final color = ev != null ? colorFor(ev.senderId) : t.accent;
    return GestureDetector(
      onTap: ev == null ? null : () => jump(id),
      child: Container(
        margin: const EdgeInsets.only(bottom: 4),
        padding: const EdgeInsetsDirectional.fromSTEB(8, 2, 8, 2),
        decoration: BoxDecoration(color: color.withValues(alpha: .12), border: Border(left: BorderSide(color: color, width: 3)), borderRadius: const BorderRadius.horizontal(right: Radius.circular(4))),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
          Text(ev != null ? senderName(ev) : 'پاسخ', maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(color: color, fontWeight: FontWeight.w600, fontSize: 13)),
          Text(ev != null ? previewText(ev.getDisplayEvent(timeline)) : 'پیام اصلی بارگذاری نشده', maxLines: 1, overflow: TextOverflow.ellipsis,
            textDirection: TextDirection.rtl, style: TextStyle(color: mine ? t.outText : t.text, fontSize: 13)),
        ]),
      ),
    );
  }
}

// ---------- menu ----------

class MenuItem {
  final IconData icon;
  final String label;
  final VoidCallback run;
  final bool danger;
  const MenuItem(this.icon, this.label, this.run, {this.danger = false});
}

/// Telegram-style popup over a dimmed background, near the press point.
void showMsgMenu(BuildContext context, Offset at, List<MenuItem> items, {Widget Function(VoidCallback close)? header}) {
  final size = MediaQuery.sizeOf(context);
  final w = header == null ? 200.0 : 300.0;
  final h = items.length * 48.0 + 16 + (header == null ? 0 : 52);
  showGeneralDialog<void>(
    context: context, barrierDismissible: true, barrierLabel: 'بستن', barrierColor: Colors.black45,
    transitionDuration: const Duration(milliseconds: 150),
    transitionBuilder: (c, a, _, child) => FadeTransition(opacity: a, child: ScaleTransition(scale: Tween(begin: .9, end: 1.0).animate(a), child: child)),
    pageBuilder: (c, _, _) {
      final t = c.tk;
      return Stack(children: [Positioned(
        left: (at.dx - w / 2).clamp(8.0, size.width - w - 8), top: at.dy.clamp(8.0, size.height - h - 8),
        child: Material(color: t.panel, elevation: 8, borderRadius: BorderRadius.circular(14), clipBehavior: Clip.antiAlias, child: SizedBox(width: w, child: Column(mainAxisSize: MainAxisSize.min, children: [
          if (header != null) ...[header(() => Navigator.pop(c)), Divider(height: 1, color: t.border)],
          const SizedBox(height: 8),
          for (final it in items) InkWell(
            onTap: () { Navigator.pop(c); it.run(); },
            child: Padding(padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12), child: Row(children: [
              Icon(it.icon, size: 22, color: it.danger ? const Color(0xffe53935) : t.muted),
              const SizedBox(width: 16),
              Text(it.label, style: TextStyle(fontSize: 15.5, color: it.danger ? const Color(0xffe53935) : t.text)),
            ])),
          ),
          const SizedBox(height: 8),
        ]))),
      )]);
    },
  );
}

// ---------- text ----------

/// Plain text with links (logic.linkSrc) made tappable.
List<InlineSpan> _linkify(BuildContext context, String text, TextStyle style, Color link, List<TapGestureRecognizer> recs) {
  final out = <InlineSpan>[];
  var at = 0;
  void plain(String s) { if (s.isNotEmpty) out.add(TextSpan(text: s, style: style)); }
  for (final m in RegExp(linkSrc + r'''|matrix:[^\s<]+[^\s<.,;:!?)"']''', caseSensitive: false).allMatches(text)) {
    final u = m[0]!;
    if (u.startsWith('matrix:') && parseMatrixLink(u) == null) continue;
    plain(text.substring(at, m.start));
    at = m.end;
    final r = TapGestureRecognizer()..onTap = () => openLink(context, u);
    recs.add(r);
    out.add(TextSpan(text: u, recognizer: r, style: style.copyWith(color: link, decoration: TextDecoration.underline, decorationColor: link)));
  }
  plain(text.substring(at));
  return out;
}

const _mono = TextStyle(fontFamily: 'monospace', fontSize: 14);

/// formatted_body → blocks: `List<InlineSpan>` paragraphs, or ready widgets for blockquote/pre.
/// A small hand-written converter: bold/italic/strike/code/pre/blockquote/links/mention pills/spoilers.
List<Object> _htmlBlocks(BuildContext context, String src, Room room, TextStyle base, bool mine, List<TapGestureRecognizer> recs, String emote) {
  final t = context.tk;
  final blocks = <Object>[];
  var cur = <InlineSpan>[if (emote.isNotEmpty) TextSpan(text: emote, style: base)];
  void flush() {
    if (cur.isNotEmpty) blocks.add(cur);
    cur = [];
  }

  late List<InlineSpan> Function(Iterable<dom.Node>, TextStyle) inline;
  late void Function(dom.Element, TextStyle) kids;
  void walk(dom.Node n, TextStyle st) {
    if (n is dom.Text) {
      cur.add(TextSpan(text: n.text, style: st));
      return;
    }
    if (n is! dom.Element) return;
    switch (n.localName) {
      case 'br': cur.add(const TextSpan(text: '\n'));
      case 'strong' || 'b': kids(n, st.copyWith(fontWeight: FontWeight.w700));
      case 'em' || 'i': kids(n, st.copyWith(fontStyle: FontStyle.italic));
      case 'del' || 's' || 'strike': kids(n, st.copyWith(decoration: TextDecoration.lineThrough));
      case 'u': kids(n, st.copyWith(decoration: TextDecoration.underline));
      case 'code': kids(n, st.merge(_mono).copyWith(backgroundColor: st.color!.withValues(alpha: .1)));
      case 'pre':
        flush();
        blocks.add(Container(
          width: double.infinity, margin: const EdgeInsets.symmetric(vertical: 3), padding: const EdgeInsets.all(8),
          decoration: BoxDecoration(color: base.color!.withValues(alpha: .08), borderRadius: BorderRadius.circular(6)),
          child: Text(n.text.replaceFirst(RegExp(r'\n$'), ''), textDirection: TextDirection.ltr, style: base.merge(_mono)),
        ));
      case 'blockquote':
        flush();
        final spans = inline(n.nodes, base.copyWith(fontStyle: FontStyle.italic));
        blocks.add(Container(
          margin: const EdgeInsets.symmetric(vertical: 3), padding: const EdgeInsetsDirectional.only(start: 8),
          decoration: BoxDecoration(border: BorderDirectional(start: BorderSide(color: mine ? t.outMeta : t.accent, width: 3))),
          child: Text.rich(TextSpan(children: spans)),
        ));
      case 'a':
        final href = n.attributes['href'] ?? '';
        final target = parseMatrixLink(href);
        if (target != null && target.kind == 'user' && target.eventId == null) {
          final name = room.unsafeGetUserFromMemoryOrFallback(target.id).calcDisplayname();
          final col = mine ? st.color! : colorFor(target.id);
          final r = TapGestureRecognizer()..onTap = () => openTarget(context, target);
          recs.add(r);
          cur.add(TextSpan(text: name, recognizer: r, style: st.copyWith(color: col, fontWeight: FontWeight.w600, backgroundColor: col.withValues(alpha: .12))));
        } else {
          final r = TapGestureRecognizer()..onTap = () => openLink(context, href);
          recs.add(r);
          final col = mine ? st.color! : t.accent;
          cur.add(TextSpan(recognizer: r, children: inline(n.nodes, st.copyWith(color: col, decoration: TextDecoration.underline, decorationColor: col))));
        }
      case 'span' when n.attributes.containsKey('data-mx-spoiler'):
        cur.add(WidgetSpan(alignment: PlaceholderAlignment.baseline, baseline: TextBaseline.alphabetic,
          child: _Spoiler(TextSpan(children: inline(n.nodes, st)), st.color!)));
      case 'p' || 'div' || 'h1' || 'h2' || 'h3' || 'h4' || 'h5' || 'h6' || 'ul' || 'ol' || 'li':
        flush();
        final head = RegExp(r'^h\d$').hasMatch(n.localName!);
        if (n.localName == 'li') cur.add(TextSpan(text: n.parent?.localName == 'ol' ? '${faDigits('${n.parent!.children.indexOf(n) + 1}')}. ' : '• ', style: st));
        kids(n, head ? st.copyWith(fontWeight: FontWeight.w700) : st);
        flush();
      default: kids(n, st); // img and unknown tags: just their text
    }
  }

  kids = (n, st) {
    for (final x in n.nodes) {
      walk(x, st);
    }
  };
  inline = (nodes, st) {
    final saved = cur;
    cur = [];
    for (final n in nodes) {
      walk(n, st);
    }
    final r = cur;
    cur = saved;
    return r;
  };
  for (final x in html.parseFragment(src).nodes) {
    walk(x, base);
  }
  flush();
  // a trailing <br> only adds an empty line
  if (blocks.isNotEmpty && blocks.last is List<InlineSpan>) {
    final l = blocks.last as List<InlineSpan>;
    if (l.isNotEmpty && l.last is TextSpan && (l.last as TextSpan).text == '\n') l.removeLast();
  }
  return blocks;
}

/// Tap to reveal.
class _Spoiler extends StatefulWidget {
  final TextSpan span;
  final Color color;
  const _Spoiler(this.span, this.color);
  @override
  State<_Spoiler> createState() => _SpoilerState();
}

class _SpoilerState extends State<_Spoiler> {
  bool shown = false;
  @override
  Widget build(BuildContext context) => GestureDetector(
    onTap: () => setState(() => shown = true),
    child: Container(
      decoration: BoxDecoration(color: shown ? widget.color.withValues(alpha: .1) : widget.color.withValues(alpha: .5), borderRadius: BorderRadius.circular(4)),
      child: shown ? Text.rich(widget.span) : Opacity(opacity: 0, child: Text.rich(widget.span)),
    ),
  );
}
