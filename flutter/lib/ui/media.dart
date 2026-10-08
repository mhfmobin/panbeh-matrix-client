import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import 'package:fc_native_video_thumbnail/fc_native_video_thumbnail.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:geolocator/geolocator.dart';
import 'package:image_picker/image_picker.dart';
import 'package:matrix/matrix.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:video_player/video_player.dart';

import '../logic.dart';
import '../media.dart';
import '../theme.dart';
import 'common.dart';
import 'message.dart' show MenuItem, MsgActions;

// ---------- helpers ----------

/// A media message's caption (MSC2530): body differs from filename; otherwise body is just the name.
String captionOf(Map c) => c['filename'] != null && c['body'] != c['filename'] ? '${c['body'] ?? ''}' : '';

bool _isVisual(Event e) =>
    !e.redacted && e.hasAttachment && (e.type == EventTypes.Sticker || (e.type == EventTypes.Message && (e.content['msgtype'] == 'm.image' || e.content['msgtype'] == 'm.video')));

({double width, double height}) _fit(Map c, double max) {
  final info = c['info'] as Map?;
  final w = (info?['w'] as num?)?.toDouble() ?? max, h = (info?['h'] as num?)?.toDouble() ?? max * .75;
  final k = [1.0, max / w, 320 / h].reduce((a, b) => a < b ? a : b);
  return (width: (w * k).clamp(90, max), height: (h * k).clamp(60, 320));
}

String _pct(int loaded, int total) => '${faNum((loaded * 100 / total).floor())}٪ · ${formatSize(loaded)} / ${formatSize(total)}';

Widget _placeholder(BuildContext context) => ColoredBox(color: context.tk.hover);

// ---------- bubbles ----------

typedef MediaContent = ({Widget widget, String caption, bool visual, double width});

/// What a media message shows inside its bubble (null: not one, show the preview text). `visual`: edge-to-edge
/// picture, with the time over it when there is no caption.
MediaContent? mediaContent(BuildContext context, Timeline tl, Event ev, Event disp, MsgActions actions) {
  final c = disp.content, sticker = ev.type == EventTypes.Sticker;
  final type = sticker ? 'm.image' : c['msgtype'];
  final cap = sticker ? '' : captionOf(c);
  switch (type) {
    case 'm.image' || 'm.video':
      final s = _fit(c, sticker ? 180 : 260);
      return (widget: _Visual(ev, disp, tl, actions.jump, s.width, s.height), caption: cap, visual: true, width: s.width);
    case 'm.file':
      return (widget: _FileRow(ev, disp), caption: cap, visual: false, width: 0);
    case 'm.location':
      final w = _Location(disp);
      return w.geo == null ? null : (widget: w, caption: '', visual: false, width: 0);
  }
  return null;
}

class _Visual extends StatefulWidget {
  final Event ev, disp;
  final Timeline tl;
  final void Function(String) jump;
  final double w, h;
  const _Visual(this.ev, this.disp, this.tl, this.jump, this.w, this.h);
  @override
  State<_Visual> createState() => _VisualState();
}

class _VisualState extends State<_Visual> {
  Uint8List? _bytes;
  late final _gif = isGif(widget.disp.content), _video = widget.disp.content['msgtype'] == 'm.video';

  @override
  void initState() {
    super.initState();
    // an animated image plays from its own bytes; everything else starts from the small preview
    (_gif && !_video ? imageBytes(widget.ev) : previewBytes(widget.ev)).then<void>((b) { if (mounted && b != null) setState(() => _bytes = b); }, onError: (_) {});
  }

  @override
  Widget build(BuildContext context) {
    final ev = widget.ev, c = widget.disp.content, pending = !ev.status.isSynced && !ev.hasAttachment;
    final dpr = MediaQuery.devicePixelRatioOf(context);
    final dur = (c['info'] as Map?)?['duration'];
    final box = ClipRRect(
      borderRadius: BorderRadius.circular(13),
      child: SizedBox(width: widget.w, height: widget.h, child: Stack(fit: StackFit.expand, children: [
        _placeholder(context),
        if (_bytes != null) Image.memory(_bytes!, fit: BoxFit.cover, gaplessPlayback: true, cacheWidth: _gif && !_video ? null : (widget.w * dpr).round()),
        if (_gif && _video) _GifVideo(ev),
        if (_video && !_gif) const Center(child: _Badge(round: true, child: Icon(Icons.play_arrow, color: Colors.white, size: 30))),
        if (_video && !_gif && dur is num && dur > 0)
          PositionedDirectional(top: 6, start: 6, child: _Badge(child: Text(fmtDuration(dur), style: const TextStyle(color: Colors.white, fontSize: 12)))),
        if (_gif) const PositionedDirectional(top: 6, start: 6, child: _Badge(child: Text('GIF', style: TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.w600)))),
        if (pending || ev.status.isError) Positioned.fill(child: _UploadOverlay(ev)),
      ])),
    );
    if (pending || ev.status.isError) return box;
    return GestureDetector(
      onTap: () => openMediaViewer(context, widget.tl, ev, widget.jump),
      child: Hero(tag: 'media-${ev.eventId}', child: box),
    );
  }
}

