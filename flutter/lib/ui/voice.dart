import 'dart:async';
import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:just_audio/just_audio.dart';
import 'package:matrix/matrix.dart';
import 'package:path_provider/path_provider.dart';
import 'package:record/record.dart';

import '../logic.dart';
import '../main.dart' show navigator;
import '../prefs.dart';
import '../theme.dart';
import 'chat.dart';
import 'common.dart';

const _bars = 40;
const _speeds = [1.0, 1.5, 2.0, 0.5];
const _speedKey = 'panbeh.voiceSpeed';
// the web app's AUDIO_BPS (livekit speech / music / musicHighQuality)
const audioBps = {'low': 24000, 'normal': 48000, 'high': 96000};

// ---------- player ----------

class Track {
  final String id, roomId;
  final Event? ev;
  final Timeline? timeline;
  final String? title;
  final Future<String> Function() load; // → local file path
  final num duration; // ms
  final List<num> waveform;
  const Track({required this.id, this.ev, this.timeline, this.title, required this.load, required this.duration, this.waveform = const [], this.roomId = ''});

  factory Track.of(Event ev, Timeline? tl) {
    final c = ev.content;
    final w = (c['org.matrix.msc1767.audio'] as Map?)?['waveform'] ?? (c['m.audio_details'] as Map?)?['waveform'];
    return Track(
      id: ev.eventId, ev: ev, timeline: tl, roomId: ev.roomId ?? '', title: isVoice(c) ? null : '${c['body'] ?? ''}',
      load: () => _fetch(ev), duration: audioDuration(c), waveform: w is List ? w.whereType<num>().toList() : const [],
    );
  }
}

/// The attachment (decrypted if needed) as a file in the temp dir; just_audio plays from a path.
Future<String> _fetch(Event ev) async {
  final dir = Directory('${(await getTemporaryDirectory()).path}/voice');
  final name = '${ev.eventId.replaceAll(RegExp(r'[^A-Za-z0-9]'), '_')}.${_ext('${(ev.content['info'] as Map?)?['mimetype'] ?? ''}', '${ev.content['body'] ?? ''}')}';
  final f = File('${dir.path}/$name');
  if (await f.exists()) return f.path;
  final file = await ev.downloadAndDecryptAttachment();
  await dir.create(recursive: true);
  await f.writeAsBytes(file.bytes);
  return f.path;
}

String _ext(String mime, String name) {
  if (mime.contains('ogg') || mime.contains('opus')) return 'ogg';
  if (mime.contains('mp4') || mime.contains('aac') || mime.contains('m4a')) return 'm4a';
  if (mime.contains('mpeg') || mime.contains('mp3')) return 'mp3';
  if (mime.contains('webm')) return 'webm';
  if (mime.contains('wav')) return 'wav';
  final e = RegExp(r'\.([A-Za-z0-9]{2,4})$').firstMatch(name)?[1];
  return e ?? 'ogg';
}

enum PlayState { idle, loading, playing, paused }

/// One player for the whole app, so playback survives scrolling and chat switches.
class VoicePlayer extends ChangeNotifier {
  AudioPlayer? _p; // lazy: the plugin isn't there in widget tests
  Track? track;
  PlayState state = PlayState.idle;
  Duration pos = Duration.zero, dur = Duration.zero;
  late double speed = _loadSpeed();

  static double _loadSpeed() {
    final s = prefs.store.getDouble(_speedKey);
    return _speeds.contains(s) ? s! : 1;
  }

  AudioPlayer get _player => _p ??= (AudioPlayer()
    ..playerStateStream.listen((s) {
      if (track == null || state == PlayState.loading) return;
      if (s.processingState == ProcessingState.completed) return _done();
      _set(state: s.playing ? PlayState.playing : PlayState.paused);
    })
    ..positionStream.listen((p) { if (track != null && state != PlayState.loading) _set(pos: p); })
    ..durationStream.listen((d) { if (d != null && track != null) _set(dur: d); }));

