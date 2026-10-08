import 'package:flutter/material.dart';

import '../main.dart';
import '../matrix.dart';
import '../prefs.dart';
import '../theme.dart';
import 'common.dart';

/// Own profile (name + avatar) for the drawer and settings.
Future<({String name, Uri? avatar})> ownProfile() async {
  try {
    final p = await client.fetchOwnProfile();
    return (name: p.displayName ?? client.userID!, avatar: p.avatarUrl);
  } catch (_) {
    return (name: client.userID!, avatar: null);
  }
}

/// «خروج از این حساب»: confirm, log out, and land on the next account or the login screen.
Future<void> confirmLogout(BuildContext context) async {
  final root = context.findAncestorStateOfType<RootState>();
  if (!await confirm(context, 'از این حساب خارج می‌شوید؟', ok: 'خروج', danger: true)) return;
  await logout();
  root?.restart();
}

// ponytail: only profile + appearance + logout for now; the rest of Settings comes in phase 4
class SettingsPage extends StatelessWidget {
  const SettingsPage({super.key});

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('تنظیمات')),
    body: ListenableBuilder(
      listenable: prefs,
      builder: (context, _) {
        final t = context.tk;
        return ListView(children: [
          Section(children: [
            FutureBuilder(
              future: ownProfile(),
              builder: (context, s) => Padding(
                padding: const EdgeInsets.all(20),
                child: Row(spacing: 16, children: [
                  Avatar(mxc: s.data?.avatar, name: s.data?.name ?? '', id: client.userID!, size: 64),
                  Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(s.data?.name ?? '', style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w600), maxLines: 1, overflow: TextOverflow.ellipsis),
                    Text(client.userID!, textDirection: TextDirection.ltr, style: TextStyle(color: t.muted)),
                  ])),
                ]),
              ),
            ),
          ]),
          Section(title: 'ظاهر', children: [
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              child: SegmentedButton<String>(
                showSelectedIcon: false,
                segments: [for (final e in themes.entries) ButtonSegment(value: e.key, label: Text(e.value))],
                selected: {prefs.theme},
                onSelectionChanged: (v) => prefs.set({'theme': v.first}),
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              child: Wrap(spacing: 14, runSpacing: 10, children: [
                for (final a in accents)
                  InkResponse(
                    onTap: () => prefs.set({'accent': a}),
                    child: CircleAvatar(radius: 20, backgroundColor: Color(a),
                      child: prefs.accent == a ? const Icon(Icons.check, color: Colors.white) : null),
                  ),
              ]),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
              child: Row(spacing: 12, children: [
                for (final w in wallpapers.entries)
                  Expanded(child: GestureDetector(
                    onTap: () => prefs.set({'wallpaper': w.key}),
                    child: Column(spacing: 6, children: [
                      Container(
                        height: 110,
                        clipBehavior: Clip.antiAlias,
                        decoration: BoxDecoration(
                          borderRadius: BorderRadius.circular(12),
                          border: Border.all(color: prefs.wallpaper == w.key ? t.accent : t.border, width: prefs.wallpaper == w.key ? 3 : 1),
                        ),
                        child: Wallpaper(kind: w.key, child: const SizedBox()),
                      ),
                      Text(w.value, style: TextStyle(fontSize: 13, color: prefs.wallpaper == w.key ? t.accent : t.muted)),
                    ]),
                  )),
              ]),
            ),
          ]),
          Section(children: [
            ListTile(
              leading: const Icon(Icons.logout, color: Color(0xffe53935)),
              title: const Text('خروج از این حساب', style: TextStyle(color: Color(0xffe53935))),
              onTap: () => confirmLogout(context),
            ),
          ]),
        ]);
      },
    ),
  );
}