class _Badge extends StatelessWidget {
  final Widget child;
  final bool round;
  const _Badge({required this.child, this.round = false});
  @override
  Widget build(BuildContext context) => Container(
    padding: round ? const EdgeInsets.all(10) : const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
    decoration: BoxDecoration(color: Colors.black54, shape: round ? BoxShape.circle : BoxShape.rectangle, borderRadius: round ? null : BorderRadius.circular(8)),
    child: child,
  );
}

/// A gif video: downloads once, then plays itself, looped and muted.
class _GifVideo extends StatefulWidget {
  final Event ev;
  const _GifVideo(this.ev);
  @override
  State<_GifVideo> createState() => _GifVideoState();
}

class _GifVideoState extends State<_GifVideo> {
  VideoPlayerController? _c;
  bool _dead = false;

  @override
  void initState() {
    super.initState();
    videoPath(widget.ev).then((p) async {
      final c = VideoPlayerController.file(File(p));
      if (_dead) return c.dispose();
      await c.initialize();
      if (_dead) return c.dispose();
      await c.setLooping(true);
      await c.setVolume(0);
      await c.play();
      setState(() => _c = c);
    }).catchError((_) {});
  }

  @override
  void dispose() {
    _dead = true;
    _c?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final c = _c;
    return c == null ? const SizedBox.shrink() : FittedBox(fit: BoxFit.cover, clipBehavior: Clip.hardEdge, child: SizedBox(width: c.value.size.width, height: c.value.size.height, child: VideoPlayer(c)));
  }
}

/// A pending (or failed) upload: the stage it's in with ✕, or retry + ✕. The SDK reports stages, not bytes.
class _UploadOverlay extends StatelessWidget {
  final Event ev;
  const _UploadOverlay(this.ev);

  Widget _btn(IconData i, VoidCallback f) => GestureDetector(onTap: f, child: Container(
    width: 46, height: 46, decoration: const BoxDecoration(color: Colors.black54, shape: BoxShape.circle), child: Icon(i, color: Colors.white)));

  @override
  Widget build(BuildContext context) {
    final err = ev.status.isError;
    final label = err ? 'ارسال نشد' : switch (ev.fileSendingStatus) {
      FileSendingStatus.generatingThumbnail => 'آماده‌سازی…',
      FileSendingStatus.encrypting => 'رمزنگاری…',
      _ => 'در حال ارسال…',
    };
    final cancel = _btn(Icons.close, () => attempt(context, () => cancelUpload(ev)));
    return Container(color: Colors.black26, alignment: Alignment.center, child: Column(mainAxisSize: MainAxisSize.min, children: [
      if (err) Row(mainAxisSize: MainAxisSize.min, children: [_btn(Icons.refresh, () => attempt(context, () async { await ev.sendAgain(); })), const SizedBox(width: 12), cancel])
      else SizedBox(width: 46, height: 46, child: Stack(alignment: Alignment.center, children: [
        const SizedBox(width: 46, height: 46, child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white)),
        _btn(Icons.close, () => attempt(context, () => cancelUpload(ev))),
      ])),
      const SizedBox(height: 6),
      _Badge(child: Text(label, style: const TextStyle(color: Colors.white, fontSize: 12))),
    ]));
  }
}

class _FileRow extends StatefulWidget {
  final Event ev, disp;
  const _FileRow(this.ev, this.disp);
  @override
  State<_FileRow> createState() => _FileRowState();
}

class _FileRowState extends State<_FileRow> {
  int? _loaded;