  void _set({PlayState? state, Duration? pos, Duration? dur}) {
    this.state = state ?? this.state;
    this.pos = pos ?? this.pos;
    this.dur = dur ?? this.dur;
    notifyListeners();
  }

  Future<void> start(Track t) async {
    track = t;
    _set(state: PlayState.loading, pos: Duration.zero, dur: Duration(milliseconds: t.duration.round()));
    final p = _player;
    await p.pause(); // the previous one stops while this one loads
    try {
      final path = await t.load();
      if (track != t) return; // picked something else meanwhile
      // recorder files may report no duration; keep the one from the event
      await p.setFilePath(path);
      await p.setSpeed(speed);
      state = PlayState.paused;
      p.play(); // completes when playback ends
    } catch (e) {
      if (track == t) _set(state: PlayState.idle);
      final c = navigator.currentContext;
      if (c != null && c.mounted) alert(c, errText(e));
    }
  }

  void toggle(Track t) {
    if (track?.id != t.id) {
      start(t);
      return;
    }
    if (state == PlayState.playing) {
      _player.pause();
    } else if (state == PlayState.paused) {
      _player.play();
    }
  }

  void seek(double f) {
    if (dur == Duration.zero) return;
    final to = dur * f.clamp(0.0, 1.0);
    _player.seek(to);
    _set(pos: to);
  }

  void cycleSpeed() {
    speed = _speeds[(_speeds.indexOf(speed) + 1) % _speeds.length];
    prefs.store.setDouble(_speedKey, speed);
    _p?.setSpeed(speed);
    notifyListeners();
  }

  Future<void> stop() async {
    final p = _p;
    track = null;
    _set(state: PlayState.idle, pos: Duration.zero, dur: Duration.zero);
    await p?.stop();
  }

  /// Auto-plays the next voice/audio message of the same timeline (what's loaded; nothing arrives after the chat is left).
  void _done() {
    final t = track, tl = t?.timeline;
    if (t != null && tl != null) {
      final evs = tl.events; // newest first
      final i = evs.indexWhere((e) => e.eventId == t.id);
      for (var j = i - 1; i > 0 && j >= 0; j--) {
        final e = evs[j];
        if (e.type == EventTypes.Message && e.content['msgtype'] == 'm.audio' && !e.redacted && e.status.isSynced) {
          start(Track.of(e, tl));
          return;
        }
      }
    }
    _player.pause();
    _player.seek(Duration.zero);
    _set(state: PlayState.paused, pos: Duration.zero);
  }
}

final voice = VoicePlayer();

// ---------- bubble ----------

/// Voice/audio message body. The media is fetched on first play, so a chat full of voice notes downloads nothing up front.
class VoiceBubble extends StatelessWidget {
  final Event ev;
  final Timeline? timeline;
  final bool mine;
  final double metaW; // room kept for the bubble's time/ticks
  const VoiceBubble({super.key, required this.ev, required this.timeline, required this.mine, required this.metaW});
  @override
  Widget build(BuildContext context) => VoicePlayerRow(
    track: Track.of(ev, timeline), mine: mine, metaW: metaW, uploading: ev.status.isSending,
    // the track id is stable across local echo, so loading starts only after it is sent
  );
}

class VoicePlayerRow extends StatelessWidget {
  final Track track;
  final bool mine, uploading;
  final double metaW;
  const VoicePlayerRow({super.key, required this.track, required this.mine, this.metaW = 0, this.uploading = false});

