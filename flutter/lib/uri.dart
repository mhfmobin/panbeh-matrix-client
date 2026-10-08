/// Parsing of matrix.to links and `matrix:` URIs (MSC2312 / Matrix spec appendix). Pure: no SDK, no DOM.
class Target {
  /// "user" | "room" | "roomAlias"
  final String kind;
  /// Full identifier with sigil: @user:server, !room:server or #alias:server.
  final String id;
  final String? eventId;
  final List<String> via;
  /// "join" | "chat"
  final String? action;
  const Target({required this.kind, required this.id, this.eventId, this.via = const [], this.action});

  @override
  String toString() => 'Target($kind, $id, $eventId, $via, $action)';
  @override
  bool operator ==(Object other) => other is Target && other.toString() == toString();
  @override
  int get hashCode => toString().hashCode;
}

String? _dec(String s) { try { return Uri.decodeComponent(s); } catch (_) { return null; } }
final _user = RegExp(r'^@[^:\s]+:\S+$'), _room = RegExp(r'^![^\s]+$'), _alias = RegExp(r'^#[^:\s]+:\S+$'), _event = RegExp(r'^\$\S+$');

// URLSearchParams semantics: "+" is a space, bad escapes stay literal
List<MapEntry<String, String>> _params(String query) => [
  for (final p in query.split('&')) if (p.isNotEmpty) () {
    final i = p.indexOf('=');
    return i < 0 ? MapEntry(_q(p), '') : MapEntry(_q(p.substring(0, i)), _q(p.substring(i + 1)));
  }(),
];
String _q(String s) { try { return Uri.decodeQueryComponent(s); } catch (_) { return s; } }

Target? _build(String? id, String? eventId, String query) {
  if (id == null || id.isEmpty) return null;
  final kind = _user.hasMatch(id) ? 'user' : _alias.hasMatch(id) ? 'roomAlias' : _room.hasMatch(id) ? 'room' : null;
  if (kind == null) return null;
  if (eventId != null && (kind == 'user' || !_event.hasMatch(eventId))) return null;
  final q = _params(query);
  final action = q.where((e) => e.key == 'action').firstOrNull?.value;
  return Target(
    kind: kind, id: id,
    eventId: eventId != null && eventId.isNotEmpty ? eventId : null,
    via: [for (final e in q) if (e.key == 'via' && e.value.isNotEmpty) e.value],
    action: action == 'join' || action == 'chat' ? action : null,
  );
}

/// `https://matrix.to/#/<id>[/<$event>][?via=…]`
Target? _fromMatrixTo(String s) {
  final m = RegExp(r'^https:\/\/matrix\.to\/#\/([^?]*)(?:\?(.*))?$', caseSensitive: false).firstMatch(s);
  if (m == null) return null;
  final p = m[1]!.split('/');
  if (p.length > 2) return null;
  return _build(_dec(p[0]), p.length < 2 || p[1].isEmpty ? null : _dec(p[1]), m[2] ?? '');
}

/// `matrix:u/user:server`, `matrix:r/alias:server[/e/event]`, `matrix:roomid/room:server[/e/event]`
Target? _fromMatrixUri(String s) {
  final m = RegExp(r'^matrix:(?:\/\/[^/]*\/)?([^?#]*)(?:\?([^#]*))?(?:#.*)?$', caseSensitive: false).firstMatch(s);
  if (m == null) return null;
  final parts = m[1]!.split('/');
  final sigil = const {'u': '@', 'r': '#', 'roomid': '!'}[parts[0].toLowerCase()];
  final name = parts.length > 1 && parts[1].isNotEmpty ? _dec(parts[1]) : null;
  if (sigil == null || name == null || name.isEmpty) return null;
  String? eventId;
  if (parts.length == 4 && parts[2] == 'e' && parts[3].isNotEmpty) {
    final e = _dec(parts[3]);
    if (e == null || e.isEmpty) return null;
    eventId = '\$$e';
  } else if (parts.length != 2) {
    return null;
  }
  return _build(sigil + name, eventId, m[2] ?? '');
}

Target? parseMatrixLink(String s) {
  final t = s.trim();
  return RegExp(r'^matrix:', caseSensitive: false).hasMatch(t) ? _fromMatrixUri(t) : _fromMatrixTo(t);
}

/// The `matrix.to` hash form (`#/!room:server/$event`) of a page hash such as location.hash.
Target? parseMatrixHash(String hash) => hash.startsWith('#/') ? _fromMatrixTo('https://matrix.to/$hash') : null;
