import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:matrix/matrix.dart';

import '../logic.dart';
import '../matrix.dart';
import '../theme.dart';

const _colors = [0xffe17076, 0xfffaa774, 0xffa695e7, 0xff7bc862, 0xff6ec9cb, 0xff65aadd, 0xffee7aae];

/// Telegram's per-user colour, same hash as the web app.
Color colorFor(String id) {
  var h = 0;
  for (final c in id.runes) {
    h = (h * 31 + c).toSigned(32);
  }
  return Color(_colors[h.toUnsigned(32) % _colors.length]);
}

String me() => client.userID!;

// ---------- media ----------

final _thumbs = <String, Future<Uint8List?>>{};

/// Authenticated mxc download (thumbnail if `size` is set), cached in memory and in the SDK's file store.
Future<Uint8List?> loadMxc(Uri mxc, {int? size}) => _thumbs.putIfAbsent('$mxc@$size', () async {
  final key = size == null ? mxc : mxc.replace(queryParameters: {'s': '$size'});
  final cached = await client.database.getFile(key);
  if (cached != null) return cached;
  final url = size == null ? await mxc.getDownloadUri(client) : await mxc.getThumbnailUri(client, width: size, height: size);
  final res = await client.httpClient.get(url, headers: {'authorization': 'Bearer ${client.accessToken}'});
  if (res.statusCode != 200) {
    _thumbs.remove('$mxc@$size'); // try again next time
    return null;
  }
  await client.database.storeFile(key, res.bodyBytes, DateTime.now().millisecondsSinceEpoch);
  return res.bodyBytes;
});

class Avatar extends StatelessWidget {
  final Uri? mxc;
  final String name, id;
  final double size;
  const Avatar({super.key, required this.mxc, required this.name, required this.id, this.size = 42});

  @override
  Widget build(BuildContext context) {
    final letters = name.replaceFirst(RegExp(r'^[@#!]'), '').characters;
    final fallback = Container(
      width: size, height: size, alignment: Alignment.center,
      decoration: BoxDecoration(shape: BoxShape.circle, gradient: LinearGradient(begin: Alignment.topCenter, end: Alignment.bottomCenter,
        colors: [Color.lerp(colorFor(id), Colors.white, .15)!, colorFor(id)])),
      child: Text(letters.isEmpty ? '?' : letters.first.toUpperCase(), style: TextStyle(color: Colors.white, fontSize: size * .42, fontWeight: FontWeight.w600)),
    );
    final m = mxc;
    if (m == null) return fallback;
    return FutureBuilder(
      future: loadMxc(m, size: (size * 2).round()),
      builder: (context, s) => s.data == null ? fallback
          : ClipOval(child: Image.memory(s.data!, width: size, height: size, fit: BoxFit.cover, gaplessPlayback: true)),
    );
  }
}

/// The room's photo; a DM without one shows the other person's. Groups never borrow a member's photo.
Uri? roomAvatarMxc(Room room) {
  final own = room.getState(EventTypes.RoomAvatar)?.content.tryGet<String>('url');
  if (own != null) return Uri.tryParse(own);
  final peer = room.directChatMatrixID;
  return peer == null ? null : room.unsafeGetUserFromMemoryOrFallback(peer).avatarUrl;
}

class RoomAvatar extends StatelessWidget {
  final Room room;
  final double size;
  const RoomAvatar(this.room, {super.key, this.size = 42});
  @override
  Widget build(BuildContext context) => Avatar(mxc: roomAvatarMxc(room), name: roomTitle(room), id: room.id, size: size);
}

/// Farsi room name (logic.roomName) when the room has none of its own.
String roomTitle(Room room) {
  if (room.name.isNotEmpty) return room.name;
  final alias = room.canonicalAlias.localpart;
  if (alias != null && alias.isNotEmpty) return alias;
  final heroes = [...?room.summary.mHeroes, ?room.directChatMatrixID].where((h) => h.isNotEmpty && h != client.userID).toSet();
  String? inviter;
  if (room.membership == Membership.invite) {
    final by = room.getState(EventTypes.RoomMember, client.userID!)?.senderId;
    if (by != null && by != client.userID) inviter = room.unsafeGetUserFromMemoryOrFallback(by).calcDisplayname();
  }
  final count = (room.summary.mJoinedMemberCount ?? 0) + (room.summary.mInvitedMemberCount ?? 0);
  return roomName(NameState(
    names: [for (final h in heroes) room.unsafeGetUserFromMemoryOrFallback(h).calcDisplayname()],
    count: count < heroes.length + 1 ? heroes.length + 1 : count,
  ), heroes.isEmpty ? inviter : null) ?? '';
}

/// The sender's *current* profile (Telegram-style).
String senderName(Event ev) => ev.room.unsafeGetUserFromMemoryOrFallback(ev.senderId).calcDisplayname();