  @override
  Widget build(BuildContext context) {
    final t = context.tk;
    final strong = mine ? t.outText : t.accent, weak = mine ? t.outMeta : t.muted.withValues(alpha: .6);
    final w = track.waveform.isEmpty ? List.filled(_bars, 320) : downsample(track.waveform, _bars);
    return ListenableBuilder(listenable: voice, builder: (context, _) {
      final cur = voice.track?.id == track.id;
      final state = cur ? voice.state : PlayState.idle;
      final dur = cur && voice.dur > Duration.zero ? voice.dur : Duration(milliseconds: track.duration.round());
      final pos = cur ? voice.pos : Duration.zero;
      final f = dur.inMilliseconds == 0 ? 0.0 : (pos.inMilliseconds / dur.inMilliseconds).clamp(0.0, 1.0);
      return Directionality(textDirection: TextDirection.ltr, child: ConstrainedBox(
        constraints: const BoxConstraints(minWidth: 210),
        child: Row(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          GestureDetector(
            onTap: uploading ? null : () => voice.toggle(track),
            child: Container(
              width: 44, height: 44, margin: const EdgeInsets.only(top: 2), decoration: BoxDecoration(color: strong, shape: BoxShape.circle),
              child: uploading || state == PlayState.loading
                  ? Padding(padding: const EdgeInsets.all(12), child: CircularProgressIndicator(strokeWidth: 2.5, color: mine ? t.bubbleOut : Colors.white))
                  : Icon(state == PlayState.playing ? Icons.pause : Icons.play_arrow, size: 28, color: mine ? t.bubbleOut : Colors.white),
            ),
          ),
          const SizedBox(width: 8),
          Flexible(child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
            if (track.title != null) Padding(padding: const EdgeInsets.only(bottom: 2), child: Text(track.title!, maxLines: 1, overflow: TextOverflow.ellipsis,
              textDirection: textDir(track.title!) == 'rtl' ? TextDirection.rtl : TextDirection.ltr,
              style: TextStyle(color: mine ? t.outText : t.text, fontWeight: FontWeight.w600, fontSize: 14.5))),
            Wave(bars: w, progress: f, on: strong, off: weak, onSeek: cur && state != PlayState.loading ? voice.seek : (_) => voice.toggle(track)),
            Row(children: [
              Text(fmtDuration((state == PlayState.idle ? dur : pos).inMilliseconds), style: TextStyle(fontSize: 12, color: mine ? t.outMeta : t.muted)),
              const SizedBox(width: 8),
              GestureDetector(
                onTap: voice.cycleSpeed,
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
                  decoration: BoxDecoration(color: strong.withValues(alpha: .15), borderRadius: BorderRadius.circular(8)),
                  child: Text('${faNum(voice.speed)}×', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: strong)),
                ),
              ),
              SizedBox(width: metaW),
            ]),
          ])),
        ]),
      ));
    });
  }
}

/// Bars 0..1024; tap or drag to seek.
class Wave extends StatelessWidget {
  final List<num> bars;
  final double progress;
  final Color on, off;
  final ValueChanged<double> onSeek;
  const Wave({super.key, required this.bars, required this.progress, required this.on, required this.off, required this.onSeek});
  @override
  Widget build(BuildContext context) => LayoutBuilder(builder: (context, c) {
    final w = c.maxWidth.isFinite ? c.maxWidth : 160.0;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTapUp: (d) => onSeek(d.localPosition.dx / w),
      onHorizontalDragUpdate: (d) => onSeek(d.localPosition.dx / w),
      child: SizedBox(height: 30, width: w, child: CustomPaint(painter: _WavePainter(bars, progress, on, off))),
    );
  });
}

class _WavePainter extends CustomPainter {
  final List<num> bars;
  final double progress;
  final Color on, off;
  _WavePainter(this.bars, this.progress, this.on, this.off);
  @override
  void paint(Canvas canvas, Size size) {
    final n = bars.length;
    final slot = size.width / n, bw = math.max(1.5, slot * .55);
    for (var i = 0; i < n; i++) {
      final h = size.height * (.15 + (bars[i] / 1024).clamp(0, 1) * .85);
      final x = i * slot + (slot - bw) / 2;
      canvas.drawRRect(RRect.fromRectAndRadius(Rect.fromLTWH(x, (size.height - h) / 2, bw, h), Radius.circular(bw / 2)), Paint()..color = i / n < progress ? on : off);
    }
  }
  @override
  bool shouldRepaint(_WavePainter o) => o.progress != progress || o.bars != bars || o.on != on || o.off != off;
}

