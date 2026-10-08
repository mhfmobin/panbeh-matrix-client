// Pure helpers — no SDK imports so `flutter test` can run them directly.
import 'package:shamsi_date/shamsi_date.dart';

class Msg {
  final String id, sender;
  final int ts;
  final String kind; // "msg" | "notice"
  const Msg(this.id, this.sender, this.ts, [this.kind = 'msg']);
}

/// type: "day" | "notice" | "unread" | "msg". label is set on day rows, first/last on msg rows.
class TimelineRow {
  final String type, key;
  final String? id, label;
  final bool first, last;
  const TimelineRow(this.type, this.key, {this.id, this.label, this.first = false, this.last = false});
}

// Intl fa-IR (Jalali calendar + Persian digits) isn't in Dart's intl, so spell it out
const _months = ['فروردین', 'اردیبهشت', 'خرداد', 'تیر', 'مرداد', 'شهریور', 'مهر', 'آبان', 'آذر', 'دی', 'بهمن', 'اسفند'];
const _weekdays = ['شنبه', 'یکشنبه', 'دوشنبه', 'سه‌شنبه', 'چهارشنبه', 'پنجشنبه', 'جمعه'];
String faDigits(String s) => s.replaceAllMapped(RegExp('[0-9]'), (m) => String.fromCharCode(0x06F0 + m[0]!.codeUnitAt(0) - 48));
DateTime _dt(int ts) => DateTime.fromMillisecondsSinceEpoch(ts);
String _jy(DateTime d) => faDigits('${Jalali.fromDateTime(d).year}');
String _dayMonth(DateTime d) { final j = Jalali.fromDateTime(d); return '${faDigits('${j.day}')} ${_months[j.month - 1]}'; }
/// Intl's group separator is U+066C, and a negative sign is LRM + U+2212.
String faNum(num n) {
  final s = n.abs().toString().replaceAllMapped(RegExp(r'\B(?=(\d{3})+(?!\d))'), (_) => '٬');
  return (n < 0 ? '‎−' : '') + faDigits(s);
}
String _time(DateTime d) => faDigits('${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}');
int _now() => DateTime.now().millisecondsSinceEpoch;

const _groupGap = 5 * 60000;
String day(int ts) { final d = _dt(ts); return '${d.year}-${d.month}-${d.day}'; }
bool _groups(Msg? a, Msg? b) =>
    a != null && b != null && a.kind == 'msg' && b.kind == 'msg' &&
    a.sender == b.sender && b.ts - a.ts < _groupGap && day(a.ts) == day(b.ts);

/// Timeline rows: day separators + messages flagged as first/last of a same-sender burst,
/// plus an "unread" divider after `unreadAfter` (a Msg id) when anything follows it.
List<TimelineRow> buildRows(List<Msg> msgs, [int? now, String? unreadAfter]) {
  now ??= _now();
  final rows = <TimelineRow>[];
  var prevDay = '';
  for (var i = 0; i < msgs.length; i++) {
    final m = msgs[i];
    if (day(m.ts) != prevDay) {
      prevDay = day(m.ts);
      rows.add(TimelineRow('day', 'day-$prevDay', label: dayLabel(m.ts, now)));
    }
    if (m.kind == 'notice') {
      rows.add(TimelineRow('notice', m.id, id: m.id));
    } else {
      // the divider splits a burst
      rows.add(TimelineRow('msg', m.id, id: m.id,
          first: !_groups(i > 0 ? msgs[i - 1] : null, m) || msgs[i - 1].id == unreadAfter,
          last: !_groups(m, i + 1 < msgs.length ? msgs[i + 1] : null) || m.id == unreadAfter));
    }
    if (m.id == unreadAfter && i < msgs.length - 1) rows.add(const TimelineRow('unread', 'unread'));
  }
  return rows;
}

