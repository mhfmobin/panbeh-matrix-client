import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:matrix/matrix.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../logic.dart';
import '../matrix.dart';
import '../media.dart';
import '../theme.dart';
import 'common.dart';

typedef _Emoji = ({String u, String name, String search});

// emojibase group order (flutter/tool/gen_emoji.mjs); no skin tones
// ponytail: base emoji only; add a tone picker if people ask
const _groups = [
  ('😀', 'شکلک‌ها'),
  ('👋', 'آدم‌ها'),
  ('🐱', 'حیوانات و طبیعت'),
  ('🍔', 'خوراکی‌ها'),
  ('✈️', 'سفر و مکان‌ها'),
  ('⚽', 'فعالیت‌ها'),
  ('💡', 'اشیا'),
  ('❤️', 'نمادها'),
  ('🏳️', 'پرچم‌ها'),
];

Future<List<List<_Emoji>>>? _data;
Future<List<List<_Emoji>>> _load() => _data ??= rootBundle
    .loadString('assets/emoji.json')
    .then(
      (s) => [
        for (final g in jsonDecode(s) as List)
          [
            for (final e in g as List)
              (u: e[0] as String, name: e[1] as String, search: e[2] as String),
          ],
      ],
    )
    .catchError((Object e) {
      _data = null; // retry next time the panel opens
      throw e;
    });

const _recentKey = 'panbeh.recentEmoji';
Future<List<String>> _recent() async =>
    (await SharedPreferences.getInstance()).getStringList(_recentKey) ?? [];
Future<void> _remember(String u) async {
  final p = await SharedPreferences.getInstance();
  await p.setStringList(
    _recentKey,
    [
      u,
      ...(p.getStringList(_recentKey) ?? []).where((x) => x != u),
    ].take(24).toList(),
  );
}

/// Emoji picker in a bottom sheet (reactions); null if dismissed.
Future<String?> pickEmoji(BuildContext context) => showModalBottomSheet<String>(
  context: context,
  isScrollControlled: true,
  backgroundColor: context.tk.panel,
  builder: (c) => SafeArea(
    child: EmojiPanel(
      height: MediaQuery.sizeOf(c).height * .5,
      onEmoji: (e) => Navigator.pop(c, e),
    ),
  ),
);

/// Telegram-style panel: emoji grid with category strip and search, plus saved gifs when `room` is given.
/// Picking doesn't close it.
class EmojiPanel extends StatefulWidget {
  final double height;
  final ValueChanged<String> onEmoji;
  final VoidCallback? onBackspace;
  final Room? room;
  const EmojiPanel({
    super.key,
    required this.height,
    required this.onEmoji,
    this.onBackspace,
    this.room,
  });
  @override
  State<EmojiPanel> createState() => _EmojiPanelState();
}