// ---------- now playing ----------

/// Telegram's strip under the AppBar while something plays. Tap the text to open the message:
/// `room` + `onJump` when it's the open chat, else the chat is opened.
class NowPlaying extends StatelessWidget {
  final Room? room;
  final void Function(String id)? onJump;
  const NowPlaying({super.key, this.room, this.onJump});

  @override
  Widget build(BuildContext context) => ListenableBuilder(listenable: voice, builder: (context, _) {
    final tr = voice.track, ev = tr?.ev;
    if (tr == null || ev == null) return const SizedBox.shrink();
    final t = context.tk;
    final playing = voice.state == PlayState.playing;
    final f = voice.dur.inMilliseconds == 0 ? 0.0 : (voice.pos.inMilliseconds / voice.dur.inMilliseconds).clamp(0.0, 1.0);
    return Material(color: t.panel, child: Stack(children: [
      SizedBox(height: 48, child: Row(children: [
        IconButton(
          icon: voice.state == PlayState.loading
              ? const SizedBox.square(dimension: 20, child: CircularProgressIndicator(strokeWidth: 2))
              : Icon(playing ? Icons.pause : Icons.play_arrow, color: t.accent),
          onPressed: () => voice.toggle(tr)),
        Expanded(child: InkWell(
          onTap: () {
            if (room != null && room!.id == tr.roomId && onJump != null) return onJump!(tr.id);
            final r = ev.room;
            Navigator.push(context, MaterialPageRoute<void>(builder: (_) => ChatPage(room: r)));
          },
          child: Align(alignment: AlignmentDirectional.centerStart, child: Text.rich(
            TextSpan(children: [
              TextSpan(text: bdi(senderName(ev)), style: const TextStyle(fontWeight: FontWeight.w600)),
              TextSpan(text: '  ${tr.title ?? 'پیام صوتی'}', style: TextStyle(color: t.muted)),
            ]),
            maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 14))),
        )),
        Text(fmtDuration(voice.pos.inMilliseconds), style: TextStyle(fontSize: 12, color: t.muted)),
        TextButton(
          style: TextButton.styleFrom(minimumSize: const Size(40, 32), padding: const EdgeInsets.symmetric(horizontal: 6)),
          onPressed: voice.cycleSpeed, child: Text('${faNum(voice.speed)}×', style: const TextStyle(fontWeight: FontWeight.w600))),
        IconButton(icon: Icon(Icons.close, color: t.muted), tooltip: 'بستن', onPressed: voice.stop),
      ])),
      Positioned(bottom: 0, left: 0, right: 0, child: Directionality(textDirection: TextDirection.ltr, child: Align(
        alignment: Alignment.centerLeft, child: FractionallySizedBox(widthFactor: f, child: Container(height: 2, color: t.accent))))),
    ]));
  });
}

// ---------- recorder ----------

typedef VoiceResult = ({Uint8List bytes, String name, String mime, int ms, List<int> waveform});
enum RecPhase { idle, holding, locked, preview }

/// Press-and-hold voice recording (Telegram): slide towards the text field to cancel, up to lock.
/// Drive it with [MicButton]; show [RecorderField] in place of the text field while `active`.
class VoiceRec extends ChangeNotifier {
  static const cancelAt = 90.0, lockAt = 70.0;
  final _r = AudioRecorder();
  RecPhase phase = RecPhase.idle;
  double dx = 0, dy = 0; // drag towards the field / up, in px (≥ 0)
  int elapsed = 0;
  final levels = <double>[];
  Timer? _tick;
  StreamSubscription? _amp;
  Future<bool>? _starting;
  final _clock = Stopwatch();
  String? _path, _mime, _name;
  VoiceResult? preview;
  Track? previewTrack;

  bool get active => phase != RecPhase.idle;

  @override
  void dispose() {
    _tick?.cancel();
    _amp?.cancel();
    _r.dispose();
    super.dispose();
  }