String dayLabel(int ts, [int? now]) {
  final d = _dt(ts), n = _dt(now ?? _now());
  final diff = (DateTime.utc(n.year, n.month, n.day).difference(DateTime.utc(d.year, d.month, d.day)).inHours / 24).round();
  if (diff == 0) return 'امروز';
  if (diff == 1) return 'دیروز';
  // Jalali years roll over at Nowruz, not Jan 1, so compare them in that calendar
  return _jy(d) == _jy(n) ? _dayMonth(d) : '${_dayMonth(d)} ${_jy(d)}';
}

/// Chat-list timestamp: 14:05 today, "Mon" this week, "12 Mar" otherwise.
String listTime(int ts, [int? now]) {
  now ??= _now();
  if (!(ts > 0)) return '';
  final d = _dt(ts);
  if (day(ts) == day(now)) return _time(d);
  if (now - ts < 6 * 86400000) return _weekdays[(d.weekday + 1) % 7];
  return _dayMonth(d);
}

String clock(int ts) => _time(_dt(ts));

class RoomInfo {
  final String id;
  final bool isDM;
  final int unread;
  final List<String> spaces;
  final bool marked, archived, muted; // marked = "mark as unread"
  const RoomInfo({required this.id, required this.isDM, required this.unread, required this.spaces, this.marked = false, this.archived = false, this.muted = false});
}
class Folder {
  final String id, label;
  const Folder(this.id, this.label);
}

const baseFolders = [Folder('all', 'همه'), Folder('unread', 'خوانده‌نشده'), Folder('dms', 'شخصی'), Folder('groups', 'گروه‌ها')];

/// Folders in the user's saved order. "all" always leads; folders the order doesn't know (a newly joined space)
/// follow in their default order, and ids that no longer exist are ignored.
List<Folder> applyFolderOrder(List<Folder> folders, List<String> order) {
  final rank = {for (var i = 0; i < order.length; i++) order[i]: i};
  final rest = folders.where((f) => f.id != 'all').toList();
  final known = rest.where((f) => rank.containsKey(f.id)).toList()..sort((a, b) => rank[a.id]! - rank[b.id]!);
  return [...folders.where((f) => f.id == 'all'), ...known, ...rest.where((f) => !rank.containsKey(f.id))];
}

/// `ids` with the one at `from` moved to `to`. The first (the "all" tab) neither moves nor gets displaced.
List<String> moveFolder(List<String> ids, int from, int to) {
  if (from < 1 || to < 1 || from >= ids.length || to >= ids.length || from == to) return ids;
  final next = ids.toList();
  next.insert(to, next.removeAt(from));
  return next;
}

/// Archived chats live in the "archive" pseudo-folder and nowhere else.
bool inFolder(RoomInfo r, String folder) {
  if ((folder == 'archive') != inArchive(r)) return false;
  return switch (folder) {
    'all' || 'archive' => true,
    'unread' => isUnread(r),
    'dms' => r.isDM,
    'groups' => !r.isDM,
    _ => r.spaces.contains(folder), // space id
  };
}

/// All rooms reachable from a space, following sub-spaces (cycle-safe).
Set<String> spaceRooms(String spaceId, List<String> Function(String id) children) {
  final seen = <String>{};
  void walk(String id) {
    for (final c in children(id)) {
      if (seen.add(c)) walk(c);
    }
  }
  walk(spaceId);
  return seen;
}

String normalizeServer(String input) {
  final s = input.trim().replaceFirst(RegExp(r'/+$'), '');
  return RegExp(r'^https?://', caseSensitive: false).hasMatch(s) ? s : 'https://$s';
}

/// Full Matrix user ID, e.g. "@alice:example.org" (worth a direct profile lookup).
bool isUserId(String s) => RegExp(r'^@[^\s:]+:[^\s:]+(:\d+)?$').hasMatch(s.trim());

/// The SDK's RoomNameState, structurally (count includes us).
class NameState {
  final String? name, subtype, oldName;
  final List<String>? names;
  final int count;
  const NameState({this.name, this.names, this.count = 0, this.subtype, this.oldName});
}