  Future<void> _download() async {
    if (_loaded != null || !widget.ev.hasAttachment) return;
    setState(() => _loaded = 0);
    try {
      await saveEvent(widget.ev, onProgress: (n) { if (mounted) setState(() => _loaded = n); });
      if (mounted) toast(context, 'در پوشه‌ی دانلودها ذخیره شد');
    } catch (e) {
      if (mounted) alert(context, errText(e));
    }
    if (mounted) setState(() => _loaded = null);
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tk, ev = widget.ev, c = widget.disp.content;
    final mine = ev.senderId == me(), fg = mine ? t.outText : t.text, sub = mine ? t.outMeta : t.muted;
    final pending = !ev.status.isSynced && !ev.hasAttachment, err = ev.status.isError;
    final size = ((c['info'] as Map?)?['size'] as num?)?.toInt() ?? 0, loaded = _loaded;
    final state = err ? 'ارسال نشد' : pending ? 'در حال ارسال…'
        : loaded == null ? (size > 0 ? formatSize(size) : 'فایل')
        : size > 0 ? _pct(loaded, size) : 'در حال دانلود…';
    final disc = Container(width: 48, height: 48, decoration: BoxDecoration(color: mine ? t.outText.withValues(alpha: .2) : t.accent, shape: BoxShape.circle));
    final icon = pending || err
        ? GestureDetector(onTap: () => attempt(context, () => cancelUpload(ev)), child: Stack(alignment: Alignment.center, children: [
            disc, if (!err) const SizedBox(width: 48, height: 48, child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white)), const Icon(Icons.close, color: Colors.white)]))
        : Stack(alignment: Alignment.center, children: [
            disc,
            if (loaded != null) SizedBox(width: 48, height: 48, child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white, value: size > 0 ? (loaded / size).clamp(0.0, 1.0) : null)),
            Icon(loaded != null ? Icons.arrow_downward : Icons.insert_drive_file_outlined, color: Colors.white),
          ]);
    return GestureDetector(
      onTap: _download,
      child: Container(constraints: const BoxConstraints(minWidth: 200), padding: const EdgeInsets.fromLTRB(0, 2, 0, 2), child: Row(mainAxisSize: MainAxisSize.min, children: [
        icon,
        const SizedBox(width: 10),
        Flexible(child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
          Text(bdi(fileNameOf(c)), maxLines: 2, overflow: TextOverflow.ellipsis, style: TextStyle(color: fg, fontWeight: FontWeight.w600, fontSize: 15)),
          Text(state, style: TextStyle(color: err ? const Color(0xffe53935) : sub, fontSize: 13)),
        ])),
      ])),
    );
  }
}

class _Location extends StatelessWidget {
  final Event ev;
  late final ({double lat, double lon, double? acc})? geo;
  late final String? desc;
  _Location(this.ev) {
    final c = ev.content, loc = (c['m.location'] ?? c['org.matrix.msc3488.location']) as Map?;
    geo = parseGeoUri(loc?['uri'] ?? c['geo_uri']);
    desc = loc?['description'] as String?;
  }

  Widget _link(String label, String url, Color color) => GestureDetector(
    onTap: () => launchUrl(Uri.parse(url), mode: LaunchMode.externalApplication).catchError((_) => false),
    child: Text(label, style: TextStyle(color: color, fontSize: 13, decoration: TextDecoration.underline, decorationColor: color)),
  );

  @override
  Widget build(BuildContext context) {
    final t = context.tk, mine = ev.senderId == me(), fg = mine ? t.outText : t.text, sub = mine ? t.outMeta : t.muted, g = geo!;
    final link = mine ? t.outText : t.accent;
    return Container(constraints: const BoxConstraints(minWidth: 200), child: Row(mainAxisSize: MainAxisSize.min, children: [
      Container(width: 48, height: 48, decoration: BoxDecoration(color: mine ? t.outText.withValues(alpha: .2) : t.accent, shape: BoxShape.circle), child: const Icon(Icons.location_on, color: Colors.white)),
      const SizedBox(width: 10),
      Flexible(child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
        Text(bdi(desc != null && desc!.isNotEmpty ? desc! : 'موقعیت مکانی'), style: TextStyle(color: fg, fontWeight: FontWeight.w600, fontSize: 15)),
        Text('${g.lat.toStringAsFixed(5)}, ${g.lon.toStringAsFixed(5)}${g.acc != null ? ' · دقت ${faNum(g.acc!.round())} متر' : ''}',
          textDirection: TextDirection.ltr, style: TextStyle(color: sub, fontSize: 12.5)),
        const SizedBox(height: 4),
        Wrap(spacing: 12, children: [
          _link('نقشه', osmUrl(g.lat, g.lon), link),
          _link('Google Maps', 'https://www.google.com/maps?q=${g.lat},${g.lon}', link),
        ]),
      ])),
    ]));
  }
}

// ---------- message menu ----------