  void _set(VoidCallback f) {
    f();
    notifyListeners();
  }

  /// Returns false if it didn't start (permission/encoder); the error is shown.
  Future<void> begin(BuildContext context) async {
    if (active) return;
    HapticFeedback.mediumImpact();
    levels.clear();
    _set(() { phase = RecPhase.holding; dx = dy = 0; elapsed = 0; });
    final s = _starting = _start();
    try {
      if (!await s) _set(() => phase = RecPhase.idle);
    } catch (e) {
      _set(() => phase = RecPhase.idle);
      if (context.mounted) alert(context, errText(e));
    }
  }

  Future<bool> _start() async {
    if (!await _r.hasPermission()) {
      final c = navigator.currentContext;
      if (c != null && c.mounted) await alert(c, 'دسترسی به میکروفون ممکن نشد. اجازه‌ی میکروفون را در تنظیمات برنامه بدهید.');
      return false;
    }
    final opus = await _r.isEncoderSupported(AudioEncoder.opus); // Opus in OGG, API 29+; older phones get AAC
    final ext = opus ? 'ogg' : 'm4a';
    _mime = opus ? 'audio/ogg' : 'audio/mp4';
    _name = 'voice.$ext';
    _path = '${(await getTemporaryDirectory()).path}/rec_${DateTime.now().millisecondsSinceEpoch}.$ext';
    await _r.start(RecordConfig(
      encoder: opus ? AudioEncoder.opus : AudioEncoder.aacLc, bitRate: audioBps[prefs.get<String>('audioQuality')] ?? 96000,
      sampleRate: opus ? 48000 : 44100, numChannels: 1,
      autoGain: true, echoCancel: false, noiseSuppress: false, // nothing plays while recording
    ), path: _path!);
    _clock..reset()..start();
    _amp = _r.onAmplitudeChanged(const Duration(milliseconds: 100)).listen((a) => levels.add(math.pow(10, a.current.clamp(-60.0, 0.0) / 20).toDouble()));
    _tick = Timer.periodic(const Duration(milliseconds: 100), (_) => _set(() => elapsed = _clock.elapsedMilliseconds));
    return true;
  }

  void move(double toField, double up) {
    if (phase != RecPhase.holding) return;
    final lock = up >= lockAt;
    _set(() { dx = math.max(0, toField); dy = math.max(0, up); });
    if (dx >= cancelAt) {
      HapticFeedback.mediumImpact();
      cancel();
    } else if (lock) {
      HapticFeedback.mediumImpact();
      _set(() => phase = RecPhase.locked);
    }
  }

  /// Finger lifted. Returns the recording to send (held long enough), else null.
  Future<VoiceResult?> release() async {
    if (phase != RecPhase.holding) return null;
    final r = await _finish();
    if (r == null || r.ms < 500) return null;
    return r;
  }

  /// Locked: ■ → preview with play / trash / send.
  Future<void> stopToPreview() async {
    final r = await _finish(keepPhase: true);
    if (r == null) return cancel();
    preview = r;
    final path = _path!;
    previewTrack = Track(id: 'preview:$path', load: () async => path, duration: r.ms, waveform: r.waveform);
    _set(() => phase = RecPhase.preview);
  }

  /// Send from locked or preview.
  Future<VoiceResult?> send() async {
    final r = phase == RecPhase.preview ? preview : await _finish();
    _reset();
    return r;
  }

  Future<VoiceResult?> _finish({bool keepPhase = false}) async {
    try { await _starting; } catch (_) { return null; }
    _tick?.cancel();
    _amp?.cancel();
    _clock.stop();
    final ms = _clock.elapsedMilliseconds;
    String? p;
    try { p = await _r.stop(); } catch (_) {}
    if (!keepPhase) _reset();
    if (p == null || !await File(p).exists()) return null;
    final bytes = await File(p).readAsBytes();
    return (bytes: bytes, name: _name!, mime: _mime!, ms: ms, waveform: downsample(levels));
  }