/// Farsi room names for the SDK's roomNameGenerator; null keeps an actual name. `inviter` names an invite with no members known.
String? roomName(NameState s, [String? inviter]) {
  if (s.name != null) return null;
  final ns = s.names;
  if (ns != null && ns.isNotEmpty) {
    final a = ns[0], others = s.count - 1;
    final names = ns.length == 1 && others <= 1 ? a
        : ns.length == 2 && others <= 2 ? '$a و ${ns[1]}'
        : '$a و ${faNum(others - 1)} نفر دیگر';
    return s.subtype == 'Inviting' ? 'در حال دعوت از $names' : names;
  }
  final old = s.oldName;
  return inviter ?? (old != null && old.isNotEmpty ? 'گفتگوی خالی (قبلاً $old)' : 'گفتگوی خالی');
}

/// Suggested #address localpart from a room name; Latin letters/digits only (Farsi names give "").
String aliasLocalpart(String name) =>
    name.toLowerCase().replaceAll(RegExp('[^a-z0-9]+'), '-').replaceAll(RegExp(r'^-+|-+$'), '');

/// Search-term folding: case, Arabic/Persian ي ك, and ZWNJ don't matter.
String normalize(String s) => s.toLowerCase().replaceAll('ي', 'ی').replaceAll('ك', 'ک').replaceAll('‌', '');

/// "امروز، ۱۴:۰۵" — day plus time, for read receipts.
String stamp(int ts, [int? now]) => '${dayLabel(ts, now)}، ${clock(ts)}';

/// Telegram-style last seen from m.presence; null when the server told us nothing.
String? lastSeen(bool online, int? activeTs, [int? now]) {
  now ??= _now();
  if (online) return 'آنلاین';
  if (activeTs == null || activeTs <= 0) return null;
  final min = ((now - activeTs) / 60000).floor();
  if (min < 1) return 'چند لحظه پیش';
  if (min < 60) return 'آخرین بازدید ${faNum(min)} دقیقه پیش';
  return 'آخرین بازدید ${day(activeTs) == day(now) ? 'امروز' : dayLabel(activeTs, now)}، ${clock(activeTs)}';
}

/// "۱:۰۵" from milliseconds.
String fmtDuration(num ms) {
  final s = ms / 1000 < 0 ? 0 : (ms / 1000).round();
  return '${faNum(s ~/ 60)}:${faNum(s % 60).padLeft(2, '۰')}';
}

/// `n` bars (peak of each slice), scaled 0..1024 like MSC1767 waveforms. Also rescales a received waveform for display.
List<int> downsample(List<num> xs, [int n = 40]) {
  if (xs.isEmpty) return [];
  final mx = xs.reduce((a, b) => a > b ? a : b);
  final max = mx == 0 ? 1 : mx;
  return List.generate(n, (i) {
    final a = (i * xs.length) ~/ n;
    final b = ((i + 1) * xs.length) ~/ n;
    final part = xs.sublist(a, a + 1 > b ? a + 1 : b);
    return ((part.reduce((a, b) => a > b ? a : b) / max) * 1024).round();
  });
}

/// geo:lat,lon[,alt][;u=accuracy] → numbers; null if malformed.
({double lat, double lon, double? acc})? parseGeoUri(Object? uri) {
  final m = RegExp(r'^geo:(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)(?:,-?[\d.]+)?(?:;.*?\bu=(\d+(?:\.\d+)?))?', caseSensitive: false).firstMatch(uri is String ? uri : '');
  return m == null ? null : (lat: double.parse(m[1]!), lon: double.parse(m[2]!), acc: m[3] == null ? null : double.parse(m[3]!));
}

String osmUrl(num lat, num lon) => 'https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=16/$lat/$lon';

class Vote {
  final String sender;
  final int ts;
  final Object? answers;
  const Vote(this.sender, this.ts, this.answers);
}

