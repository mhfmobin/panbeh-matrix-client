import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:matrix/matrix.dart';

import 'logic.dart';
import 'main.dart' show navigator;
import 'matrix.dart';
import 'media.dart';
import 'theme.dart';
import 'ui/chat.dart';
import 'ui/common.dart';
import 'ui/composer.dart' show addDraft;
import 'ui/media.dart' show reviewAndSend;

const _native = MethodChannel('ir.panbeh.flutter/native');
bool _on = false;

/// Android's share sheet → this app («ارسال به…»). MainActivity copies the shared streams to cache files and hands over
/// `{text, files: [{path, name, mime}]}`: `getShare` for a cold start, an `onShare` ping when we're already running.
/// Call once the first sync is in (the chat list does).
Future<void> initShare() async {
  if (_on) return;
  _on = true;
  _native.setMethodCallHandler((c) async { if (c.method == 'onShare') await _check(); });
  await _check();
}

Future<void> _check() async {
  final Map? m;
  try {
    m = await _native.invokeMethod<Map>('getShare');
  } on PlatformException {
    return;
  } on MissingPluginException {
    return; // not Android
  }
  final ctx = navigator.currentState?.overlay?.context;
  if (m == null || ctx == null || !ctx.mounted || !hasClient) return;
  final text = (m['text'] as String?)?.trim() ?? '';
  final files = [
    for (final f in (m['files'] as List?) ?? const []) () {
      final x = f as Map;
      return Picked(x['path'] as String, x['name'] as String?, x['mime'] as String?);
    }(),
  ];
  if (text.isEmpty && files.isEmpty) return;
  final room = await Navigator.push<Room>(ctx, MaterialPageRoute(builder: (_) => const _Picker()));
  if (room == null || !ctx.mounted) return;
  if (text.isNotEmpty) addDraft(room.id, text);
  Navigator.push(ctx, MaterialPageRoute<void>(builder: (_) => ChatPage(room: room)));
  if (files.isNotEmpty) await reviewAndSend(ctx, room, files);
}

class _Picker extends StatefulWidget {
  const _Picker();
  @override
  State<_Picker> createState() => _PickerState();
}

class _PickerState extends State<_Picker> {
  final _q = TextEditingController();

  @override
  void dispose() {
    _q.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tk, q = normalize(_q.text.trim());
    final rooms = [
      for (final r in client.rooms) if (r.membership == Membership.join && !r.isSpace && r.canSendDefaultMessages && (q.isEmpty || normalize(roomTitle(r)).contains(q))) r,
    ]..sort((a, b) => (b.lastEvent?.originServerTs ?? DateTime(0)).compareTo(a.lastEvent?.originServerTs ?? DateTime(0)));
    return Scaffold(
      appBar: AppBar(
        title: TextField(
          controller: _q, autofocus: true, onChanged: (_) => setState(() {}),
          style: const TextStyle(color: Colors.white, fontSize: 18), cursorColor: Colors.white,
          decoration: const InputDecoration(hintText: 'ارسال به…', hintStyle: TextStyle(color: Colors.white70), border: InputBorder.none)),
      ),
      body: rooms.isEmpty
          ? Center(child: Text('گفتگویی پیدا نشد', style: TextStyle(color: t.muted)))
          : ListView.builder(itemCount: rooms.length, itemBuilder: (_, i) => ListTile(
              leading: RoomAvatar(rooms[i], size: 44),
              title: Text(roomTitle(rooms[i]), maxLines: 1, overflow: TextOverflow.ellipsis),
              onTap: () => Navigator.pop(context, rooms[i]),
            )),
    );
  }
}