bool isGroupChat(Room room) => (room.summary.mJoinedMemberCount ?? 0) > 2;

// ---------- feedback ----------


/// Small transient message at the bottom of the screen.
void toast(BuildContext context, String text) =>
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text), duration: const Duration(milliseconds: 1800)));

Future<void> copyText(BuildContext context, String text) async {
  await Clipboard.setData(ClipboardData(text: text));
  if (context.mounted) toast(context, 'کپی شد');
}

Future<bool> confirm(BuildContext context, String text, {String ok = 'تأیید', bool danger = false}) async =>
    await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        content: Text(text),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('انصراف')),
          TextButton(onPressed: () => Navigator.pop(c, true), child: Text(ok, style: danger ? const TextStyle(color: Color(0xffe53935)) : null)),
        ],
      ),
    ) ?? false;

Future<void> alert(BuildContext context, String text) => showDialog(
  context: context,
  builder: (c) => AlertDialog(content: Text(text), actions: [TextButton(onPressed: () => Navigator.pop(c), child: const Text('باشه'))]),
);

/// Runs `f`; an error becomes a Farsi alert.
Future<void> attempt(BuildContext context, Future<void> Function() f) async {
  try {
    await f();
  } catch (e) {
    if (context.mounted) await alert(context, errText(e));
  }
}

/// Farsi message for an SDK/network error.
String errText(Object e) {
  if (e is MatrixException && e.error == MatrixError.M_FORBIDDEN) return 'نام کاربری یا رمز عبور اشتباه است.';
  if (e is MatrixException) return e.errorMessage;
  final m = e.toString();
  return RegExp('socket|network|connection|host lookup|handshake', caseSensitive: false).hasMatch(m)
      ? 'به سرور دسترسی نیست. نشانی و پورت را بررسی کنید.'
      : m.replaceFirst(RegExp(r'^Exception: '), '');
}

/// Bidi-isolate a name inside a Farsi sentence so Latin names/punctuation don't reorder it.
String bdi(String s) => '\u2068$s\u2069';

// ---------- event text ----------

const verifyRequest = 'm.key.verification.request';
const pollStart = ['org.matrix.msc3381.poll.start', 'm.poll.start'];

bool isVoice(Map c) => c['org.matrix.msc3245.voice'] != null || c['m.voice'] != null;
/// ms; 0 if unknown
num audioDuration(Map c) => (c['info'] as Map?)?['duration'] ?? (c['org.matrix.msc1767.audio'] as Map?)?['duration'] ?? 0;

String stripReplyFallback(String body) => body.replaceFirst(RegExp(r'^(> .*\n)+\n?'), '');

String? _cap(Map c) => c['filename'] != null && c['body'] != c['filename'] ? c['body'] as String? : null;

/// Poll question text, stable or unstable names.
String? pollQuestion(Map c) {
  final p = (c['org.matrix.msc3381.poll.start'] ?? c['m.poll']) as Map?;
  final q = p?['question'] as Map?;
  return (q?['org.matrix.msc1767.text'] ?? q?['m.text'] ?? q?['body'])?.toString();
}

/// One-line text for list previews and reply quotes.
String previewText(Event ev) {
  if (ev.type == EventTypes.Encrypted) {
    return ev.messageType == MessageTypes.BadEncrypted ? '🔒 پیام رمزنگاری‌شده' : '🔒 در حال رمزگشایی…';
  }
  final c = ev.content;
  switch (c['msgtype']) {
    case 'm.image': return '🖼 ${_cap(c) ?? 'عکس'}';
    case 'm.video': return isGif(c) ? '🎞 ${_cap(c) ?? 'گیف'}' : '🎬 ${_cap(c) ?? 'ویدیو'}';
    case 'm.audio': return isVoice(c) ? '🎤 پیام صوتی ${fmtDuration(audioDuration(c))}' : '🎵 صدا';
    case 'm.location': return '📍 موقعیت مکانی';
    case 'm.file': return '📎 ${_cap(c) ?? c['filename'] ?? c['body'] ?? 'فایل'}';
    case 'm.emote': return '* ${senderName(ev)} ${c['body']}';
    case verifyRequest: return '🔐 درخواست تأیید هویت';
  }
  if (ev.type == EventTypes.Sticker) return 'استیکر';
  if (pollStart.contains(ev.type)) return '📊 ${pollQuestion(c) ?? 'نظرسنجی'}';
  return stripReplyFallback(c.tryGet<String>('body') ?? '');
}