/// Poll results from response events: each voter's latest vote counts. Per MSC3381 a vote with an unknown
/// or no answer is spoiled (and still replaces that voter's earlier vote); answers past `max` are dropped.
({Map<String, List<String>> voters, Map<String, List<String>> picks}) tallyPoll(List<Vote> votes, List<String> answerIds, int max) {
  final latest = <String, Vote>{};
  for (final v in votes) {
    if (!((latest[v.sender]?.ts ?? double.negativeInfinity) > v.ts)) latest[v.sender] = v;
  }
  final voters = {for (final id in answerIds) id: <String>[]};
  final picks = <String, List<String>>{};
  latest.forEach((sender, v) {
    final ans = v.answers;
    final a = ans is List ? ans.take(max < 1 ? 1 : max).toSet().toList() : <Object?>[];
    if (a.isEmpty || a.any((id) => !voters.containsKey(id))) return;
    picks[sender] = a.cast<String>();
    for (final id in a) {
      voters[id]!.add(sender);
    }
  });
  return (voters: voters, picks: picks);
}

String _esc(String s) => s.replaceAllMapped(RegExp('[&<>"\']'), (m) => '&#${m[0]!.codeUnitAt(0)};');
String escRe(String s) => s.replaceAllMapped(RegExp(r'[.*+?^${}()|[\]\\]'), (m) => '\\${m[0]}');

/// Telegram-style Markdown subset + "@Name" mentions as matrix.to links. Everything that must not be
/// re-parsed (code, links, mentions) is stashed behind \0N\0 placeholders until the end.
/// null when nothing got formatted, so plain messages send no formatted_body.
({String html, List<String> ids})? formatMessage(String text, List<({String name, String id})> mentions) {
  final stash = <String>[];
  String hold(String html) { stash.add(html); return '\u0000${stash.length - 1}\u0000'; }
  var t = text.replaceAll('\u0000', '');
  t = t.replaceAllMapped(RegExp(r'\n?```(?:[\w+-]*\n)?([\s\S]*?)\n?```\n?'), (m) => hold('<pre><code>${_esc(m[1]!)}</code></pre>'));
  t = t.replaceAllMapped(RegExp(r'`([^`\n]+)`'), (m) => hold('<code>${_esc(m[1]!)}</code>'));

  final byName = {for (final m in mentions) '@${m.name}': m.id};
  final used = <String>{};
  if (byName.isNotEmpty) {
    // longest first so "@Ali Reza" isn't cut short by "@Ali"
    final re = RegExp(([...byName.keys]..sort((a, b) => b.length - a.length)).map(escRe).join('|'));
    t = t.replaceAllMapped(re, (m) {
      final id = byName[m[0]]!;
      used.add(id);
      return hold('<a href="https://matrix.to/#/${_esc(id)}">${_esc(m[0]!.substring(1))}</a>');
    });
  }

  t = _esc(t);
  t = t.replaceAllMapped(RegExp(r'\[([^\]\n]+)\]\((https?://[^\s)]+)\)'), (m) => hold('<a href="${m[2]}">${m[1]}</a>'));
  final parts = <({StringBuffer html, bool quote})>[];
  for (final line in t.split('\n')) {
    final q = line.startsWith('&#62; ');
    if (q && parts.isNotEmpty && parts.last.quote) {
      parts.last.html.write('<br>${line.substring(6)}');
    } else {
      parts.add((html: StringBuffer(q ? line.substring(6) : line), quote: q));
    }
  }
  var h = [for (var i = 0; i < parts.length; i++)
    (parts[i].quote ? '<blockquote>${parts[i].html}</blockquote>' : '${parts[i].html}') +
        (i < parts.length - 1 && !parts[i].quote && !parts[i + 1].quote ? '\n' : '')].join();
  void wrap(String re, String open, String close) => h = h.replaceAllMapped(RegExp(re), (m) => '$open${m[1]}$close');
  wrap(r'\*\*([^\s*](?:[^*\n]*?[^\s*])?)\*\*', '<strong>', '</strong>');
  wrap(r'~~([^\s~](?:[^~\n]*?[^\s~])?)~~', '<del>', '</del>');
  wrap(r'\|\|([^\s|](?:[^|\n]*?[^\s|])?)\|\|', '<span data-mx-spoiler>', '</span>');
  // opening marker not after a word char, closing not before one: snake_case and 2*3*4 stay literal
  h = h.replaceAllMapped(RegExp(r'(?<![\p{L}\p{N}_*])([*_])(?=\S)((?:(?!\1)[^\n])*?\S)\1(?![\p{L}\p{N}_*])', unicode: true), (m) => '<em>${m[2]}</em>');
  h = h.replaceAll('\n', '<br>'); // before restoring, so <pre> keeps its newlines
  while (h.contains('\u0000')) {
    h = h.replaceAllMapped(RegExp(r'\x00(\d+)\x00'), (m) => stash[int.parse(m[1]!)]);
  }
  final clean = _esc(text.replaceAll('\u0000', '')).replaceAll('\n', '<br>');
  return used.isNotEmpty || h != clean ? (html: h, ids: used.toList()) : null;
}

