import 'package:flutter/material.dart';
import 'package:matrix/matrix.dart';

import '../logic.dart';
import '../prefs.dart';
import '../theme.dart';
import 'common.dart';


class Mode {
  final bool edit;
  final Event ev;
  const Mode.reply(this.ev) : edit = false;
  const Mode.edit(this.ev) : edit = true;
}

typedef _Mention = ({String name, String id});
final _drafts = <String, String>{}; // per room, in memory

class Composer extends StatefulWidget {
  final Room room;
  final Timeline timeline;
  final Mode? mode;
  final ValueChanged<Mode?> onMode;
  const Composer({super.key, required this.room, required this.timeline, required this.mode, required this.onMode});
  @override
  State<Composer> createState() => _ComposerState();
}

class _ComposerState extends State<Composer> {
  late final _c = TextEditingController(text: _drafts[widget.room.id] ?? '');
  final _f = FocusNode();
  final _mentions = <_Mention>[]; // people picked from the @ list; turned into links on send
  String _stash = ''; // the draft, put aside while editing a message
  int _typingAt = 0;
  static const _closedAt = -1; // ponytail: no Esc on a phone, so the @ list never needs hiding

  Room get room => widget.room;
  Mode? get mode => widget.mode;

  @override
  void initState() {
    super.initState();
    _c.addListener(_onText);
    if (mode != null) _enter(null);
  }

  @override
  void didUpdateWidget(Composer old) {
    super.didUpdateWidget(old);
    if (old.mode != mode) _enter(old.mode);
  }

  @override
  void dispose() {
    if (_typingAt != 0) room.setTyping(false).catchError((_) {});
    _c.dispose();
    _f.dispose();
    super.dispose();
  }

  void _enter(Mode? was) {
    final m = mode;
    if (m != null && m.edit) {
      if (was == null || !was.edit) _stash = _c.text;
      final disp = m.ev.getDisplayEvent(widget.timeline);
      _c.text = stripReplyFallback('${disp.content['body'] ?? ''}');
      // keep the original's mentions as links in the edit
      _mentions
        ..clear()
        ..addAll([for (final id in (disp.content.tryGetMap<String, Object?>('m.mentions')?['user_ids'] as List? ?? []).cast<String>())
          (name: room.unsafeGetUserFromMemoryOrFallback(id).calcDisplayname(), id: id)]);
    } else if (was != null && was.edit) {
      _c.text = _stash; // edit sent/cancelled, or switched to reply
      _mentions.clear();
    }
    if (m != null) _f.requestFocus();
  }

  void _onText() {
    if (mode?.edit != true) _drafts[room.id] = _c.text;
    _typing(_c.text.isNotEmpty);
    setState(() {});
  }

  void _typing(bool on) {
    final now = DateTime.now().millisecondsSinceEpoch;
    if (on && now - _typingAt < 3000) return;
    if (!on && _typingAt == 0) return;
    _typingAt = on ? now : 0;
    room.setTyping(on, timeout: 5000).catchError((_) {});
  }

  // ---------- @ mentions ----------

  RegExpMatch? get _at {
    final caret = _c.selection.baseOffset;
    return caret < 0 ? null : RegExp(r'(^|\s)@([^\s@]*)$').firstMatch(_c.text.substring(0, caret));
  }

  List<({String id, String name, Uri? mxc})> _suggestions() {
    final at = _at;
    if (at == null) return [];
    final pos = _c.selection.baseOffset - at[2]!.length - 1;
    if (pos == _closedAt) return [];
    final q = normalize(at[2]!);
    return [
      for (final u in room.getParticipants([Membership.join]).where((u) => u.id != me() && (normalize(u.calcDisplayname()).contains(q) || normalize(u.id).contains(q))).take(6))
        (id: u.id, name: u.calcDisplayname(), mxc: u.avatarUrl),
      if ('room'.startsWith(q) && room.canSendNotification(me())) (id: '@room', name: 'room', mxc: null),
    ];
  }

  void _pick(({String id, String name, Uri? mxc}) s) {
    final at = _at!, caret = _c.selection.baseOffset, pos = caret - at[2]!.length - 1;
    final insert = '@${s.name} ';
    if (s.id != '@room') _mentions.add((name: s.name, id: s.id));
    _c.value = TextEditingValue(text: _c.text.replaceRange(pos, caret, insert), selection: TextSelection.collapsed(offset: pos + insert.length));
    _f.requestFocus();
  }

  // ---------- sending ----------

  /// body + optional HTML with mention links, and the m.mentions that decides who gets pinged.
  Map<String, dynamic> _compose(String body) {
    // "@Name" / "@user:server" typed out without picking from the list counts too (Telegram habit)
    final typed = room.getParticipants([Membership.join]).where((u) => u.id != me())
        .expand((u) => [(name: u.calcDisplayname(), id: u.id), (name: u.id.substring(1), id: u.id)])
        .where((m) => RegExp('@${escRe(m.name)}(?![\\p{L}\\p{N}_])', unicode: true).hasMatch(body));
    final h = formatMessage(body, [..._mentions, ...typed]);
    final ids = {...?h?.ids};
    final replyTo = mode != null && !mode!.edit ? mode!.ev.senderId : null;
    if (replyTo != null && replyTo != me()) ids.add(replyTo); // spec: a reply mentions who it replies to
    final everyone = RegExp(r'(^|\s)@room\b').hasMatch(body) && room.canSendNotification(me());
    return {
      'body': body,
      if (h != null) ...{'format': 'org.matrix.custom.html', 'formatted_body': h.html},
      'm.mentions': {'user_ids': ids.toList(), if (everyone) 'room': true},
    };
  }

