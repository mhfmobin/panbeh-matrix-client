// Chat-list state kept on the server (tags: pin/archive; "marked as unread") and the room-row model.
import 'dart:convert';

import 'package:matrix/matrix.dart';

import 'logic.dart';
import 'matrix.dart';
import 'prefs.dart';

const pinnedTag = 'm.favourite'; // Element shows these as Favourites
const archivedTag = 'u.archived'; // user-defined tags must start with "u."

bool hasTag(Room room, String tag) => room.tags.containsKey(tag);
Future<void> setTag(Room room, String tag, bool on) => on ? room.addTag(tag) : room.removeTag(tag);

// The SDK reads both the stable and the old famedly name (MSC2867); markUnread writes the stable one,
// which wins over the old one when read, so clearing it clears both.
Future<void> setMarkedUnread(Room room, bool unread) => room.markUnread(unread);

/// Receipt on the newest event + clear the manual mark.
Future<void> markRead(Room room) async {
  final last = room.lastEvent;
  if (last != null && room.membership == Membership.join) {
    await room.setReadMarker(last.eventId, mRead: last.eventId);
  }
  if (room.markedUnread) await room.markUnread(false);
}

/// Muted = a room or override rule for this room that doesn't notify (ours, or Element's).
bool isMuted(Room room) => room.pushRuleState != PushRuleState.notify;
// the web app's mute is a room rule with no actions
Future<void> setMuted(Room room, bool mute) => room.setPushRuleState(mute ? PushRuleState.mentionsOnly : PushRuleState.notify);

/// Leave and drop it from the server's room list for good.
Future<void> leaveAndForget(Room room) async {
  if (room.membership != Membership.leave) {
    try {
      await room.leave(waitForSync: true).timeout(const Duration(seconds: 10), onTimeout: () {});
    } on MatrixException catch (e) {
      if (e.error != MatrixError.M_FORBIDDEN) rethrow;
      // not in the room at all: just forget it
    }
  }
  await room.forget();
}

// ---------- room rows ----------

class RoomRow extends RoomInfo implements ListOrdered {
  final Room room;
  final Event? last;
  @override
  final int ts;
  @override
  final bool invite, pinned;
  const RoomRow({required this.room, this.last, required this.ts, required this.invite, required this.pinned, required super.isDM,
    required super.unread, required super.spaces, super.marked, super.archived, super.muted}) : super(id: '');
  @override
  String get id => room.id;
}

// getType() is the decrypted type once decrypted; m.room.encrypted = still pending or failed
const _messageTypes = [EventTypes.Message, EventTypes.Sticker, EventTypes.Encrypted, 'org.matrix.msc3381.poll.start', 'm.poll.start'];

/// Blocked senders are hidden here too: some servers (Conduit) ignore m.ignored_user_list and keep sending their events.
bool isMessage(Event e) =>
    _messageTypes.contains(e.type) && !e.redacted && !client.ignoredUsers.contains(e.senderId) && e.content['msgtype'] != 'm.key.verification.request';

/// Calls count too: calling someone brings the chat to the top, like a message.
bool isCallStart(Event e) => e.type == EventTypes.CallInvite || e.type == 'm.rtc.notification' || e.type == 'org.matrix.msc4075.rtc.notification';

/// The event a row previews. ponytail: only the SDK's cached last event is looked at (no timeline scan), so a
/// chat whose newest event is a hangup or key request shows no preview; an edit previews its new text.
Event? lastMessage(Room room) {
  final e = room.lastEvent;
  if (e == null) return null;
  if (e.relationshipType == RelationshipTypes.edit) {
    final c = e.content['m.new_content'];
    return c is Map<String, Object?> && !e.redacted ? Event.fromJson({...e.toJson(), 'content': c}, room) : null;
  }
  return isMessage(e) || isCallStart(e) ? e : null;
}

/// Joined and invited rooms (minus spaces, which become folders) in list order, plus the top-level spaces.
({List<RoomRow> rows, List<Room> spaces}) roomRows() {
  final all = client.rooms.where((r) => r.membership == Membership.join || r.membership == Membership.invite).toList();
  final spaces = all.where((r) => r.isSpace && r.membership == Membership.join).toList();
  List<String> children(String id) {
    final r = client.getRoomById(id);
    return r != null && r.isSpace ? [for (final c in r.spaceChildren) ?c.roomId] : [];
  }

  final membership = <String, List<String>>{};
  // top-level = not a child of another joined space
  final nested = {for (final s in spaces) ...children(s.id)};
  final top = spaces.where((s) => !nested.contains(s.id)).toList();
  for (final s in top) {
    for (final id in spaceRooms(s.id, children)) {
      (membership[id] ??= []).add(s.id);
    }
  }
  // space invites are listed like any invite; joined spaces become folders instead
  final rows = [
    for (final room in all.where((r) => !r.isSpace || r.membership == Membership.invite))
      () {
        final invite = room.membership == Membership.invite;
        final last = lastMessage(room);
        return RoomRow(
          room: room, last: last, invite: invite,
          ts: (last?.originServerTs ?? room.latestEventReceivedTime).millisecondsSinceEpoch,
          isDM: room.isDirectChat,
          unread: room.notificationCount,
          spaces: membership[room.id] ?? [],
          pinned: hasTag(room, pinnedTag),
          archived: hasTag(room, archivedTag),
          marked: !invite && room.markedUnread,
          muted: !invite && isMuted(room),
        );
      }(),
  ]..sort(byListOrder);
  return (rows: rows, spaces: top);
}

// ---------- folder order ----------

const _orderType = 'app.panbeh.folder_order';
String _orderKey() => 'panbeh.folderOrder:${client.userID}';

List<String>? remoteFolderOrder() {
  final o = client.accountData[_orderType]?.content['order'];
  return o is List ? o.cast<String>() : null;
}

Future<void> setFolderOrder(List<String> order) async {
  prefs.store.setString(_orderKey(), jsonEncode(order));
  await client.setAccountData(client.userID!, _orderType, {'order': order});
}

/// The local copy, for the first paint before account data arrives.
List<String> cachedFolderOrder() {
  try {
    return (jsonDecode(prefs.store.getString(_orderKey()) ?? '[]') as List).cast<String>();
  } catch (_) {
    return [];
  }
}