/// «ذخیره در دانلودها» for anything with a file, and «ذخیره‌ی گیف» / «حذف از گیف‌ها» for gifs.
List<MenuItem> mediaMenu(BuildContext context, Event ev, Event disp) {
  final c = disp.content, file = ev.hasAttachment && (ev.type == EventTypes.Sticker || const ['m.image', 'm.video', 'm.file'].contains(c['msgtype']));
  return [
    if (file) MenuItem(Icons.download_outlined, 'ذخیره در دانلودها', () async {
      try {
        await saveEvent(ev);
        if (context.mounted) toast(context, 'در پوشه‌ی دانلودها ذخیره شد');
      } catch (e) {
        if (context.mounted) alert(context, errText(e));
      }
    }),
    if (file && isGif(c)) isSavedGif(c)
        ? MenuItem(Icons.close, 'حذف از گیف‌ها', () => attempt(context, () => toggleGif(c)))
        : MenuItem(Icons.gif_box_outlined, 'ذخیره‌ی گیف', () async {
            try {
              await toggleGif(c);
              if (context.mounted) toast(context, 'به گیف‌ها اضافه شد');
            } catch (e) {
              if (context.mounted) alert(context, errText(e));
            }
          }),
  ];
}

// ---------- viewer ----------

/// Full-screen gallery over the room's loaded images and videos (oldest first; RTL puts the newer one on the left).
void openMediaViewer(BuildContext context, Timeline tl, Event start, void Function(String) jump) {
  final list = [for (final e in tl.events.reversed) if (_isVisual(e)) e];
  final items = list.any((e) => e.eventId == start.eventId) ? list : [start];
  Navigator.push(context, PageRouteBuilder<void>(
    opaque: false, transitionDuration: const Duration(milliseconds: 220), reverseTransitionDuration: const Duration(milliseconds: 180),
    pageBuilder: (_, _, _) => _Viewer(items, items.indexWhere((e) => e.eventId == start.eventId), jump),
    transitionsBuilder: (_, a, _, child) => FadeTransition(opacity: a, child: child),
  ));
}

class _Viewer extends StatefulWidget {
  final List<Event> items;
  final int start;
  final void Function(String) jump;
  const _Viewer(this.items, this.start, this.jump);
  @override
  State<_Viewer> createState() => _ViewerState();
}

class _ViewerState extends State<_Viewer> {
  late final _pc = PageController(initialPage: widget.start);
  late int _i = widget.start;
  bool _chrome = true, _zoomed = false;
  double _dy = 0;

  @override
  void dispose() {
    _pc.dispose();
    super.dispose();
  }

  Event get _ev => widget.items[_i];

  Future<void> _save() async {
    try {
      await saveEvent(_ev);
      if (mounted) toast(context, 'در پوشه‌ی دانلودها ذخیره شد');
    } catch (e) {
      if (mounted) alert(context, errText(e));
    }
  }

  @override
  Widget build(BuildContext context) {
    final ev = _ev, c = ev.content, video = c['msgtype'] == 'm.video' && !isGif(c), cap = ev.type == EventTypes.Sticker ? '' : captionOf(c);
    final n = widget.items.length;
    final bar = IgnorePointer(ignoring: !_chrome, child: AnimatedOpacity(opacity: _chrome ? 1 : 0, duration: const Duration(milliseconds: 150), child: Container(
      decoration: const BoxDecoration(gradient: LinearGradient(begin: Alignment.topCenter, end: Alignment.bottomCenter, colors: [Colors.black54, Colors.transparent])),
      child: SafeArea(bottom: false, child: Row(children: [
        IconButton(icon: const Icon(Icons.close, color: Colors.white), tooltip: 'بستن', onPressed: () => Navigator.pop(context)),
        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(senderName(ev), maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w600, fontSize: 16)),
          Text('${stamp(ev.originServerTs.millisecondsSinceEpoch)}${n > 1 ? ' · ${faNum(_i + 1)} از ${faNum(n)}' : ''}', style: const TextStyle(color: Colors.white70, fontSize: 12.5)),
        ])),
        IconButton(icon: const Icon(Icons.chat_bubble_outline, color: Colors.white), tooltip: 'نمایش در گفتگو', onPressed: () { Navigator.pop(context); widget.jump(ev.eventId); }),
        IconButton(icon: const Icon(Icons.download, color: Colors.white), tooltip: 'دانلود', onPressed: _save),
      ])),
    )));
    return Scaffold(
      backgroundColor: Colors.black.withValues(alpha: (1 - _dy.abs() / 400).clamp(0.0, 1.0)),
      body: GestureDetector(
        // a zoomed picture pans instead: no recogniser at all then, so it can't win the arena
        onVerticalDragUpdate: _zoomed ? null : (d) => setState(() => _dy += d.delta.dy),
        onVerticalDragEnd: _zoomed ? null : (d) {
          if (_dy.abs() > 120 || (d.primaryVelocity ?? 0).abs() > 800) {
            Navigator.pop(context);
          } else {
            setState(() => _dy = 0);
          }
        },
        child: Transform.translate(offset: Offset(0, _dy), child: Stack(children: [
          PageView.builder(
            controller: _pc, itemCount: n, physics: _zoomed ? const NeverScrollableScrollPhysics() : null,
            onPageChanged: (i) => setState(() { _i = i; _zoomed = false; _chrome = true; }),
            itemBuilder: (_, i) {
              final e = widget.items[i], isVideo = e.content['msgtype'] == 'm.video';
              return isVideo
                  ? _VideoPage(key: ValueKey(e.eventId), ev: e, chrome: _chrome, onTap: () => setState(() => _chrome = !_chrome))
                  : _ImagePage(key: ValueKey(e.eventId), ev: e, onTap: () => setState(() => _chrome = !_chrome), onZoom: (z) { if (z != _zoomed) setState(() => _zoomed = z); });
            },
          ),
          Positioned(top: 0, left: 0, right: 0, child: bar),
          if (cap.isNotEmpty) Positioned(left: 0, right: 0, bottom: video ? 96 : 0, child: IgnorePointer(child: AnimatedOpacity(opacity: _chrome ? 1 : 0, duration: const Duration(milliseconds: 150), child: Container(
            color: Colors.black54, padding: EdgeInsets.fromLTRB(16, 10, 16, 10 + MediaQuery.paddingOf(context).bottom * (video ? 0 : 1)),
            child: Text(cap, textAlign: TextAlign.center, style: const TextStyle(color: Colors.white, fontSize: 15)),
          )))),
        ])),
      ),
    );
  }
}