  void _send() {
    final body = _c.text.trim();
    if (body.isEmpty) return;
    _typing(false);
    final m = mode;
    // the SDK's inReplyTo/editEventId would add its own fallbacks; build the content like the web app instead
    if (m != null && m.edit) {
      final old = m.ev.getDisplayEvent(widget.timeline).content;
      if (body != stripReplyFallback('${old['body'] ?? ''}')) {
        final next = {'msgtype': old['msgtype'], ..._compose(body)};
        final before = {...?(old.tryGetMap<String, Object?>('m.mentions')?['user_ids'] as List?)?.cast<String>()};
        room.sendEvent({
          'msgtype': old['msgtype'], 'body': '* ${next['body']}',
          'm.new_content': next,
          // only people newly mentioned by the edit get pinged
          'm.mentions': {'user_ids': [for (final id in (next['m.mentions'] as Map)['user_ids'] as List) if (!before.contains(id)) id]},
          'm.relates_to': {'rel_type': 'm.replace', 'event_id': m.ev.eventId},
        }).catchError((_) => null);
      }
    } else {
      room.sendEvent({
        'msgtype': 'm.text', ..._compose(body),
        if (m != null) 'm.relates_to': {'m.in_reply_to': {'event_id': m.ev.eventId}},
      }).catchError((_) => null);
    }
    _mentions.clear();
    _c.clear(); // listener clears the draft
    widget.onMode(null);
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tk;
    final sugg = _suggestions();
    final text = _c.text.trim();
    final m = mode;
    return Material(
      color: t.panel,
      child: SafeArea(top: false, child: Column(mainAxisSize: MainAxisSize.min, children: [
        if (sugg.isNotEmpty) Container(
          constraints: const BoxConstraints(maxHeight: 280), decoration: BoxDecoration(border: Border(top: BorderSide(color: t.border))),
          child: ListView(shrinkWrap: true, padding: EdgeInsets.zero, children: [
            for (final s in sugg) ListTile(
              dense: true,
              leading: s.id == '@room' ? CircleAvatar(radius: 16, backgroundColor: t.accent, child: const Icon(Icons.group, size: 18, color: Colors.white))
                  : Avatar(mxc: s.mxc, name: s.name, id: s.id, size: 32),
              title: Text(s.id == '@room' ? '@room' : s.name, maxLines: 1, overflow: TextOverflow.ellipsis),
              subtitle: Text(s.id == '@room' ? 'همه‌ی اعضای گفتگو' : s.id, textDirection: TextDirection.ltr, style: TextStyle(fontSize: 12, color: t.muted)),
              onTap: () => _pick(s),
            ),
          ]),
        ),
        if (m != null) Container(
          padding: const EdgeInsetsDirectional.fromSTEB(14, 6, 4, 6),
          decoration: BoxDecoration(border: Border(top: BorderSide(color: t.border))),
          child: Row(children: [
            Icon(m.edit ? Icons.edit_outlined : Icons.reply, color: t.accent),
            const SizedBox(width: 12),
            Expanded(child: Container(
              padding: const EdgeInsetsDirectional.only(start: 8),
              decoration: BoxDecoration(border: BorderDirectional(start: BorderSide(color: t.accent, width: 2))),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(m.edit ? 'ویرایش پیام' : 'پاسخ به ${bdi(senderName(m.ev))}', maxLines: 1, overflow: TextOverflow.ellipsis,
                  style: TextStyle(color: t.accent, fontWeight: FontWeight.w600, fontSize: 13.5)),
                Text(previewText(m.ev.getDisplayEvent(widget.timeline)), maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(color: t.muted, fontSize: 13)),
              ]),
            )),
            IconButton(icon: Icon(Icons.close, color: t.muted), tooltip: 'لغو', onPressed: () => widget.onMode(null)),
          ]),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(8, 6, 8, 6),
          child: Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
            Expanded(child: Container(
              decoration: BoxDecoration(color: t.hover, borderRadius: BorderRadius.circular(22)),
              child: Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
                IconButton(icon: Icon(Icons.emoji_emotions_outlined, color: t.muted), tooltip: 'اموجی', onPressed: () => toast(context, 'به‌زودی')),
                Expanded(child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 4),
                  child: TextField(
                    controller: _c, focusNode: _f, minLines: 1, maxLines: 6,
                    keyboardType: TextInputType.multiline,
                    textInputAction: prefs.enterSends ? TextInputAction.send : TextInputAction.newline,
                    onSubmitted: prefs.enterSends ? (_) { _send(); _f.requestFocus(); } : null,
                    onTapOutside: (_) => _typing(false),
                    style: const TextStyle(fontSize: 16),
                    decoration: InputDecoration(hintText: 'پیام', hintStyle: TextStyle(color: t.muted), border: InputBorder.none, isDense: true,
                      contentPadding: const EdgeInsets.symmetric(vertical: 10)),
                  ),
                )),
                const SizedBox(width: 8),
              ]),
            )),
            const SizedBox(width: 6),
            text.isEmpty
                ? IconButton(icon: Icon(Icons.mic_none, color: t.muted, size: 28), tooltip: 'پیام صوتی', onPressed: () => toast(context, 'به‌زودی'))
                : IconButton.filled(
                    style: IconButton.styleFrom(backgroundColor: t.accent, foregroundColor: Colors.white),
                    icon: Icon(m?.edit == true ? Icons.check : Icons.send, textDirection: TextDirection.ltr), tooltip: 'ارسال', onPressed: _send),
          ]),
        ),
      ])),
    );
  }
}