/// Text for state events worth showing as a centered pill; null = hide.
String? noticeText(Event ev) {
  final who = bdi(senderName(ev));
  final c = ev.content, prev = ev.prevContent ?? const {};
  switch (ev.type) {
    case EventTypes.Message: return c['msgtype'] == verifyRequest ? '$who درخواست تأیید هویت فرستاد' : null;
    case EventTypes.RoomCreate: return '$who گفتگو را ایجاد کرد';
    case EventTypes.RoomName:
      final n = c['name'];
      return n is String && n.isNotEmpty ? '$who نام گفتگو را به «${bdi(n)}» تغییر داد' : '$who نام گفتگو را حذف کرد';
    case EventTypes.RoomTopic: return '$who موضوع را تغییر داد';
    case EventTypes.Encryption: return 'پیام‌ها رمزنگاری سرتاسری شده‌اند';
    case EventTypes.RoomPinnedEvents:
      final n = (c['pinned'] as List?)?.length ?? 0, was = (prev['pinned'] as List?)?.length ?? 0;
      return n > was ? '$who پیامی را سنجاق کرد' : n < was ? '$who سنجاق پیامی را برداشت' : null;
    case EventTypes.RoomMember:
      final target = bdi((c['displayname'] ?? ev.stateKey ?? '').toString());
      final prevName = bdi((prev['displayname'] ?? c['displayname'] ?? ev.stateKey ?? '').toString());
      if (c['membership'] == prev['membership']) return null; // profile changes are noise
      switch (c['membership']) {
        case 'join': return '$target پیوست';
        case 'invite': return '$who $target را دعوت کرد';
        case 'knock': return '$target درخواست عضویت داد';
        case 'leave':
          if (ev.senderId == ev.stateKey) return prev['membership'] == 'knock' ? '$prevName درخواست عضویتش را پس گرفت' : '$prevName خارج شد';
          return switch (prev['membership']) {
            'ban' => '$who مسدودیت $prevName را برداشت',
            'knock' => '$who درخواست عضویت $prevName را رد کرد',
            'invite' => '$who دعوت $prevName را لغو کرد',
            _ => '$who $prevName را بیرون کرد',
          };
        case 'ban': return '$who $prevName را مسدود کرد';
      }
      return null;
    // room creation sets these too; only later changes are news
    case EventTypes.RoomPowerLevels:
      if (prev.isEmpty) return null;
      final changes = levelChanges(prev.cast(), c.cast());
      if (changes.isEmpty) return null;
      final c1 = changes.first, more = changes.length - 1;
      final name = bdi(ev.room.unsafeGetUserFromMemoryOrFallback(c1.id).calcDisplayname());
      return '$who نقش $name را به «${roleLabel(c1.to) ?? 'عضو'}» تغییر داد${more > 0 ? ' (و ${faNum(more)} نفر دیگر)' : ''}';
    case EventTypes.RoomJoinRules:
      final r = c['join_rule'];
      return prev['join_rule'] != null && r != prev['join_rule'] ? '$who شیوه‌ی پیوستن را به «${joinRules[r] ?? r}» تغییر داد' : null;
    case EventTypes.HistoryVisibility:
      final h = c['history_visibility'];
      return prev['history_visibility'] != null && h != prev['history_visibility'] ? '$who دسترسی به تاریخچه را به «${history[h] ?? h}» تغییر داد' : null;
    case EventTypes.RoomTombstone: return '$who این گروه را ارتقا داد';
    // ponytail: call outcomes (rtcOutcome/legacyOutcome) come with calls, phase 6
    case 'm.rtc.notification' || 'org.matrix.msc4075.rtc.notification':
      final kind = c['m.call.intent'] == 'video' ? 'تماس تصویری' : 'تماس صوتی';
      return c['notification_type'] != 'ring' ? '$who $kind گروهی را شروع کرد' : '$who $kind گرفت';
    case EventTypes.CallInvite: return '$who ${isVideoOffer(c.cast()) ? 'تماس تصویری' : 'تماس صوتی'} گرفت';
  }
  return null;
}

String formatSize(num n) {
  const u = ['بایت', 'کیلوبایت', 'مگابایت', 'گیگابایت'];
  var i = 0;
  while (n >= 1024 && i < u.length - 1) {
    n /= 1024;
    i++;
  }
  return '${faDigits(n.toStringAsFixed(i > 0 ? 1 : 0).replaceFirst(RegExp(r'\.0$'), ''))} ${u[i]}';
}

/// Telegram's grey section header and list rows, for settings-like pages.
class Section extends StatelessWidget {
  final String? title;
  final List<Widget> children;
  const Section({super.key, this.title, required this.children});
  @override
  Widget build(BuildContext context) => Container(
    color: context.tk.panel,
    margin: const EdgeInsets.only(bottom: 10),
    child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      if (title != null) Padding(padding: const EdgeInsetsDirectional.fromSTEB(20, 14, 20, 4),
        child: Text(title!, style: TextStyle(color: context.tk.accent, fontWeight: FontWeight.w600, fontSize: 15))),
      ...children,
    ]),
  );
}