class _ImagePage extends StatefulWidget {
  final Event ev;
  final VoidCallback onTap;
  final ValueChanged<bool> onZoom;
  const _ImagePage({super.key, required this.ev, required this.onTap, required this.onZoom});
  @override
  State<_ImagePage> createState() => _ImagePageState();
}

class _ImagePageState extends State<_ImagePage> {
  final _tc = TransformationController();
  Uint8List? _thumb, _full;
  Offset _at = Offset.zero;
  bool _failed = false;

  @override
  void initState() {
    super.initState();
    _tc.addListener(() => widget.onZoom(_tc.value.getMaxScaleOnAxis() > 1.01));
    previewBytes(widget.ev).then<void>((b) { if (mounted && _full == null) setState(() => _thumb = b); }, onError: (_) {});
    imageBytes(widget.ev).then<void>((b) { if (mounted) setState(() => _full = b); }, onError: (_) { if (mounted) setState(() => _failed = true); });
  }

  @override
  void dispose() {
    _tc.dispose();
    super.dispose();
  }

  // ponytail: double-tap jumps to 2.5x under the finger, no animation
  void _double() => _tc.value = _tc.value.getMaxScaleOnAxis() > 1.01 ? Matrix4.identity() : (Matrix4.identity()..translateByDouble(-_at.dx * 1.5, -_at.dy * 1.5, 0, 1)..scaleByDouble(2.5, 2.5, 1, 1));

  @override
  Widget build(BuildContext context) {
    final b = _full ?? _thumb;
    return GestureDetector(
      onTap: widget.onTap, onDoubleTapDown: (d) => _at = d.localPosition, onDoubleTap: _double,
      child: InteractiveViewer(
        transformationController: _tc, minScale: 1, maxScale: 6,
        child: SizedBox.expand(child: Center(child: b == null
            ? (_failed ? const Icon(Icons.broken_image_outlined, color: Colors.white54, size: 48) : const CircularProgressIndicator(color: Colors.white))
            : Hero(tag: 'media-${widget.ev.eventId}', child: Image.memory(b, fit: BoxFit.contain, gaplessPlayback: true)))),
      ),
    );
  }
}

const _speeds = [1.0, 1.5, 2.0, 0.5];

class _VideoPage extends StatefulWidget {
  final Event ev;
  final bool chrome;
  final VoidCallback onTap;
  const _VideoPage({super.key, required this.ev, required this.chrome, required this.onTap});
  @override
  State<_VideoPage> createState() => _VideoPageState();
}

class _VideoPageState extends State<_VideoPage> {
  VideoPlayerController? _c;
  Uint8List? _poster;
  int _loaded = 0;
  bool _dead = false, _failed = false, _muted = false;
  double _speed = 1;

