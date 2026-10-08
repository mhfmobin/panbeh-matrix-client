import 'package:flutter_test/flutter_test.dart';
import 'package:panbeh/logic.dart';

final T = DateTime(2026, 9, 30, 12).millisecondsSinceEpoch;
Msg m(String id, String sender, num min, [String kind = 'msg']) => Msg(id, sender, T + (min * 60000).round(), kind);
Map<String, dynamic> flagsOf(List<TimelineRow> rows) => {for (final r in rows.where((r) => r.type == 'msg')) r.id!: '${r.first ? 1 : 0}${r.last ? 1 : 0}'};
RoomInfo room({bool isDM = true, int unread = 0, List<String> spaces = const [], bool marked = false, bool archived = false, bool muted = false}) =>
    RoomInfo(id: '1', isDM: isDM, unread: unread, spaces: spaces, marked: marked, archived: archived, muted: muted);
class Li implements ListOrdered {
  @override final bool invite, pinned;
  @override final int ts;
  final String id;
  Li(this.id, this.ts, {this.invite = false, this.pinned = false});
}

void main() {
  test('groups same-sender bursts, splits on gap, sender, notice and day', () {
    final rows = buildRows([
      m('a', '@x', 0), m('b', '@x', 1), m('c', '@x', 2), // one group
      m('d', '@x', 10), // gap > 5min
      m('e', '@y', 11), // other sender
      m('f', '@y', 12, 'notice'), m('g', '@y', 13), // notice breaks group
      m('h', '@y', 13 + 24 * 60), // next day
    ], T);
    expect(flagsOf(rows), {'a': '10', 'b': '00', 'c': '01', 'd': '11', 'e': '11', 'g': '11', 'h': '11'});
    expect(rows.where((r) => r.type == 'day').map((r) => r.label), ['امروز', '۹ مهر']);
  });

  test('dayLabel compares Jalali years, not Gregorian', () {
    final now = DateTime(2026, 10, 1, 12).millisecondsSinceEpoch;
    expect(dayLabel(DateTime(2026, 2, 15, 12).millisecondsSinceEpoch, now), '۲۶ بهمن ۱۴۰۴'); // same Gregorian year, previous Jalali year
    expect(dayLabel(DateTime(2026, 4, 15, 12).millisecondsSinceEpoch, now), '۲۶ فروردین');
  });

  test('folders', () {
    final dm = room(), grp = room(isDM: false, unread: 3, spaces: ['!s']);
    expect(['all', 'unread', 'dms', 'groups', '!s'].map((f) => [inFolder(dm, f), inFolder(grp, f)]),
        [[true, true], [false, true], [true, false], [false, true], [false, true]]);
  });

  test('spaceRooms follows sub-spaces and survives cycles', () {
    final tree = {'s': ['a', 'sub'], 'sub': ['b', 's'], 'a': <String>[], 'b': <String>[]};
    expect(spaceRooms('s', (id) => tree[id] ?? []).toList()..sort(), ['a', 'b', 's', 'sub']);
  });

  test('normalizeServer', () {
    expect(normalizeServer(' chat.example.org:8448/ '), 'https://chat.example.org:8448');
    expect(normalizeServer('http://localhost:8008'), 'http://localhost:8008');
  });

  test('user IDs and alias suggestions', () {
    expect(isUserId('@b:localhost'), true);
    expect(isUserId(' @alice:matrix.org:8448 '), true);
    expect(isUserId('alice'), false);
    expect(isUserId('@alice'), false);
    expect(isUserId('#room:server'), false);
    expect(aliasLocalpart('My Cool Group!'), 'my-cool-group');
    expect(aliasLocalpart('گروه دوستان'), '');
    expect(stamp(T, T), matches(RegExp('^امروز، ')));
  });

  test('voice helpers', () {
    expect(fmtDuration(65400), '۱:۰۵');
    expect(fmtDuration(0), '۰:۰۰');
    expect(downsample([1, 2, 3, 4], 2), [512, 1024]);
    expect(downsample([0, 5], 4), [0, 0, 1024, 1024]);
    expect(downsample([]), []);
  });

  test('parseGeoUri', () {
    expect(parseGeoUri('geo:35.6892,51.389;u=20'), (lat: 35.6892, lon: 51.389, acc: 20.0));
    expect(parseGeoUri('geo:-1.5,2,100;crs=wgs84;u=3.5'), (lat: -1.5, lon: 2.0, acc: 3.5));
    expect(parseGeoUri('geo:1,2'), (lat: 1.0, lon: 2.0, acc: null));
    expect(parseGeoUri('https://x'), null);
    expect(parseGeoUri(null), null);
  });

  test('tallyPoll: latest vote wins, spoiled votes erase, max truncates', () {
    final r = tallyPoll([
      const Vote('@a', 1, ['x']),
      const Vote('@a', 2, ['y']), // changed vote
      const Vote('@b', 1, ['x']),
      const Vote('@b', 3, ['nope']), // spoiled: b now has no vote
      const Vote('@c', 1, ['x', 'y']), // single choice: only x counts
      const Vote('@d', 1, 'x'), // malformed
    ], ['x', 'y'], 1);
    expect(r.voters, {'x': ['@c'], 'y': ['@a']});
    expect(r.picks.keys.toList()..sort(), ['@a', '@c']);
  });

  test('formatMessage', () {
    const ali = (name: 'Ali', id: '@ali:hs'), aliR = (name: 'Ali Reza', id: '@ar:hs');
    expect(formatMessage('hi\nthere', [ali]), null);
    final r = formatMessage('@Ali Reza & @Ali\n<b>', [ali, aliR]);
    expect(r?.html, '<a href="https://matrix.to/#/@ar:hs">Ali Reza</a> &#38; <a href="https://matrix.to/#/@ali:hs">Ali</a><br>&#60;b&#62;');
    expect(r?.ids, ['@ar:hs', '@ali:hs']);
  });

  test('formatMessage markdown', () {
    String? h(String s, [List<({String name, String id})> m = const []]) => formatMessage(s, m)?.html;
    expect(h('**b** *i* _j_ ~~s~~ ||x||'), '<strong>b</strong> <em>i</em> <em>j</em> <del>s</del> <span data-mx-spoiler>x</span>');
    expect(h('snake_case_names and 2*3*4'), null);
    expect(h('hi @ali_b:server _x_', [(name: 'ali_b:server', id: '@ali_b:server')]),
        'hi <a href="https://matrix.to/#/@ali_b:server">ali_b:server</a> <em>x</em>');
    expect(h('**hey @Ali**', [(name: 'Ali', id: '@ali:hs')]), '<strong>hey <a href="https://matrix.to/#/@ali:hs">Ali</a></strong>');
    expect(h('`*a* _b_` *c*'), '<code>*a* _b_</code> <em>c</em>');
    expect(h('**<b>**'), '<strong>&#60;b&#62;</strong>');
    expect(h('> a\n> *b*\nc'), '<blockquote>a<br><em>b</em></blockquote>c');
    expect(h('x\n```js\nlet _a_ = 1;\n*z*\n```\ny'), 'x<pre><code>let _a_ = 1;\n*z*</code></pre>y');
    expect(h('[a_b](https://x.io/a_b_c?q=1&r=2)'), '<a href="https://x.io/a_b_c?q=1&#38;r=2">a_b</a>');
    expect(h('[a](javascript:alert(1))'), null);
    expect(h('```@Ali```', [(name: 'Ali', id: '@ali:hs')]), '<pre><code>@Ali</code></pre>');
  });

  test('normalize', () {
    expect(normalize('Hello كتاب ي می‌روم'), 'hello کتاب ی میروم');
    expect(normalize('می‌روم'), normalize('ميروم'));
  });

  test('Farsi room names, invite falls back to the inviter', () {
    expect(roomName(const NameState(name: 'Team')), null);
    expect(roomName(const NameState(names: ['علی'], count: 2)), 'علی');
    expect(roomName(const NameState(names: ['علی', 'سارا'], count: 3)), 'علی و سارا');
    expect(roomName(const NameState(names: ['علی', 'سارا'], count: 6)), 'علی و ۴ نفر دیگر');
    expect(roomName(const NameState(names: ['علی'], count: 2, subtype: 'Inviting')), 'در حال دعوت از علی');
    expect(roomName(const NameState(names: [], count: 3)), 'گفتگوی خالی');
    expect(roomName(const NameState(oldName: 'علی')), 'گفتگوی خالی (قبلاً علی)');
    expect(roomName(const NameState(), '@bob:hs'), '@bob:hs');
  });

  test('shared media: classify content and pull links out of the text, not the reply quote', () {
    String? msg(Map<String, dynamic> c) => mediaKind('m.room.message', c);
    expect([msg({'msgtype': 'm.image'}), msg({'msgtype': 'm.video'}), msg({'msgtype': 'm.file'}), msg({'msgtype': 'm.audio'})], ['media', 'media', 'file', 'audio']);
    expect(mediaKind('m.sticker', {'url': 'mxc://a/b'}), null);
    expect(msg({'msgtype': 'm.text', 'body': 'سلام'}), null);
    expect(msg({'msgtype': 'm.text', 'body': '> <@a:x> see https://old.example\n\nthanks'}), null);
    expect(contentLinks({'body': 'a https://x.org/p?q=1, and (http://y.io) again https://x.org/p?q=1.'}), ['https://x.org/p?q=1', 'http://y.io']);
  });

  test('archive, marked unread and list order', () {
    final base = room(), arch = room(archived: true);
    expect([inFolder(arch, 'all'), inFolder(arch, 'archive'), inFolder(arch, 'dms')], [false, true, false]);
    expect(inArchive(room(archived: true, unread: 2)), false, reason: 'unmuted chat with new messages comes back');
    expect(inArchive(room(archived: true, unread: 2, muted: true)), true, reason: 'muted ones stay archived');
    expect(inFolder(base, 'archive'), false);
    expect(isUnread(room(marked: true)) && inFolder(room(marked: true), 'unread'), true);
    final rows = [Li('old', 1), Li('pin', 0, pinned: true), Li('new', 5), Li('inv', 0, invite: true)];
    expect((rows..sort(byListOrder)).map((r) => r.id), ['inv', 'pin', 'new', 'old']);
  });

  test('gifs: mau flag or gif image; saving moves to the front without duplicates', () {
    expect(isGif({'msgtype': 'm.video', 'info': {'fi.mau.gif': true}}), true);
    expect(isGif({'msgtype': 'm.image', 'info': {'mimetype': 'image/gif'}}), true);
    expect(isGif({'msgtype': 'm.video', 'info': {'mimetype': 'video/mp4'}}), false);
    final Gif a = {'msgtype': 'm.video', 'body': 'a', 'url': 'mxc://hs/a'}, b = {'msgtype': 'm.video', 'body': 'b', 'file': {'url': 'mxc://hs/b'}};
    final list = withGif(withGif(withGif([], a), b), a);
    expect(list.map((g) => g['body']), ['a', 'b']);
    expect(hasGif(list, b) && !hasGif(withoutGif(list, b), b), true);
    expect(List.generate(120, (i) => {...a, 'url': 'mxc://hs/$i'}).fold<List<Gif>>([], withGif).length, 100);
  });

  test('unread divider goes after the last read message and splits its burst', () {
    final msgs = [m('a', '@x', 0), m('b', '@x', 1), m('c', '@x', 2)];
    final rows = buildRows(msgs, T, 'a');
    expect(rows.map((r) => r.type == 'msg' ? '${r.id}${r.first ? 1 : 0}${r.last ? 1 : 0}' : r.type), ['day', 'a11', 'unread', 'b10', 'c01']);
    expect(buildRows(msgs, T, 'c').any((r) => r.type == 'unread'), false); // nothing after it
    expect(buildRows(msgs, T).any((r) => r.type == 'unread'), false);
  });

  test('room admin helpers', () {
    expect([100, 75, 50, 25, 0].map(roleLabel), ['مدیر', 'ناظر', 'ناظر', 'سطح ۲۵', null]);
    expect(['6', '7', '10', 'org.example.v1', '', null].map(supportsKnock), [false, true, true, false, false, false]);
    expect(levelChanges({'users': {'@a': 100, '@b': 50}}, {'users': {'@a': 100, '@c': 50}, 'users_default': 0}),
        [(id: '@b', from: 50, to: 0), (id: '@c', from: 0, to: 50)]);
  });

  // "Megolm key file" test lives with keyfile.ts, which isn't ported here

  test('lastSeen buckets', () {
    int ago(num min) => T - (min * 60000).round();
    expect(lastSeen(true, null, T), 'آنلاین');
    expect(lastSeen(false, null, T), null);
    expect(lastSeen(false, ago(0.2), T), 'چند لحظه پیش');
    expect(lastSeen(false, ago(5), T), 'آخرین بازدید ۵ دقیقه پیش');
    expect(lastSeen(false, ago(90), T), 'آخرین بازدید امروز، ۱۰:۳۰');
    expect(lastSeen(false, ago(24 * 60), T), 'آخرین بازدید دیروز، ۱۲:۰۰');
  });

  test('fitSize downscales the long side and never upscales', () {
    expect(fitSize(4000, 3000), (w: 1280, h: 960));
    expect(fitSize(3000, 4000), (w: 960, h: 1280));
    expect(fitSize(800, 600), (w: 800, h: 600));
  });

  test('textDir: first letter decides, no letters is ltr', () {
    expect(textDir('سلام hello'), 'rtl');
    expect(textDir('hello سلام'), 'ltr');
    expect(textDir('123 سلام'), 'rtl');
    expect(textDir('۱۲۳'), 'ltr');
    expect(textDir('12:30 👍'), 'ltr');
    expect(textDir(''), 'ltr');
  });

  test('isRing: rings only for fresh rings aimed at us or the room', () {
    final ring = {'notification_type': 'ring', 'm.mentions': {'user_ids': ['@me:x']}};
    expect(isRing(ring, 2000, '@me:x', 1000), true);
    expect(isRing(ring, 1000, '@me:x', 1000), false); // expired
    expect(isRing(ring, double.nan, '@me:x', 1000), false); // bad lifetime
    expect(isRing(ring, 2000, '@you:x', 1000), false); // someone else
    expect(isRing({'notification_type': 'ring', 'm.mentions': {'room': true}}, 2000, '@me:x', 1000), true);
    expect(isRing({'notification_type': 'notification', 'm.mentions': {'room': true}}, 2000, '@me:x', 1000), false);
    expect(isRing({'notification_type': 'ring'}, 2000, '@me:x', 1000), false);
  });

  test('isLegacyRing: fresh invites for us or anyone in the room', () {
    expect(isLegacyRing({'lifetime': 60000}, 1000, '@me:x', 2000), true);
    expect(isLegacyRing({'lifetime': 60000}, 1000, '@me:x', 62000), false); // expired
    expect(isLegacyRing({}, 1000, '@me:x', 1000), false); // no lifetime
    expect(isLegacyRing({'lifetime': 60000, 'invitee': '@me:x'}, 1000, '@me:x', 2000), true);
    expect(isLegacyRing({'lifetime': 60000, 'invitee': '@you:x'}, 1000, '@me:x', 2000), false);
    expect(isVideoOffer({'offer': {'sdp': 'v=0\r\nm=audio 9 UDP\r\nm=video 9 UDP\r\n'}}), true);
    expect(isVideoOffer({'offer': {'sdp': 'v=0\r\nm=audio 9 UDP\r\n'}}), false);
  });

  test('isGroupCallAlert: group call starts, not rings', () {
    expect(isGroupCallAlert({'notification_type': 'notification', 'm.mentions': {'room': true}}, 2000, '@me:x', 1000), true);
    expect(isGroupCallAlert({'notification_type': 'ring', 'm.mentions': {'room': true}}, 2000, '@me:x', 1000), false);
    expect(isGroupCallAlert({'notification_type': 'notification', 'm.mentions': {'room': true}}, 1000, '@me:x', 1000), false);
  });

  test('legacyOutcome: answered with duration, declined, missed, still ringing', () {
    const inv = CallEv(type: 'm.call.invite', sender: '@a:x', ts: 1000, content: {'call_id': 'c1', 'lifetime': 60000});
    CallEv ev(String type, int ts, [String callId = 'c1']) => CallEv(type: type, sender: '@b:x', ts: ts, content: {'call_id': callId});
    expect(legacyOutcome(inv, [ev('m.call.answer', 5000), ev('m.call.hangup', 197000)], 300000), (state: 'ended', duration: 192000));
    expect(legacyOutcome(inv, [ev('m.call.answer', 5000)], 300000), (state: 'ongoing', duration: null));
    expect(legacyOutcome(inv, [ev('m.call.reject', 5000)], 300000), (state: 'declined', duration: null));
    expect(legacyOutcome(inv, [ev('m.call.hangup', 5000)], 6000), (state: 'missed', duration: null)); // caller gave up
    expect(legacyOutcome(inv, [], 300000), (state: 'missed', duration: null)); // expired
    expect(legacyOutcome(inv, [ev('m.call.answer', 5000, 'other')], 2000), (state: 'ringing', duration: null)); // another call's events
  });

  test('rtcOutcome: from call memberships after the ring', () {
    const ring = CallEv(id: r'$r', type: 'org.matrix.msc4075.rtc.notification', sender: '@a:x', ts: 1000, content: {});
    CallEv m(String sender, int ts, bool on) => CallEv(type: 'org.matrix.msc3401.call.member', sender: sender, ts: ts, content: on ? {'application': 'm.call'} : {});
    expect(rtcOutcome(ring, 31000, [m('@b:x', 5000, true), m('@a:x', 65000, false)], 100000), (state: 'ended', duration: 60000));
    expect(rtcOutcome(ring, 31000, [m('@b:x', 5000, true)], 100000), (state: 'ongoing', duration: null));
    expect(rtcOutcome(ring, 31000, [const CallEv(type: 'org.matrix.msc4310.rtc.decline', sender: '@b:x', ts: 3000, content: {'m.relates_to': {'event_id': r'$r'}})], 100000), (state: 'declined', duration: null));
    expect(rtcOutcome(ring, 31000, [m('@a:x', 9000, false)], 10000), (state: 'missed', duration: null)); // caller gave up
    expect(rtcOutcome(ring, 31000, [], 100000), (state: 'missed', duration: null));
    expect(rtcOutcome(ring, 31000, [], 2000), (state: 'ringing', duration: null));
    expect(rtcOutcome(ring, 31000, [m('@b:x', 40000, true)], 100000), (state: 'missed', duration: null)); // joined after it stopped ringing
  });

  test('endpointOf names the endpoint for the net log', () {
    expect(endpointOf('https://hs.x/_matrix/client/v3/sync?since=s1&timeout=30000'), 'sync');
    expect(endpointOf('https://hs.x/_matrix/client/versions'), 'versions');
    expect(endpointOf('https://hs.x/_matrix/client/v3/rooms/!a:x/send/m.room.message/1'), 'rooms');
    expect(endpointOf('https://hs.x/_matrix/client/v1/media/thumbnail/x/y?width=96'), 'thumbnail');
    expect(endpointOf('https://hs.x/_matrix/client/unstable/org.matrix.msc3575/sync'), 'org.matrix.msc3575');
  });

  test('links without a scheme: www. and bare domains are found, emails and file names are not', () {
    expect(contentLinks({'body': 'سلام www.example.com/a, و panbeh.ir. یا (example.org:8080/x?y=1) https://x.io'}),
        ['https://www.example.com/a', 'https://panbeh.ir', 'https://example.org:8080/x?y=1', 'https://x.io']);
    expect(contentLinks({'body': 'mail me@example.com, see notes.txt and v1.2.3'}), []);
  });

  test('applyFolderOrder: all first, saved order, new spaces last, stale ids ignored', () {
    final folders = ['all', 'unread', 'dms', 's1', 's2'].map((id) => Folder(id, id)).toList();
    List<String> ids(List<String> order) => applyFolderOrder(folders, order).map((x) => x.id).toList();
    expect(ids([]), ['all', 'unread', 'dms', 's1', 's2']);
    expect(ids(['s1', 'gone', 'dms']), ['all', 's1', 'dms', 'unread', 's2']);
    expect(ids(['dms', 'all']), ['all', 'dms', 'unread', 's1', 's2']);
  });

  test('moveFolder: never touches the first tab', () {
    final ids = ['all', 'a', 'b', 'c'];
    expect(moveFolder(ids, 3, 1), ['all', 'c', 'a', 'b']);
    expect(moveFolder(ids, 1, 3), ['all', 'b', 'c', 'a']);
    expect(moveFolder(ids, 0, 2), same(ids));
    expect(moveFolder(ids, 2, 0), same(ids));
    expect(moveFolder(ids, 2, 9), same(ids));
    expect(moveFolder(ids, 2, 2), same(ids));
  });
}