const _tlds = 'com|org|net|edu|gov|int|io|ir|co|me|app|dev|info|biz|ai|tv|xyz|ly|gl|us|uk|de|fr|ru|tech|online|site|chat|link|page';
/// What counts as a web link in plain text: http(s)://…, www.…, or a bare domain with a well-known TLD (not in emails).
const linkSrc =
    r'''(?:https?:\/\/|www\.)[^\s<]+[^\s<.,;:!?)"'`]'''
    r'''|(?<![\w@.\/-])(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+(?:''' '$_tlds' r''')(?![\w@-])(?::\d+)?(?:\/[^\s<]*[^\s<.,;:!?)"'`])?''';
/// href for a link found by linkSrc: scheme-less ones get https://.
String linkHref(String u) => RegExp(r'^(?:https?:|matrix:)', caseSensitive: false).hasMatch(u) ? u : 'https://$u';
/// Distinct web links in text (same pattern as the bubble's linkify), as hrefs.
List<String> extractLinks(String text) => {for (final m in RegExp(linkSrc, caseSensitive: false).allMatches(text)) linkHref(m[0]!)}.toList();
/// Links in a message's own text, not in the quoted reply fallback.
List<String> contentLinks(Map<String, dynamic> c) => extractLinks('${c['body'] ?? ''}'.replaceFirst(RegExp(r'^(> .*\n)+\n?'), ''));

/// Shared-media tab for an event: "media" | "file" | "link" | "audio"; null = not listed (stickers, polls, plain text…).
String? mediaKind(String type, Map<String, dynamic> c) {
  if (type != 'm.room.message') return null;
  return switch (c['msgtype']) {
    'm.image' || 'm.video' => 'media',
    'm.file' => 'file',
    'm.audio' => 'audio',
    'm.text' || 'm.notice' || 'm.emote' => contentLinks(c).isNotEmpty ? 'link' : null,
    _ => null,
  };
}
bool isUnread(RoomInfo r) => r.unread > 0 || r.marked;

/// Telegram-style: an unmuted archived chat with new messages shows in the main list until it's read again.
bool inArchive(RoomInfo r) => r.archived && !(r.unread > 0 && !r.muted);

abstract interface class ListOrdered {
  bool get invite;
  bool get pinned;
  int get ts;
}
/// Chat list order: invites, then pinned, then newest.
int byListOrder(ListOrdered a, ListOrdered b) {
  int n(bool x) => x ? 1 : 0;
  final i = n(b.invite) - n(a.invite);
  if (i != 0) return i;
  final p = n(b.pinned) - n(a.pinned);
  return p != 0 ? p : b.ts - a.ts;
}
bool _truthy(Object? v) => v != null && v != false && v != 0 && v != '';
/// A looping, silent video (mautrix's flags, also set by the Telegram bridge) or an animated gif image.
bool isGif(Map<String, dynamic> c) {
  final info = c['info'] as Map?;
  return _truthy(info?['fi.mau.gif']) || (c['msgtype'] == 'm.image' && info?['mimetype'] == 'image/gif');
}