class _EmojiPanelState extends State<EmojiPanel> {
  var _gif = false;
  @override
  Widget build(BuildContext context) {
    final t = context.tk, room = widget.room;
    return SizedBox(
      height: widget.height,
      child: Column(
        children: [
          Expanded(
            child: _gif && room != null
                ? _GifGrid(room)
                : _EmojiGrid(
                    onPick: (u) {
                      _remember(u);
                      widget.onEmoji(u);
                    },
                  ),
          ),
          if (room != null)
            Container(
              decoration: BoxDecoration(
                border: Border(top: BorderSide(color: t.border)),
              ),
              child: Row(
                children: [
                  const SizedBox(width: 8),
                  for (final (gif, icon, label) in [
                    (false, Icons.emoji_emotions_outlined, 'اموجی'),
                    (true, Icons.gif_box_outlined, 'گیف'),
                  ])
                    IconButton(
                      icon: Icon(icon, color: _gif == gif ? t.accent : t.muted),
                      tooltip: label,
                      onPressed: () => setState(() => _gif = gif),
                    ),
                  const Spacer(),
                  if (widget.onBackspace != null)
                    IconButton(
                      icon: Icon(Icons.backspace_outlined, color: t.muted),
                      onPressed: widget.onBackspace,
                    ),
                  const SizedBox(width: 8),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

class _EmojiGrid extends StatefulWidget {
  final ValueChanged<String> onPick;
  const _EmojiGrid({required this.onPick});
  @override
  State<_EmojiGrid> createState() => _EmojiGridState();
}

class _EmojiGridState extends State<_EmojiGrid> {
  List<List<_Emoji>>? _g;
  var _rec = <String>[];
  var _group = 0; // -1 = recent
  var _q = '';
  var _searching = false;

  @override
  void initState() {
    super.initState();
    _load().then(
      (g) => mounted ? setState(() => _g = g) : null,
      onError: (_) {},
    );
    _recent().then(
      (r) => mounted
          ? setState(() {
              _rec = r;
              if (r.isNotEmpty) _group = -1;
            })
          : null,
    );
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tk, g = _g;
    final term = normalize(_q.trim());
    final byU = g == null
        ? const <String, _Emoji>{}
        : {for (final e in g.expand((x) => x)) e.u: e};
    final List<_Emoji> list = g == null
        ? []
        : term.isNotEmpty
        ? g
              .expand((x) => x)
              .where((e) => e.search.contains(term))
              .take(240)
              .toList()
        : _group == -1
        ? [for (final u in _rec) byU[u] ?? (u: u, name: u, search: '')]
        : g[_group];
    final cats = [
      if (_rec.isNotEmpty) (-1, '🕘', 'اخیر'),
      for (final (i, c) in _groups.indexed) (i, c.$1, c.$2),
    ];
    return Column(
      children: [
        if (_searching)
          Padding(
            padding: const EdgeInsets.fromLTRB(8, 6, 8, 0),
            child: TextField(
              autofocus: true,
              onChanged: (v) => setState(() => _q = v),
              decoration: InputDecoration(
                hintText: 'جستجوی اموجی',
                isDense: true,
                filled: true,
                fillColor: t.hover,
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(20),
                  borderSide: BorderSide.none,
                ),
                suffixIcon: IconButton(
                  icon: const Icon(Icons.close, size: 20),
                  onPressed: () => setState(() {
                    _searching = false;
                    _q = '';
                  }),
                ),
              ),
            ),
          )
        else
          SizedBox(
            height: 44,
            child: Row(
              children: [
                IconButton(
                  icon: Icon(Icons.search, color: t.muted),
                  tooltip: 'جستجوی اموجی',
                  onPressed: () => setState(() => _searching = true),
                ),
                Expanded(
                  child: ListView(
                    scrollDirection: Axis.horizontal,
                    children: [
                      for (final (id, icon, label) in cats)
                        Tooltip(
                          message: label,
                          child: InkResponse(
                            onTap: () => setState(() => _group = id),
                            child: Container(
                              width: 44,
                              alignment: Alignment.center,
                              decoration: BoxDecoration(
                                border: Border(
                                  bottom: BorderSide(
                                    color: id == _group
                                        ? t.accent
                                        : Colors.transparent,
                                    width: 2,
                                  ),
                                ),
                              ),
                              child: Text(
                                icon,
                                style: TextStyle(
                                  fontSize: 22,
                                  color: id == _group
                                      ? null
                                      : t.muted.withValues(alpha: .9),
                                ),
                              ),
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        Expanded(
          child: g == null
              ? const Center(child: CircularProgressIndicator(strokeWidth: 2))
              : list.isEmpty
              ? Center(
                  child: Text(
                    'اموجی‌ای پیدا نشد',
                    style: TextStyle(color: t.muted),
                  ),
                )
              : GridView.builder(
                  padding: const EdgeInsets.all(6),
                  gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                    maxCrossAxisExtent: 46,
                  ),
                  itemCount: list.length,
                  itemBuilder: (_, i) => InkResponse(
                    onTap: () => widget.onPick(list[i].u),
                    radius: 24,
                    child: Center(
                      child: Text(
                        list[i].u,
                        style: const TextStyle(fontSize: 28),
                      ),
                    ),
                  ),
                ),
        ),
      ],
    );
  }
}

// ---------- saved gifs ----------

class _GifGrid extends StatelessWidget {
  final Room room;
  const _GifGrid(this.room);
  @override
  Widget build(BuildContext context) => StreamBuilder(
    stream: client.onSync.stream,
    builder: (context, _) {
      final gifs = savedGifs();
      if (gifs.isEmpty) {
        return Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Text(
              'گیفی ندارید. ویدیو را با گزینه‌ی «ارسال به صورت گیف» بفرستید یا گیف دیگران را ذخیره کنید.',
              textAlign: TextAlign.center,
              style: TextStyle(color: context.tk.muted),
            ),
          ),
        );
      }
      return GridView.builder(
        padding: const EdgeInsets.all(4),
        gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: 3,
          mainAxisSpacing: 4,
          crossAxisSpacing: 4,
        ),
        itemCount: gifs.length,
        itemBuilder: (_, i) => GestureDetector(
          onTap: () => sendGif(room, gifs[i]),
          child: _GifThumb(gifs[i]),
        ),
      );
    },
  );
}

/// ponytail: still frame only (images, or the video's thumbnail); no video playback in the grid
class _GifThumb extends StatelessWidget {
  final Map<String, dynamic> g;
  const _GifThumb(this.g);
  @override
  Widget build(BuildContext context) {
    final info = g['info'] is Map ? g['info'] as Map : const {};
    final url = g['msgtype'] == 'm.image' ? g['url'] : info['thumbnail_url'];
    final mxc = url is String ? Uri.tryParse(url) : null;
    final box = ColoredBox(
      color: context.tk.hover,
      child: Icon(Icons.gif, color: context.tk.muted, size: 32),
    );
    if (mxc == null) return box;
    return ClipRRect(
      borderRadius: BorderRadius.circular(6),
      child: FutureBuilder<Uint8List?>(
        future: loadMxc(mxc, size: 256),
        builder: (_, s) => s.data == null
            ? box
            : Image.memory(s.data!, fit: BoxFit.cover, gaplessPlayback: true),
      ),
    );
  }
}