  late final _total = ((widget.ev.content['info'] as Map?)?['size'] as num?)?.toInt() ?? 0;

  @override
  void initState() {
    super.initState();
    previewBytes(widget.ev).then<void>((b) { if (mounted) setState(() => _poster = b); }, onError: (_) {});
    videoPath(widget.ev, onProgress: (n) { if (mounted) setState(() => _loaded = n); }).then((p) async {
      final c = VideoPlayerController.file(File(p));
      if (_dead) return c.dispose();
      await c.initialize();
      if (_dead) return c.dispose();
      c.addListener(() { if (mounted) setState(() {}); });
      await c.play();
      setState(() => _c = c);
    }).catchError((_) { if (mounted) setState(() => _failed = true); });
  }

  @override
  void dispose() {
    _dead = true;
    _c?.dispose();
    super.dispose();
  }

  void _toggle() {
    final c = _c;
    if (c == null) return;
    c.value.isPlaying ? c.pause() : c.play();
  }

  @override
  Widget build(BuildContext context) {
    final c = _c, v = c?.value;
    final ms = v?.duration.inMilliseconds ?? 0, pos = (v?.position.inMilliseconds ?? 0).clamp(0, ms == 0 ? 1 : ms);
    final show = widget.chrome || v?.isPlaying != true;
    return GestureDetector(
      behavior: HitTestBehavior.opaque, onTap: widget.onTap,
      child: Stack(fit: StackFit.expand, children: [
        if (c == null) ...[
          if (_poster != null) Image.memory(_poster!, fit: BoxFit.contain),
          Center(child: _failed ? const Icon(Icons.error_outline, color: Colors.white54, size: 48)
              : CircularProgressIndicator(color: Colors.white, value: _total > 0 && _loaded > 0 ? (_loaded / _total).clamp(0.0, 1.0) : null)),
        ] else ...[
          Center(child: AspectRatio(aspectRatio: v!.aspectRatio == 0 ? 16 / 9 : v.aspectRatio, child: VideoPlayer(c))),
          if (!v.isPlaying) Center(child: GestureDetector(onTap: _toggle, child: const _Badge(round: true, child: Icon(Icons.play_arrow, color: Colors.white, size: 40)))),
          Positioned(left: 0, right: 0, bottom: 0, child: IgnorePointer(ignoring: !show, child: AnimatedOpacity(opacity: show ? 1 : 0, duration: const Duration(milliseconds: 150), child: Directionality(
            textDirection: TextDirection.ltr,
            child: Container(
              color: Colors.black54, padding: EdgeInsets.only(bottom: MediaQuery.paddingOf(context).bottom + 4, top: 4),
              child: Row(children: [
                IconButton(icon: Icon(v.isPlaying ? Icons.pause : Icons.play_arrow, color: Colors.white), onPressed: _toggle),
                Text(fmtDuration(pos), style: const TextStyle(color: Colors.white, fontSize: 12)),
                Expanded(child: Slider(value: pos.toDouble(), max: ms == 0 ? 1 : ms.toDouble(), onChanged: (x) => c.seekTo(Duration(milliseconds: x.round())))),
                Text(fmtDuration(ms), style: const TextStyle(color: Colors.white, fontSize: 12)),
                TextButton(
                  onPressed: () { _speed = _speeds[(_speeds.indexOf(_speed) + 1) % _speeds.length]; c.setPlaybackSpeed(_speed); setState(() {}); },
                  child: Text('${faDigits(_speed == _speed.truncate() ? '${_speed.toInt()}' : '$_speed')}×', style: const TextStyle(color: Colors.white)),
                ),
                IconButton(icon: Icon(_muted ? Icons.volume_off : Icons.volume_up, color: Colors.white), onPressed: () { _muted = !_muted; c.setVolume(_muted ? 0 : 1); setState(() {}); }),
              ]),
            ),
          )))),
        ],
      ]),
    );
  }
}

// ---------- attach ----------

typedef _Send = ({List<Picked> files, String caption, bool asFile, bool asGif});

/// 📎 in the composer: gallery, camera, file, poll, location. `replyTo` rides on the first file; `onSent` clears the composer's reply.
class AttachButton extends StatelessWidget {
  final Room room;
  final Event? replyTo;
  final VoidCallback onSent;
  final VoidCallback? onPoll;
  const AttachButton({super.key, required this.room, this.replyTo, required this.onSent, this.onPoll});

