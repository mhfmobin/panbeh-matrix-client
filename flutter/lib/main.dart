import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

import 'matrix.dart';
import 'prefs.dart';
import 'theme.dart';
import 'ui/chat_list.dart';
import 'ui/login.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await prefs.load();
  await initStorage();
  runApp(const App());
}

final navigator = GlobalKey<NavigatorState>();

class App extends StatelessWidget {
  const App({super.key});

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: prefs,
    builder: (context, _) {
      final accent = Color(prefs.accent);
      return MaterialApp(
        title: 'پنبه',
        navigatorKey: navigator,
        debugShowCheckedModeBanner: false,
        locale: const Locale('fa'),
        supportedLocales: const [Locale('fa')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        themeMode: switch (prefs.theme) { 'light' => ThemeMode.light, 'dark' => ThemeMode.dark, _ => ThemeMode.system },
        theme: buildTheme(accent, false),
        darkTheme: buildTheme(accent, true),
        home: const Root(),
      );
    },
  );
}

/// Boot → login or the chat list. Switching or adding accounts comes back through here.
class Root extends StatefulWidget {
  const Root({super.key});
  @override
  State<Root> createState() => RootState();
}

enum Phase { boot, login, ready, error }

class RootState extends State<Root> {
  Phase phase = Phase.boot;
  bool adding = false; // login screen over a running account

  @override
  void initState() {
    super.initState();
    onLoggedOut = () => restart();
    restart();
  }

  /// Re-reads the active account: after boot, logout, or an account switch.
  Future<void> restart() async {
    setState(() => phase = Phase.boot);
    navigator.currentState?.popUntil((r) => r.isFirst);
    try {
      // keep the session on failure: dropping it silently would orphan this device and its keys
      final ok = hasClient || await start();
      setState(() => phase = ok ? Phase.ready : Phase.login);
    } catch (e, s) {
      debugPrint('start failed: $e\n$s');
      setState(() => phase = Phase.error);
    }
  }

  void addAccount() => setState(() => adding = true);

  @override
  Widget build(BuildContext context) {
    if (adding || phase == Phase.login) {
      return Login(
        onDone: () { adding = false; restart(); },
        onCancel: adding ? () => setState(() => adding = false) : null,
      );
    }
    return switch (phase) {
      Phase.boot => const Splash('در حال راه‌اندازی…'),
      Phase.error => Splash('راه‌اندازی ممکن نشد.', actions: [
        FilledButton(onPressed: restart, child: const Text('تلاش دوباره')),
        TextButton(onPressed: () async { await logout(); restart(); }, child: const Text('خروج', style: TextStyle(color: Colors.red))),
      ]),
      _ => StreamBuilder(
        // the cached rooms show at once; only a first-ever login waits for the initial sync
        stream: client.onSync.stream,
        builder: (context, _) => client.prevBatch == null ? const Splash('در حال همگام‌سازی گفتگوها…') : ChatList(key: ValueKey(client.clientName)),
      ),
    };
  }
}

class Splash extends StatelessWidget {
  final String text;
  final List<Widget>? actions;
  const Splash(this.text, {super.key, this.actions});

  @override
  Widget build(BuildContext context) => Scaffold(
    body: Wallpaper(child: Center(child: Column(mainAxisSize: MainAxisSize.min, spacing: 16, children: [
      const Logo(),
      Row(mainAxisSize: MainAxisSize.min, spacing: 10, children: [
        if (actions == null) const SizedBox.square(dimension: 18, child: CircularProgressIndicator(strokeWidth: 2)),
        Text(text),
      ]),
      ...?actions,
    ]))),
  );
}

class Logo extends StatelessWidget {
  const Logo({super.key});
  @override
  Widget build(BuildContext context) => CircleAvatar(
    radius: 40, backgroundColor: context.tk.accent,
    child: const Text('✦', style: TextStyle(fontSize: 38, color: Colors.white)),
  );
}
