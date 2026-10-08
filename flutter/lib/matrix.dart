import 'dart:async';

import 'package:app_links/app_links.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_vodozemac/flutter_vodozemac.dart' as vod;
import 'package:matrix/encryption.dart';
import 'package:matrix/matrix.dart';
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqflite/sqflite.dart' as sqflite;
import 'package:url_launcher/url_launcher.dart';

import 'logic.dart';

// Several accounts, one running client at a time (like the web app). Each account is an SDK client name,
// which also names its database; the SDK keeps the session and keys in there.
const _clientsKey = 'panbeh.clients', _activeKey = 'panbeh.active', lastServerKey = 'panbeh.lastServer';

/// The running client. Set by [start] / login.
late Client client;
bool hasClient = false;

late SharedPreferences _sp;
List<String> get clientNames => _sp.getStringList(_clientsKey) ?? [];
String? get activeName => _sp.getString(_activeKey);

Future<void> initStorage() async {
  _sp = await SharedPreferences.getInstance();
  await vod.init();
}

Future<MatrixSdkDatabase> _openDb(String name) async {
  final dir = await getApplicationSupportDirectory();
  return MatrixSdkDatabase.init(
    name,
    database: await sqflite.openDatabase('${dir.path}/$name.sqlite'),
    sqfliteFactory: sqflite.databaseFactory,
    fileStorageLocation: Uri.directory('${dir.path}/media'),
    maxFileSize: 20 * 1024 * 1024,
    deleteFilesAfterDuration: const Duration(days: 30),
  );
}

Future<Client> _make(String name) async {
  return Client(
    name,
    database: await _openDb(name),
    // emoji only: we can't show or scan QR codes, so don't let the other side pick them
    verificationMethods: {KeyVerificationMethod.emoji, KeyVerificationMethod.numbers},
    nativeImplementations: NativeImplementationsIsolate(compute, vodozemacInit: () => vod.init()),
    importantStateEvents: {'m.room.pinned_events', 'm.room.join_rules', 'm.room.history_visibility', 'm.room.server_acl'},
    logLevel: kReleaseMode ? Level.warning : Level.info,
  );
}

/// Opens the active account's saved session. False if there is none (or it was logged out).
Future<bool> start() async {
  final name = activeName;
  if (name == null) return false;
  final c = await _make(name);
  await c.init(waitForFirstSync: false, waitUntilLoadCompletedLoaded: true);
  if (!c.isLogged()) {
    await _drop(name);
    return false;
  }
  _use(c);
  return true;
}

void _use(Client c) {
  client = c;
  hasClient = true;
  // token revoked elsewhere: drop the account and go back to login (or the next account)
  c.onLoginStateChanged.stream.where((s) => s == LoginState.loggedOut).first.then((_) async {
    if (!hasClient || client != c) return; // our own logout() / switch
    hasClient = false;
    await _drop(c.clientName);
    onLoggedOut?.call();
  });
}

/// Set by the app shell: shows the login screen (or the next account).
void Function()? onLoggedOut;

Future<void> _activate(Client c) async {
  if (hasClient) await client.dispose().catchError((_) {}); // adding an account: the old one stops
  final names = clientNames.where((n) => n != c.clientName).toList()..add(c.clientName);
  await _sp.setStringList(_clientsKey, names);
  await _sp.setString(_activeKey, c.clientName);
  _use(c);
}

Future<void> _drop(String name) async {
  final left = clientNames.where((n) => n != name).toList();
  await _sp.setStringList(_clientsKey, left);
  if (left.isEmpty) {
    await _sp.remove(_activeKey);
  } else {
    await _sp.setString(_activeKey, left.first);
  }
}

String _newName() => 'panbeh-${DateTime.now().millisecondsSinceEpoch}';

/// How this server logs in: on its own page (OAuth, e.g. MAS) or with a password. Throws if it isn't reachable.
Future<String> loginMode(String server) async {
  final c = Client('probe', database: await MatrixSdkDatabase.init('probe', database: await sqflite.openDatabase(sqflite.inMemoryDatabasePath)));
  final (_, _, _, meta) = await c.checkHomeserver(Uri.parse(normalizeServer(server)), fetchAuthMetadata: true);
  return meta != null ? 'oauth' : 'password';
}

Future<void> login(String server, String user, String password) async {
  final c = await _make(_newName());
  try {
    await c.checkHomeserver(Uri.parse(normalizeServer(server)));
    await c.login(LoginType.mLoginPassword,
        identifier: AuthenticationUserIdentifier(user: user), password: password, initialDeviceDisplayName: 'Panbeh Android');
  } catch (_) {
    await c.database.delete();
    rethrow;
  }
  await _activate(c);
}

// ---------- OAuth 2.0 (next-gen auth, e.g. MAS) ----------

// The browser hands the code back through the app's own URL scheme. MAS wants a native app's scheme to be its
// client_uri's host reversed. ponytail: flutter.panbeh.ir is never fetched; switch both with the package id.
final _redirect = Uri.parse('ir.panbeh.flutter:/oauth');
final _clientUri = Uri.parse('https://flutter.panbeh.ir/');

/// Registers Panbeh with the server's auth service, opens its login page in the browser, and logs in when the
/// browser comes back. ponytail: the pending login lives in memory; if Android kills us meanwhile, the user starts over.
Future<void> loginOAuth(String server) async {
  final c = await _make(_newName());
  try {
    await c.checkHomeserver(Uri.parse(normalizeServer(server)), fetchAuthMetadata: true);
    final reg = await c.registerOidcClient(
      redirectUris: [_redirect],
      applicationType: OidcApplicationType.native,
      clientInformation: OidcClientInformation(clientName: 'Panbeh', clientUri: _clientUri, logoUri: null, tosUri: null, policyUri: null),
    );
    final session = await c.initOidcLoginSession(oidcClientData: reg, redirectUri: _redirect);
    final back = AppLinks().uriLinkStream.firstWhere((u) => u.scheme == _redirect.scheme);
    await launchUrl(session.authenticationUri, mode: LaunchMode.externalApplication);
    final q = (await back).queryParameters;
    if (q['error'] != null) throw Exception(q['error_description'] ?? q['error']);
    await c.oidcLogin(session: session, code: q['code'] ?? '', state: q['state'] ?? '');
  } catch (_) {
    await c.database.delete();
    rethrow;
  }
  await _activate(c);
}

/// Drops the current account (and its keys) and switches to the next one, if any. False = none left.
Future<bool> logout() async {
  hasClient = false;
  await _drop(client.clientName);
  try {
    await client.logout().timeout(const Duration(seconds: 5));
  } catch (_) {
    // offline or already revoked: still forget it here
    await client.clear().catchError((_) {});
  }
  await client.dispose().catchError((_) {});
  return start();
}

/// The other saved accounts, for the switcher.
Future<List<({String name, String userId})>> otherAccounts() async {
  final out = <({String name, String userId})>[];
  for (final n in clientNames.where((n) => n != client.clientName)) {
    final db = await _openDb(n);
    final s = await db.getClient(n);
    if (s?['user_id'] is String) out.add((name: n, userId: s!['user_id'] as String));
    await db.close();
  }
  return out;
}

Future<void> switchAccount(String name) async {
  await client.dispose().catchError((_) {});
  hasClient = false;
  await _sp.setString(_activeKey, name);
  await start();
}
