import 'dart:async';

import 'package:app_links/app_links.dart';
import 'package:flutter/material.dart';
import 'package:matrix/matrix.dart';

import 'main.dart' show navigator;
import 'matrix.dart';
import 'ui/chat.dart';
import 'ui/common.dart';
import 'uri.dart';

StreamSubscription<Uri>? _sub;

/// Incoming matrix.to / matrix: links. Call once the first sync is in (the chat list does): the cold-start link and later ones
/// are opened from then on. The OAuth redirect scheme is matrix.dart's.
Future<void> initLinks() async {
  if (_sub != null) return;
  final links = AppLinks();
  _sub = links.uriLinkStream.listen(_incoming);
  final first = await links.getInitialLink().catchError((_) => null);
  if (first != null) _incoming(first);
}

void _incoming(Uri u) {
  if (u.scheme == 'ir.panbeh.flutter') return;
  final t = parseMatrixLink(u.toString());
  final ctx = navigator.currentState?.overlay?.context;
  if (t == null || ctx == null || !ctx.mounted) return;
  openTarget(ctx, t).catchError((Object e) { if (ctx.mounted) alert(ctx, errText(e)); });
}

Room? _findRoom(String id) => id.startsWith('!')
    ? client.getRoomById(id)
    : client.rooms.where((r) => r.canonicalAlias == id || ((r.getState(EventTypes.RoomCanonicalAlias)?.content['alt_aliases'] as List?)?.contains(id) ?? false)).firstOrNull;

void _open(BuildContext context, Room room, String? eventId) =>
    Navigator.push(context, MaterialPageRoute<void>(builder: (_) => ChatPage(room: room, eventId: eventId)));

Future<void> openTarget(BuildContext context, Target t) async {
  if (t.kind == 'user') {
    if (t.id == client.userID) return;
    if (!await confirm(context, 'گفتگوی خصوصی با ${bdi(t.id)} باز شود؟', ok: 'باز کردن') || !context.mounted) return;
    // an existing DM, else a new encrypted one (the SDK also files it under m.direct)
    final id = await client.startDirectChat(t.id, enableEncryption: true);
    final room = client.getRoomById(id);
    if (room != null && context.mounted) _open(context, room, null);
    return;
  }
  var room = _findRoom(t.id);
  if (room?.membership != Membership.join) {
    if (!await confirm(context, 'به ${bdi(t.id)} پیوسته شود؟', ok: 'پیوستن') || !context.mounted) return;
    var via = t.via;
    if (t.kind == 'roomAlias' && via.isEmpty) via = (await client.getRoomIdByAlias(t.id)).servers ?? [];
    final id = await client.joinRoom(t.id, via: via.isEmpty ? null : via);
    await client.waitForRoomInSync(id, join: true);
    room = client.getRoomById(id);
  }
  if (room != null && context.mounted) _open(context, room, t.eventId);
}