  Future<void> _review(BuildContext context, List<Picked> files) async {
    if (files.isEmpty || !context.mounted) return;
    final r = await Navigator.push<_Send>(context, MaterialPageRoute(fullscreenDialog: true, builder: (_) => _SendPage(files)));
    if (r == null || !context.mounted) return;
    sendMedia(room, r.files, caption: r.caption, asFile: r.asFile, asGif: r.asGif, replyTo: replyTo, onError: (e) { if (context.mounted) alert(context, errText(e)); });
    onSent();
  }

  Future<void> _run(BuildContext context, Future<List<Picked>> Function() pick) async {
    try {
      final files = await pick();
      if (!context.mounted) return;
      await _review(context, files);
    } catch (e) {
      if (context.mounted) alert(context, errText(e));
    }
  }

  Future<List<Picked>> _gallery() async => [for (final x in await ImagePicker().pickMultipleMedia()) Picked(x.path, x.name, x.mimeType)];

  Future<List<Picked>> _camera(BuildContext context) async {
    final video = await showModalBottomSheet<bool>(context: context, builder: (c) => SafeArea(child: Column(mainAxisSize: MainAxisSize.min, children: [
      ListTile(leading: const Icon(Icons.photo_camera_outlined), title: const Text('عکس'), onTap: () => Navigator.pop(c, false)),
      ListTile(leading: const Icon(Icons.videocam_outlined), title: const Text('ویدیو'), onTap: () => Navigator.pop(c, true)),
    ])));
    if (video == null) return [];
    final x = video ? await ImagePicker().pickVideo(source: ImageSource.camera) : await ImagePicker().pickImage(source: ImageSource.camera);
    return x == null ? [] : [Picked(x.path, x.name, x.mimeType)];
  }

  Future<List<Picked>> _files() async => [for (final f in await FilePicker.pickFiles()) if (f.path != null) Picked(f.path!, f.name, null)];

  Future<void> _location(BuildContext context) async {
    try {
      var perm = await Geolocator.checkPermission();
      if (perm == LocationPermission.denied) perm = await Geolocator.requestPermission();
      if (perm == LocationPermission.denied || perm == LocationPermission.deniedForever) {
        if (context.mounted) alert(context, 'اجازه‌ی دسترسی به موقعیت مکانی داده نشد.');
        return;
      }
      if (!await Geolocator.isLocationServiceEnabled()) {
        if (context.mounted) alert(context, 'موقعیت مکانی دستگاه خاموش است.');
        return;
      }
      if (context.mounted) toast(context, 'در حال یافتن موقعیت…');
      final p = await Geolocator.getCurrentPosition(locationSettings: const LocationSettings(accuracy: LocationAccuracy.high, timeLimit: Duration(seconds: 15)));
      if (!context.mounted) return;
      if (!await confirm(context, 'موقعیت فعلی شما (با دقت حدود ${faNum(p.accuracy.round())} متر) ارسال شود؟', ok: 'ارسال') || !context.mounted) return;
      await sendLocation(room, p.latitude, p.longitude, p.accuracy.round(), replyTo: replyTo);
      onSent();
    } on TimeoutException {
      if (context.mounted) alert(context, 'موقعیت مکانی پیدا نشد.');
    } catch (e) {
      if (context.mounted) alert(context, errText(e));
    }
  }

  void _sheet(BuildContext context) {
    Widget tile(IconData icon, Color color, String label, VoidCallback run) => InkWell(
      onTap: () { Navigator.pop(context); run(); },
      child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
        CircleAvatar(radius: 28, backgroundColor: color, child: Icon(icon, color: Colors.white, size: 28)),
        const SizedBox(height: 6),
        Text(label, style: const TextStyle(fontSize: 13)),
      ]),
    );
    showModalBottomSheet<void>(context: context, builder: (_) => SafeArea(child: Padding(padding: const EdgeInsets.all(16), child: GridView.count(
      crossAxisCount: 3, shrinkWrap: true, mainAxisSpacing: 16, childAspectRatio: 1.1,
      children: [
        tile(Icons.photo_outlined, const Color(0xff7e57c2), 'گالری', () => _run(context, _gallery)),
        tile(Icons.photo_camera_outlined, const Color(0xffef5350), 'دوربین', () => _run(context, () => _camera(context))),
        tile(Icons.insert_drive_file_outlined, const Color(0xff42a5f5), 'فایل', () => _run(context, _files)),
        tile(Icons.poll_outlined, const Color(0xffffa726), 'نظرسنجی', onPoll ?? () => toast(context, 'به‌زودی')),
        tile(Icons.location_on_outlined, const Color(0xff66bb6a), 'موقعیت مکانی', () => _location(context)),
      ],
    ))));
  }

  @override
  Widget build(BuildContext context) =>
      IconButton(icon: Icon(Icons.attach_file, color: context.tk.muted), tooltip: 'پیوست', onPressed: () => _sheet(context));
}

