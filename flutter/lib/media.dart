import 'dart:async';
import 'dart:io';

import 'package:fc_native_video_thumbnail/fc_native_video_thumbnail.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:image/image.dart' as img;
import 'package:matrix/matrix.dart';
import 'package:mime/mime.dart';
import 'package:path_provider/path_provider.dart';
import 'package:video_player/video_player.dart';

import 'logic.dart';
import 'matrix.dart';
import 'ui/common.dart' show loadMxc, me;
import 'ui/thread.dart' show relatesTo;

const _native = MethodChannel('ir.panbeh.flutter/native');

/// A file from the gallery, camera or file picker, not read into memory yet.
class Picked {
  final String path, name, mime;
  final int size;
  Picked(this.path, String? name, String? mime)
      : name = name ?? path.split('/').last,
        mime = mime ?? lookupMimeType(name ?? path) ?? 'application/octet-stream',
        size = File(path).lengthSync();
  bool get image => mime.startsWith('image/');
  bool get video => mime.startsWith('video/');
}

// ---------- sending ----------

/// The caption (MSC2530) is the body, the file name goes in `filename`: the SDK always writes body = filename,
/// but `extraContent` is merged over it.
final _cancelled = <String>{};
Future<void> _queue = Future.value(); // one file at a time, in order
// ponytail: the SDK reports no upload bytes (and can't abort): the bubble shows its stage, not a percentage

/// The first file carries the caption and the reply (like the web app). Returns at once; the SDK's local echo is the pending bubble.
void sendMedia(Room room, List<Picked> files, {String caption = '', bool asFile = false, bool asGif = false, Event? replyTo, String? threadId, void Function(Object)? onError}) {
  for (var i = 0; i < files.length; i++) {
    final f = files[i], first = i == 0;
    _queue = _queue.then((_) => _one(room, f, first ? caption : '', asFile, asGif, first ? replyTo : null, threadId)).catchError((Object e) {
      onError?.call(e);
    });
  }
}

/// JPEG, longest side 1280, quality 82 (the web app's canvas recipe); the original if that isn't smaller or can't be decoded.
Uint8List? _compress(Uint8List bytes) {
  final src = img.decodeImage(bytes);
  if (src == null) return null;
  var im = img.bakeOrientation(src);
  final k = fitSize(im.width, im.height);
  if (k.w != im.width) im = img.copyResize(im, width: k.w, height: k.h, interpolation: img.Interpolation.average);
  if (im.hasAlpha) { // JPEG has no alpha
    final bg = img.Image(width: im.width, height: im.height)..clear(img.ColorRgb8(255, 255, 255));
    im = img.compositeImage(bg, im);
  }
  final out = Uint8List.fromList(img.encodeJpg(im, quality: 82));
  return out.length < bytes.length ? out : null;
}

