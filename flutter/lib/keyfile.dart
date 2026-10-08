// Megolm key-export files (the format Element reads/writes).
// Body: 0x01 | salt(16) | iv(16) | rounds(4, BE) | AES-256-CTR ciphertext | HMAC-SHA256 of everything before it.
import 'dart:convert';
import 'dart:isolate';
import 'dart:math';
import 'dart:typed_data';

import 'package:cryptography/cryptography.dart';
import 'package:matrix/encryption/utils/session_key.dart';
import 'package:matrix/matrix.dart';

const _header = '-----BEGIN MEGOLM SESSION DATA-----';
const _footer = '-----END MEGOLM SESSION DATA-----';

// 500k PBKDF2-SHA512 rounds are slow in pure Dart, so derive off the UI isolate.
Future<List<int>> _derive(String pass, List<int> salt, int rounds) => Isolate.run(() async {
  final k = await Pbkdf2(macAlgorithm: Hmac.sha512(), iterations: rounds, bits: 512).deriveKeyFromPassword(password: pass, nonce: salt);
  return k.extractBytes();
});

final _aes = AesCtr.with256bits(macAlgorithm: MacAlgorithm.empty);
final _hmac = Hmac.sha256();

/// Encrypts the exported-sessions JSON into an armored key file.
Future<String> encryptKeyFile(String json, String passphrase, [int rounds = 500000]) async {
  final rnd = Random.secure();
  final salt = Uint8List.fromList(List.generate(16, (_) => rnd.nextInt(256)));
  final iv = Uint8List.fromList(List.generate(16, (_) => rnd.nextInt(256)));
  iv[8] &= 0x7f; // clear bit 63 of the counter so it can't wrap (as Element does)
  final bits = await _derive(passphrase, salt, rounds);
  final ct = (await _aes.encrypt(utf8.encode(json), secretKey: SecretKey(bits.sublist(0, 32)), nonce: iv)).cipherText;
  final body = BytesBuilder()
    ..addByte(1)
    ..add(salt)
    ..add(iv)
    ..add((ByteData(4)..setUint32(0, rounds)).buffer.asUint8List())
    ..add(ct);
  final mac = await _hmac.calculateMac(body.toBytes(), secretKey: SecretKey(bits.sublist(32)));
  body.add(mac.bytes);
  final b64 = base64.encode(body.toBytes());
  return [_header, for (var i = 0; i < b64.length; i += 96) b64.substring(i, min(i + 96, b64.length)), _footer, ''].join('\n');
}

/// Decrypts a key file back to the sessions JSON. Throws on a wrong passphrase.
Future<String> decryptKeyFile(String file, String passphrase) async {
  final a = file.indexOf(_header), b = file.indexOf(_footer);
  if (a < 0 || b < a) throw Exception('این فایل، فایل کلید رمزنگاری نیست');
  final Uint8List body;
  try {
    body = base64.decode(file.substring(a + _header.length, b).replaceAll(RegExp(r'\s+'), ''));
  } on FormatException {
    throw Exception('این فایل، فایل کلید رمزنگاری نیست');
  }
  if (body.isEmpty || body[0] != 1 || body.length < 1 + 16 + 16 + 4 + 32) throw Exception('نسخه‌ی فایل کلید پشتیبانی نمی‌شود');
  final end = body.length - 32;
  final rounds = ByteData.sublistView(body, 33, 37).getUint32(0);
  final bits = await _derive(passphrase, body.sublist(1, 17), rounds);
  final mac = await _hmac.calculateMac(body.sublist(0, end), secretKey: SecretKey(bits.sublist(32)));
  var diff = 0; // constant-time compare
  for (var i = 0; i < 32; i++) {
    diff |= mac.bytes[i] ^ body[end + i];
  }
  if (diff != 0) throw Exception('عبارت عبور درست نیست');
  final pt = await _aes.decrypt(SecretBox(body.sublist(37, end), nonce: body.sublist(17, 33), mac: Mac.empty), secretKey: SecretKey(bits.sublist(0, 32)));
  return utf8.decode(pt);
}

/// Every inbound group session this device has, as an encrypted key file.
Future<String> exportKeys(Client client, String passphrase) async {
  final out = [];
  for (final row in await client.database.getAllInboundGroupSessions()) {
    try {
      final s = SessionKey.fromDb(row, client.userID!);
      if (!s.isValid) continue;
      out.add({
        'algorithm': AlgorithmTypes.megolmV1AesSha2,
        'room_id': s.roomId,
        'sender_key': s.senderKey,
        'session_id': s.sessionId,
        'session_key': s.inboundGroupSession!.exportAtFirstKnownIndex(),
        'sender_claimed_keys': s.senderClaimedKeys,
        'forwarding_curve25519_key_chain': s.forwardingCurve25519KeyChain,
      });
    } catch (_) {} // unreadable session: skip it
  }
  return encryptKeyFile(jsonEncode(out), passphrase);
}

/// Imports a key file; returns how many sessions were imported.
/// setInboundGroupSession(forwarded: true) uses vodozemac InboundGroupSession.import, stores the session,
/// and fires room.onSessionKeyReceived, which open Timelines listen to in order to retry decryption.
Future<int> importKeys(Client client, String fileText, String passphrase, {void Function(int done, int total)? onProgress}) async {
  final List list;
  try {
    final j = jsonDecode(await decryptKeyFile(fileText, passphrase));
    if (j is! List) throw const FormatException();
    list = j;
  } on FormatException {
    throw Exception('محتوای فایل کلید نامعتبر است');
  }
  var n = 0;
  for (var i = 0; i < list.length; i++) {
    final e = list[i];
    try {
      if (e is Map && e['room_id'] is String && e['session_id'] is String && e['sender_key'] is String && e['session_key'] is String) {
        final content = Map<String, dynamic>.from(e);
        await client.encryption!.keyManager.setInboundGroupSession(
          e['room_id'], e['session_id'], e['sender_key'], content,
          forwarded: true,
          senderClaimedKeys: (e['sender_claimed_keys'] is Map) ? Map<String, String>.from((e['sender_claimed_keys'] as Map).map((k, v) => MapEntry('$k', '$v'))) : null,
        );
        n++;
      }
    } catch (_) {} // malformed entry: skip
    onProgress?.call(i + 1, list.length);
  }
  return n;
}