/// Full-screen "send these" page: thumbnails, caption, and the two switches (the web app's SendFiles).
class _SendPage extends StatefulWidget {
  final List<Picked> files;
  const _SendPage(this.files);
  @override
  State<_SendPage> createState() => _SendPageState();
}

class _SendPageState extends State<_SendPage> {
  late final _files = [...widget.files];
  final _caption = TextEditingController();
  bool _asFile = false, _asGif = false;

  @override
  void dispose() {
    _caption.dispose();
    super.dispose();
  }

  void _send() => Navigator.pop<_Send>(context, (files: _files, caption: _caption.text.trim(), asFile: _asFile, asGif: _asGif));

  @override
  Widget build(BuildContext context) {
    final t = context.tk;
    final title = _files.length == 1 && _files[0].image ? 'ارسال عکس' : 'ارسال ${faNum(_files.length)} فایل';
    return Scaffold(
      appBar: AppBar(title: Text(title)),
      body: Column(children: [
        Expanded(child: GridView.count(
          padding: const EdgeInsets.all(8), crossAxisCount: _files.length == 1 ? 1 : 2, mainAxisSpacing: 8, crossAxisSpacing: 8,
          childAspectRatio: _files.length == 1 ? 1.2 : 1,
          children: [for (final f in _files) _PickTile(f, () { setState(() => _files.remove(f)); if (_files.isEmpty) Navigator.pop(context); })],
        )),
        if (_files.any((f) => f.image)) SwitchListTile(title: const Text('ارسال به صورت فایل'), subtitle: const Text('بدون فشرده‌سازی'), value: _asFile, onChanged: (v) => setState(() => _asFile = v)),
        if (_files.any((f) => f.video)) SwitchListTile(title: const Text('ارسال به صورت گیف'), subtitle: const Text('پخش خودکار و تکرار، بدون صدا'), value: _asGif, onChanged: (v) => setState(() => _asGif = v)),
        Material(color: t.panel, child: SafeArea(top: false, child: Padding(padding: const EdgeInsets.fromLTRB(12, 6, 8, 6), child: Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
          Expanded(child: TextField(controller: _caption, minLines: 1, maxLines: 5, decoration: InputDecoration(hintText: 'کپشن…', hintStyle: TextStyle(color: t.muted), border: InputBorder.none))),
          IconButton.filled(
            style: IconButton.styleFrom(backgroundColor: t.accent, foregroundColor: Colors.white),
            icon: const Icon(Icons.send, textDirection: TextDirection.ltr), tooltip: 'ارسال', onPressed: _send),
        ])))),
      ]),
    );
  }
}

class _PickTile extends StatelessWidget {
  final Picked f;
  final VoidCallback remove;
  const _PickTile(this.f, this.remove);

  @override
  Widget build(BuildContext context) {
    final t = context.tk;
    final Widget body;
    if (f.image) {
      body = Image.file(File(f.path), fit: BoxFit.cover, cacheWidth: 600, errorBuilder: (_, _, _) => const Icon(Icons.broken_image_outlined));
    } else if (f.video) {
      body = Stack(fit: StackFit.expand, children: [
        FutureBuilder<Uint8List?>(
          future: FcNativeVideoThumbnail().saveThumbnailToBytes(srcFile: f.path, width: 400, height: 400, format: 'jpeg', quality: 70).then<Uint8List?>((b) => b, onError: (_) => null),
          builder: (_, s) => s.data == null ? const SizedBox.shrink() : Image.memory(s.data!, fit: BoxFit.cover),
        ),
        const Center(child: _Badge(round: true, child: Icon(Icons.play_arrow, color: Colors.white, size: 28))),
      ]);
    } else {
      body = Padding(padding: const EdgeInsets.all(12), child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
        Icon(Icons.insert_drive_file_outlined, size: 40, color: t.accent),
        const SizedBox(height: 6),
        Text(bdi(f.name), maxLines: 2, overflow: TextOverflow.ellipsis, textAlign: TextAlign.center),
        Text(formatSize(f.size), style: TextStyle(color: t.muted, fontSize: 12.5)),
      ]));
    }
    return ClipRRect(borderRadius: BorderRadius.circular(12), child: Stack(fit: StackFit.expand, children: [
      ColoredBox(color: t.hover, child: body),
      PositionedDirectional(top: 4, end: 4, child: GestureDetector(onTap: remove, child: const _Badge(round: true, child: Icon(Icons.close, color: Colors.white, size: 16)))),
    ]));
  }
}
