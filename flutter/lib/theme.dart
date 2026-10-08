import 'package:flutter/cupertino.dart' show CupertinoPageTransitionsBuilder;
import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

import 'prefs.dart';

/// `color-mix(in srgb, a p%, b)`
Color mix(Color a, double p, Color b) => Color.lerp(b, a, p)!;

/// The web app's CSS tokens (styles.css :root), for both themes.
class Tokens extends ThemeExtension<Tokens> {
  final Color accent, bg, panel, hover, text, muted, border, bubbleIn, bubbleOut, outText, outMeta, pill, chatBg, readTick;
  const Tokens({required this.accent, required this.bg, required this.panel, required this.hover, required this.text, required this.muted,
    required this.border, required this.bubbleIn, required this.bubbleOut, required this.outText, required this.outMeta, required this.pill,
    required this.chatBg, required this.readTick});

  factory Tokens.of(Color accent, bool dark) {
    if (dark) {
      const panel = Color(0xff212121);
      return Tokens(accent: accent, bg: const Color(0xff181818), panel: panel, hover: const Color(0xff2c2c2c), text: Colors.white,
        muted: const Color(0xffaaaaaa), border: const Color(0xff303030), bubbleIn: panel, bubbleOut: mix(accent, .7, panel),
        outText: Colors.white, outMeta: Colors.white70, pill: Colors.black38, chatBg: mix(accent, .18, const Color(0xff0f0f0f)), readTick: Colors.white70);
    }
    return Tokens(accent: accent, bg: Colors.white, panel: Colors.white, hover: const Color(0xfff4f4f5), text: Colors.black,
      muted: const Color(0xff707579), border: const Color(0xffe6e6e6), bubbleIn: Colors.white, bubbleOut: mix(accent, .14, Colors.white),
      outText: Colors.black, outMeta: mix(accent, .8, Colors.black), pill: mix(accent, .45, const Color(0xff506070)),
      chatBg: mix(accent, .12, const Color(0xffe8efe0)), readTick: const Color(0xff4fae4e));
  }

  @override
  Tokens copyWith() => this;
  @override
  Tokens lerp(Tokens? other, double t) => other ?? this;
}

extension TokensX on BuildContext {
  Tokens get tk => Theme.of(this).extension<Tokens>()!;
}

ThemeData buildTheme(Color accent, bool dark) {
  final t = Tokens.of(accent, dark);
  final scheme = ColorScheme.fromSeed(seedColor: accent, brightness: dark ? Brightness.dark : Brightness.light)
      .copyWith(primary: accent, onPrimary: Colors.white, surface: t.panel, onSurface: t.text, outlineVariant: t.border);
  return ThemeData(
    colorScheme: scheme,
    fontFamily: 'Vazirmatn',
    scaffoldBackgroundColor: t.bg,
    dividerColor: t.border,
    extensions: [t],
    // Telegram Android: a coloured app bar in light mode, a dark one in dark mode
    appBarTheme: AppBarTheme(
      backgroundColor: dark ? t.panel : accent, foregroundColor: Colors.white, elevation: 0, scrolledUnderElevation: 0,
      titleTextStyle: const TextStyle(fontFamily: 'Vazirmatn', fontSize: 19, fontWeight: FontWeight.w600, color: Colors.white),
    ),
    floatingActionButtonTheme: FloatingActionButtonThemeData(backgroundColor: accent, foregroundColor: Colors.white, shape: const CircleBorder()),
    listTileTheme: ListTileThemeData(iconColor: t.muted),
    pageTransitionsTheme: const PageTransitionsTheme(builders: {
      TargetPlatform.android: CupertinoPageTransitionsBuilder(), // swipe from the edge to go back, as in Telegram
    }),
  );
}

// the doodle from styles.css, recoloured per theme
const _doodle = '''<svg xmlns='http://www.w3.org/2000/svg' width='160' height='160' fill='none' stroke='#000' stroke-width='1.6' stroke-linecap='round' stroke-linejoin='round'><path d='M20 30c0-6 8-8 10-2 2-6 10-4 10 2 0 7-10 12-10 12s-10-5-10-12z'/><circle cx='120' cy='30' r='9'/><path d='M70 70l4 9 10 1-7 7 2 10-9-5-9 5 2-10-7-7 10-1z'/><path d='M20 110h24v18H20zM20 110l12 10 12-10'/><path d='M110 100c10-10 26 6 16 16l-10 10'/><path d='M130 70q5-10 10 0t10 0'/><path d='M70 140a8 8 0 1 0 16 0'/></svg>''';

/// Chat background per the wallpaper setting.
class Wallpaper extends StatelessWidget {
  final Widget child;
  final String? kind; // Settings' previews pass their own
  const Wallpaper({super.key, required this.child, this.kind});

  @override
  Widget build(BuildContext context) {
    final t = context.tk;
    final dark = Theme.of(context).brightness == Brightness.dark;
    final k = kind ?? prefs.wallpaper;
    final bg = switch (k) {
      'doodle' => BoxDecoration(gradient: LinearGradient(begin: Alignment.topLeft, end: Alignment.bottomRight,
          colors: [mix(t.accent, .22, t.chatBg), t.chatBg], stops: const [0, .6])),
      'gradient' => BoxDecoration(color: t.chatBg, gradient: RadialGradient(center: const Alignment(-.7, -.6), radius: 1.2,
          colors: [mix(t.accent, .45, t.chatBg), mix(const Color(0xfff6c177), .3, t.chatBg), mix(const Color(0xff7bc8a4), .3, t.chatBg)])),
      _ => BoxDecoration(color: t.chatBg),
    };
    return DecoratedBox(
      decoration: bg,
      child: Stack(fit: StackFit.expand, children: [
        if (k == 'doodle')
          Opacity(opacity: dark ? .05 : .07, child: _Tiled(color: dark ? Colors.white : Colors.black)),
        child,
      ]),
    );
  }
}

class _Tiled extends StatelessWidget {
  final Color color;
  const _Tiled({required this.color});
  @override
  Widget build(BuildContext context) => LayoutBuilder(builder: (context, c) {
    const s = 160.0;
    return ClipRect(child: Wrap(children: [
      for (var i = 0; i < ((c.maxWidth / s).ceil() * (c.maxHeight / s).ceil()); i++)
        SvgPicture.string(_doodle, width: s, height: s, colorFilter: ColorFilter.mode(color, BlendMode.srcIn)),
    ]));
  });
}