Future<void> _one(Room room, Picked f, String caption, bool asFile, bool asGif, Event? replyTo, String? threadId) async {
  var bytes = await File(f.path).readAsBytes();
  var name = f.name;
  final c = room.client, txid = c.generateUniqueTransactionId();
  final visual = f.image || f.video;
  final extra = <String, dynamic>{
    if (caption.isNotEmpty) ...{'body': caption, 'filename': name},
    if (asFile || !visual) 'msgtype': 'm.file',
    'm.relates_to': ?relatesTo(threadId, replyTo),
    if (replyTo != null && replyTo.senderId != me()) 'm.mentions': {'user_ids': [replyTo.senderId]},
  };
  MatrixFile file;
  MatrixImageFile? thumb;
  if (asFile || !visual) {
    file = MatrixFile(bytes: bytes, name: name, mimeType: f.mime);
  } else if (f.image) {
    var mime = f.mime;
    if (RegExp(r'^image/(jpeg|png|webp|bmp)$').hasMatch(mime)) {
      final small = await compute(_compress, bytes);
      if (small != null) {
        bytes = small;
        name = '${name.replaceFirst(RegExp(r'\.[^.]+$'), '')}.jpg';
        mime = 'image/jpeg';
        if (caption.isNotEmpty) extra['filename'] = name;
      }
    }
    file = await MatrixImageFile.create(bytes: bytes, name: name, mimeType: mime, nativeImplementations: c.nativeImplementations);
  } else {
    // size, duration and a poster frame, so bubbles (ours and other clients') are right before any download
    int? w, h, ms;
    final p = VideoPlayerController.file(File(f.path));
    try {
      await p.initialize().timeout(const Duration(seconds: 10));
      w = p.value.size.width.round();
      h = p.value.size.height.round();
      ms = p.value.duration.inMilliseconds;
    } catch (_) {} // some codecs never initialise: sent without
    await p.dispose();
    file = MatrixVideoFile(bytes: bytes, name: name, mimeType: f.mime, width: w, height: h, duration: ms);
    if (asGif) {
      // mautrix's flags; `info` replaces the SDK's whole info, so it carries ours (no poster: a gif plays itself)
      extra['info'] = {...file.info, ...gifInfo};
    } else {
      try {
        final jpg = await FcNativeVideoThumbnail().saveThumbnailToBytes(srcFile: f.path, width: 800, height: 800, format: 'jpeg', quality: 80);
        if (jpg != null) thumb = await MatrixImageFile.create(bytes: jpg, name: '$name.thumbnail.jpg', mimeType: 'image/jpeg', nativeImplementations: c.nativeImplementations);
      } catch (_) {} // no poster: the bubble shows a plain tile
    }
  }
  final id = await room.sendFileEvent(file, txid: txid, thumbnail: thumb, extraContent: extra);
  // the upload can't be aborted, so a message cancelled while uploading is taken back once it exists
  if (_cancelled.remove(txid)) {
    if (id != null) await room.redactEvent(id);
    return;
  }
  if (id != null && (asGif || file.mimeType == 'image/gif') && !asFile) {
    final sent = await room.getEventById(id);
    if (sent != null && isGif(sent.content)) await saveGif(sent.content).catchError((_) {}); // still sent, just not saved
  }
}

/// ✕ on a pending bubble.
Future<void> cancelUpload(Event ev) async {
  if (ev.status.isSending) _cancelled.add(ev.transactionId ?? ev.eventId);
  await ev.cancelSend();
}

// ---------- saved gifs ----------

/// Marks an uploaded video as a gif: autoplays, loops, muted, no controls (mautrix's flags).
const gifInfo = {'fi.mau.gif': true, 'fi.mau.loop': true, 'fi.mau.autoplay': true, 'fi.mau.hide_controls': true, 'fi.mau.no_audio': true};
const _gifsKey = 'app.panbeh.gifs';

List<Map<String, dynamic>> savedGifs() =>
    [for (final g in (client.accountData[_gifsKey]?.content['gifs'] as List?) ?? const []) (g as Map).cast<String, dynamic>()];
/// No caption, reply or forward marks.
Gif _gifOf(Map c) => {'msgtype': c['msgtype'], 'body': 'gif', 'info': c['info'], if (c['url'] != null) 'url': c['url'] else 'file': c['file']};
Future<void> _putGifs(List<Gif> gifs) => client.setAccountData(me(), _gifsKey, {'gifs': gifs});
Future<void> saveGif(Map c) => _putGifs(withGif(savedGifs(), _gifOf(c)));
bool isSavedGif(Map c) => hasGif(savedGifs(), _gifOf(c));
Future<void> toggleGif(Map c) => isSavedGif(c) ? _putGifs(withoutGif(savedGifs(), _gifOf(c))) : saveGif(c);

Future<void> sendGif(Room room, Map<String, dynamic> gif, {Event? replyTo}) async {
  await room.sendEvent({
    ...gif,
    if (replyTo != null) ...{
      'm.relates_to': {'m.in_reply_to': {'event_id': replyTo.eventId}},
      if (replyTo.senderId != me()) 'm.mentions': {'user_ids': [replyTo.senderId]},
    },
  });
}

// ---------- location ----------