/// {msgtype, body, info?, url?, file?: {url}}
typedef Gif = Map<String, dynamic>;
Object? _gifKey(Gif g) => g['url'] ?? (g['file'] as Map?)?['url'];
// ponytail: capped at 100, account data tops out around 64KB
/// Saved gifs with `g` first: re-saving moves it up instead of duplicating.
List<Gif> withGif(List<Gif> list, Gif g) => [g, ...list.where((x) => _gifKey(x) != _gifKey(g))].take(100).toList();
List<Gif> withoutGif(List<Gif> list, Gif g) => list.where((x) => _gifKey(x) != _gifKey(g)).toList();
bool hasGif(List<Gif> list, Gif g) => list.any((x) => _gifKey(x) == _gifKey(g));

// ---------- room admin ----------

/// "مدیر" / "ناظر" / "سطح ۲۵"; null for plain members.
String? roleLabel(int level) => level >= 100 ? 'مدیر' : level >= 50 ? 'ناظر' : level > 0 ? 'سطح ${faNum(level)}' : null;

/// Knocking needs room version 7+; unknown or non-numeric (experimental) versions count as unsupported.
bool supportsKnock(String? version) => RegExp(r'^\d+$').hasMatch(version ?? '') && int.parse(version!) >= 7;

const joinRules = {'public': 'عمومی؛ هر کسی می‌تواند بپیوندد', 'invite': 'خصوصی؛ فقط با دعوت', 'knock': 'با درخواست عضویت', 'restricted': 'اعضای فضا'};
const history = {'shared': 'اعضا، از ابتدا', 'invited': 'اعضا، از زمان دعوت', 'joined': 'اعضا، از زمان پیوستن', 'world_readable': 'همه، حتی بدون عضویت'};

/// Users whose power level differs between two m.room.power_levels contents (missing = users_default).
List<({String id, int from, int to})> levelChanges(Map<String, dynamic> prev, Map<String, dynamic> next) {
  int at(Map<String, dynamic> c, String id) => ((c['users'] as Map?)?[id] ?? c['users_default'] ?? 0 as num).toInt();
  final ids = {...((prev['users'] as Map?)?.keys ?? []), ...((next['users'] as Map?)?.keys ?? [])}.cast<String>();
  return [for (final id in ids) if (at(prev, id) != at(next, id)) (id: id, from: at(prev, id), to: at(next, id))];
}

/// Scale (w, h) so the longer side is at most `max`; never upscales.
({int w, int h}) fitSize(num w, num h, [num max = 1280]) {
  final k = [1, max / (w > h ? w : h)].reduce((a, b) => a < b ? a : b);
  return (w: (w * k).round(), h: (h * k).round());
}

/// Direction of a message's text the way dir=auto picks it (first letter), but the same everywhere: WebViews disagree on text
/// with no letters (numbers, emoji), and the time/ticks have to sit on the side the text leaves free.
String textDir(String s) {
  final letter = RegExp(r'\p{L}', unicode: true).firstMatch(s)?[0];
  return letter != null && RegExp(r'[\p{Script=Arabic}\p{Script=Hebrew}\p{Script=Syriac}\p{Script=Thaana}\p{Script=Nko}]', unicode: true).hasMatch(letter) ? 'rtl' : 'ltr';
}

/// An m.rtc.notification (MSC4075) content: {notification_type?, "m.mentions"?: {room?, user_ids?}}.
bool _aimedAt(Map<String, dynamic> c, String me) {
  final m = c['m.mentions'] as Map?;
  return _truthy(m?['room']) || ((m?['user_ids'] as List?)?.contains(me) ?? false);
}
/// An m.rtc.notification (MSC4075) that should ring `me` now: a "ring" (not a group "notification"), aimed at us or the room, before `until`.
bool isRing(Map<String, dynamic> c, num until, String me, [int? now]) =>
    c['notification_type'] == 'ring' && _aimedAt(c, me) && until > (now ?? _now()); // NaN until = no