  Future<void> cancel() async {
    try { await _starting; } catch (_) {}
    _tick?.cancel();
    _amp?.cancel();
    if (voice.track?.id == previewTrack?.id && previewTrack != null) voice.stop();
    final p = _path;
    try { await _r.cancel(); } catch (_) {}
    if (p != null) File(p).delete().catchError((_) => File(p));
    _reset();
  }

  void _reset() {
    final p = previewTrack;
    if (p != null && voice.track?.id == p.id) voice.stop();
    preview = null;
    previewTrack = null;
    _starting = null;
    _set(() { phase = RecPhase.idle; dx = dy = 0; elapsed = 0; });
  }
}

/// Mic (no text) / send (locked, preview). Hold the mic to record.
class MicButton extends StatelessWidget {
  final VoiceRec rec;
  final Future<void> Function(VoiceResult) onSend;
  const MicButton({super.key, required this.rec, required this.onSend});

  @override
  Widget build(BuildContext context) {
    final t = context.tk;
    final rtl = Directionality.of(context) == TextDirection.rtl;
    // the text field is on the start side: that's where "cancel" is
    final towardField = rtl ? 1.0 : -1.0;
    final p = rec.phase;
    Offset? from;
    Future<void> sendNow() async {
      final r = await rec.send();
      if (r != null) await onSend(r);
    }
    final holding = p == RecPhase.holding;
    final lockF = (rec.dy / VoiceRec.lockAt).clamp(0.0, 1.0);
    if (p == RecPhase.locked || p == RecPhase.preview) {
      return IconButton.filled(
        style: IconButton.styleFrom(backgroundColor: t.accent, foregroundColor: Colors.white),
        icon: const Icon(Icons.send, textDirection: TextDirection.ltr), tooltip: 'ارسال', onPressed: sendNow);
    }
    return SizedBox(width: 48, height: 48, child: Stack(clipBehavior: Clip.none, alignment: Alignment.center, children: [
      if (holding) Positioned(bottom: 64 + rec.dy.clamp(0.0, 40.0), child: Opacity(opacity: .6 + .4 * lockF, child: Container(
        width: 40, height: 40 + 40 * (1 - lockF),
        decoration: BoxDecoration(color: t.panel, borderRadius: BorderRadius.circular(20), boxShadow: const [BoxShadow(color: Color(0x33000000), blurRadius: 6)]),
        child: Align(alignment: Alignment.topCenter, child: Padding(padding: const EdgeInsets.only(top: 10), child: Icon(lockF >= 1 ? Icons.lock : Icons.lock_open, size: 20, color: t.accent)))))),
      Listener(
        behavior: HitTestBehavior.opaque,
        onPointerDown: (e) {
          from = e.position;
          rec.begin(context);
        },
        onPointerMove: (e) {
          if (from != null) rec.move((e.position.dx - from!.dx) * towardField, from!.dy - e.position.dy);
        },
        onPointerUp: (_) async {
          from = null;
          if (rec.phase != RecPhase.holding) return;
          final r = await rec.release();
          if (r != null) {
            await onSend(r);
          } else if (context.mounted) {
            toast(context, 'برای ضبط پیام صوتی، دکمه را نگه دارید');
          }
        },
        onPointerCancel: (_) { from = null; if (rec.phase == RecPhase.holding) rec.cancel(); },
        child: AnimatedScale(
          scale: holding ? 1.6 : 1, duration: const Duration(milliseconds: 150),
          child: Container(
            width: 48, height: 48, alignment: Alignment.center,
            decoration: BoxDecoration(shape: BoxShape.circle, color: holding ? t.accent : Colors.transparent),
            child: Icon(Icons.mic_none, size: 28, color: holding ? Colors.white : t.muted),
          ),
        ),
      ),
    ]));
  }
}

/// Replaces the text field while recording.
class RecorderField extends StatelessWidget {
  final VoiceRec rec;
  const RecorderField({super.key, required this.rec});