Future<void> sendLocation(Room room, double lat, double lon, int accuracy, {Event? replyTo, String? threadId}) {
  final la = double.parse(lat.toStringAsFixed(6)), lo = double.parse(lon.toStringAsFixed(6));
  final geo = 'geo:$la,$lo;u=$accuracy';
  return room.sendEvent({
    'msgtype': 'm.location',
    // body keeps a map link for clients that don't show locations
    'body': 'موقعیت مکانی ${osmUrl(la, lo)}',
    'geo_uri': geo,
    'org.matrix.msc3488.location': {'uri': geo},
    'org.matrix.msc3488.asset': {'type': 'm.self'},
    'org.matrix.msc3488.ts': DateTime.now().millisecondsSinceEpoch,
    'm.relates_to': ?relatesTo(threadId, replyTo),
  });
}

// ---------- downloading ----------

bool _pending(Event ev) => !ev.status.isSynced && !ev.hasAttachment;

Future<T> _remember<T>(Map<String, Future<T>> m, String key, int cap, Future<T> Function() make) {
  final hit = m[key];
  if (hit != null) return hit;
  if (m.length >= cap) m.remove(m.keys.first);
  final f = m[key] = make();
  f.then((_) {}, onError: (_) { m.remove(key); }); // try again next time
  return f;
}

final _thumbs = <String, Future<Uint8List?>>{}, _fulls = <String, Future<Uint8List>>{};

/// A small preview: the sender's thumbnail, else a server-side one (encrypted media can't be thumbnailed: the file itself).
/// Null for a video without poster. Pending events read the SDK's cached file.
Future<Uint8List?> previewBytes(Event ev) => _remember(_thumbs, ev.eventId, 400, () async {
  final c = ev.content;
  if (_pending(ev)) {
    if (c['msgtype'] == 'm.video') return (await ev.downloadAndDecryptAttachment(getThumbnail: true).then<MatrixFile?>((f) => f, onError: (_) => null))?.bytes;
    return (await ev.downloadAndDecryptAttachment()).bytes;
  }
  if (ev.hasThumbnail) return (await ev.downloadAndDecryptAttachment(getThumbnail: true)).bytes;
  if (c['msgtype'] == 'm.video') return null;
  final mxc = ev.attachmentMxcUrl;
  if (mxc != null && !ev.isAttachmentEncrypted && ev.attachmentMimetype != 'image/gif') {
    final b = await loadMxc(mxc, size: 640);
    if (b != null) return b;
  }
  return (await ev.downloadAndDecryptAttachment()).bytes;
});

/// The whole image (viewer, animated gifs); the last few stay in memory.
Future<Uint8List> imageBytes(Event ev) => _remember(_fulls, ev.eventId, 6, () async => (await ev.downloadAndDecryptAttachment()).bytes);

final _files = <String, Future<String>>{};

/// A downloaded, decrypted copy of a video on disk (the player wants a path). `onProgress` only reports the download this call starts.
Future<String> videoPath(Event ev, {void Function(int)? onProgress}) => _remember(_files, ev.eventId, 1 << 20, () async {
  final f = await ev.downloadAndDecryptAttachment(onDownloadProgress: onProgress);
  final dir = Directory('${(await getTemporaryDirectory()).path}/media')..createSync(recursive: true);
  final ext = f.name.contains('.') ? f.name.substring(f.name.lastIndexOf('.')) : '.mp4';
  final file = File('${dir.path}/${ev.eventId.hashCode.toUnsigned(32)}$ext');
  await file.writeAsBytes(f.bytes, flush: true);
  return file.path;
});

String fileNameOf(Map c) => (c['filename'] ?? c['body'] ?? 'file').toString();

/// Into the Downloads folder (Android 10+: MediaStore, no permission). Throws if it can't.
Future<void> saveToDownloads(String name, String mime, Uint8List bytes) =>
    _native.invokeMethod<void>('saveToDownloads', {'name': name, 'mime': mime, 'bytes': bytes});

/// Downloads (decrypting) and saves; `onProgress` gets the downloaded bytes.
Future<void> saveEvent(Event ev, {void Function(int)? onProgress}) async {
  final f = await ev.downloadAndDecryptAttachment(onDownloadProgress: onProgress);
  await saveToDownloads(fileNameOf(ev.content), f.mimeType, f.bytes);
}