/// A group call that just started (a "notification", not a ring): worth a quiet notification.
bool isGroupCallAlert(Map<String, dynamic> c, num until, String me, [int? now]) =>
    c['notification_type'] == 'notification' && _aimedAt(c, me) && until > (now ?? _now());

/// A legacy 1:1 m.call.invite (old Element, FluffyChat, Nheko…) that should ring `me` now: unexpired and not aimed at someone else.
bool isLegacyRing(Map<String, dynamic> c, int ts, String me, [int? now]) {
  final invitee = c['invitee'];
  return ts + ((c['lifetime'] ?? 0) as num) > (now ?? _now()) && (!_truthy(invitee) || invitee == me);
}
bool isVideoOffer(Map<String, dynamic> c) => RegExp(r'^m=video', multiLine: true).hasMatch(((c['offer'] as Map?)?['sdp'] ?? '') as String);

// ---------- how a call went (for its line in the timeline) ----------

class CallEv {
  final String? id;
  final String type, sender;
  final int ts;
  final Map<String, dynamic> content;
  const CallEv({this.id, required this.type, required this.sender, required this.ts, required this.content});
}
/// state: "ringing" | "ongoing" | "missed" | "declined" | "ended". ringing/ongoing: nothing to add yet. ended: answered, for `duration` ms.
typedef CallOutcome = ({String state, int? duration});
CallOutcome _o(String state, [int? duration]) => (state: state, duration: duration);
const _member = 'org.matrix.msc3401.call.member';

/// A legacy m.call.invite, from the events after it in the room (oldest first).
CallOutcome legacyOutcome(CallEv invite, List<CallEv> after, [int? now]) {
  now ??= _now();
  final same = after.where((e) => e.content['call_id'] == invite.content['call_id']);
  final answer = same.where((e) => e.type == 'm.call.answer').firstOrNull;
  final end = same.where((e) => e.type == 'm.call.hangup' || e.type == 'm.call.reject').firstOrNull;
  if (answer != null) return end != null ? _o('ended', end.ts - answer.ts) : _o('ongoing');
  if (end?.type == 'm.call.reject') return _o('declined');
  return end != null || invite.ts + ((invite.content['lifetime'] ?? 0) as num) <= now ? _o('missed') : _o('ringing');
}

/// A MatrixRTC ring (m.rtc.notification) ringing until `until`: answered once someone else's call membership shows up, over when one side leaves.
CallOutcome rtcOutcome(CallEv ring, int until, List<CallEv> after, [int? now]) {
  now ??= _now();
  final members = after.where((e) => e.type == _member).toList();
  final joined = members.where((e) => e.sender != ring.sender && e.content.isNotEmpty && e.ts <= until).firstOrNull;
  if (joined != null) {
    final left = members.where((e) => e.ts >= joined.ts && e.content.isEmpty).firstOrNull; // 1:1: whoever leaves first ends it
    return left != null ? _o('ended', left.ts - joined.ts) : _o('ongoing');
  }
  if (after.any((e) => e.type.endsWith('rtc.decline') && (e.content['m.relates_to'] as Map?)?['event_id'] == ring.id)) return _o('declined');
  final gaveUp = members.any((e) => e.sender == ring.sender && e.content.isEmpty);
  return gaveUp || until <= now ? _o('missed') : _o('ringing');
}

/// Endpoint name for the net log: /_matrix/client/v3/rooms/!x/send/… → rooms.
String endpointOf(String url) =>
    Uri.parse(url).path.split('/').skip(2).where((x) => !RegExp(r'^(client|media|v\d+|r0|unstable)$').hasMatch(x)).firstOrNull ?? '?';