  @override
  Widget build(BuildContext context) {
    final t = context.tk;
    final p = rec.phase;
    final dot = _Blink(child: Container(width: 12, height: 12, decoration: const BoxDecoration(color: Color(0xffe53935), shape: BoxShape.circle)));
    final time = Text(fmtDuration(rec.elapsed), style: const TextStyle(fontSize: 16, fontFeatures: [FontFeature.tabularFigures()]));
    final trash = IconButton(icon: const Icon(Icons.delete_outline, color: Color(0xffe53935)), tooltip: 'حذف', onPressed: rec.cancel);
    final recent = [for (final v in rec.levels.skip(math.max(0, rec.levels.length - 30))) math.min(1.0, v * 4) * 1024];
    return Container(
      height: 48,
      decoration: BoxDecoration(color: t.hover, borderRadius: BorderRadius.circular(22)),
      child: p == RecPhase.preview && rec.previewTrack != null
          ? Row(children: [trash, Expanded(child: Padding(padding: const EdgeInsetsDirectional.only(end: 12), child: VoicePlayerRow(track: rec.previewTrack!, mine: false)))])
          : Row(children: [
              if (p == RecPhase.locked) trash else const SizedBox(width: 14),
              dot, const SizedBox(width: 8), time, const SizedBox(width: 12),
              Expanded(child: p == RecPhase.locked
                  ? Directionality(textDirection: TextDirection.ltr, child: SizedBox(height: 30, child: CustomPaint(
                      painter: _WavePainter(recent.isEmpty ? [0] : recent, 1, t.accent, t.accent))))
                  // fades and follows the finger like Telegram's "slide to cancel"
                  : Opacity(opacity: (1 - rec.dx / VoiceRec.cancelAt).clamp(0.0, 1.0), child: Transform.translate(
                      offset: Offset(-(Directionality.of(context) == TextDirection.rtl ? -1 : 1) * rec.dx * .5, 0),
                      child: Row(mainAxisSize: MainAxisSize.min, children: [
                        Icon(Directionality.of(context) == TextDirection.rtl ? Icons.chevron_right : Icons.chevron_left, color: t.muted),
                        Text('برای لغو بکشید', style: TextStyle(color: t.muted, fontSize: 14)),
                      ])))),
              if (p == RecPhase.locked) IconButton(icon: Icon(Icons.stop_circle_outlined, color: t.accent, size: 30), tooltip: 'پایان ضبط', onPressed: rec.stopToPreview),
            ]),
    );
  }
}

class _Blink extends StatefulWidget {
  final Widget child;
  const _Blink({required this.child});
  @override
  State<_Blink> createState() => _BlinkState();
}

class _BlinkState extends State<_Blink> with SingleTickerProviderStateMixin {
  late final _c = AnimationController(vsync: this, duration: const Duration(milliseconds: 700))..repeat(reverse: true);
  @override
  void dispose() { _c.dispose(); super.dispose(); }
  @override
  Widget build(BuildContext context) => FadeTransition(opacity: Tween(begin: .25, end: 1.0).animate(_c), child: widget.child);
}

/// m.audio voice message content, like the web app's: Element shows it as a voice message with a waveform.
Map<String, dynamic> voiceContent(VoiceResult r) => {
  'msgtype': 'm.audio', 'body': 'پیام صوتی',
  'info': {'duration': r.ms, 'mimetype': r.mime, 'size': r.bytes.length},
  'org.matrix.msc1767.audio': {'duration': r.ms, 'waveform': r.waveform},
  'org.matrix.msc3245.voice': <String, dynamic>{},
};

Future<void> sendVoice(Room room, VoiceResult r, {Event? replyTo, Map<String, dynamic>? extra}) => room.sendFileEvent(
  MatrixAudioFile(bytes: r.bytes, name: r.name, mimeType: r.mime, duration: r.ms),
  extraContent: {
    ...voiceContent(r), ...?extra,
    if (replyTo != null) 'm.relates_to': {'m.in_reply_to': {'event_id': replyTo.eventId}},
  },
);
