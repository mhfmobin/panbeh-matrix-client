import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

const accents = [0xff3390ec, 0xff8774e1, 0xff40a7a0, 0xffe5864a, 0xffe0578b, 0xff4fae4e];
const themes = {'system': 'سیستم', 'light': 'روشن', 'dark': 'تیره'};
const wallpapers = {'doodle': 'طرح‌دار', 'gradient': 'گرادیان', 'plain': 'ساده'};
const camQualities = {'360': '۳۶۰p', '540': '۵۴۰p', '720': '۷۲۰p', '1080': '۱۰۸۰p'};
const audioQualities = {'low': 'کم', 'normal': 'معمولی', 'high': 'بالا'};

const _defaults = <String, Object>{
  'theme': 'system', 'accent': 0xff3390ec, 'wallpaper': 'doodle',
  // Android asks for the notification permission on first start
  'notify': true, 'notifyDMs': true, 'notifyGroups': true,
  'previews': true, 'shareLastSeen': true, 'dev': false, 'legacyCalls': false, 'enterSends': false,
  'camQuality': '720', 'audioQuality': 'high',
};

/// App settings (Settings → …), one JSON blob like the web app's `panbeh.prefs`.
class Prefs extends ChangeNotifier {
  static const _key = 'panbeh.prefs';
  late SharedPreferences store;
  Map<String, Object?> _m = {};

  Future<void> load() async {
    store = await SharedPreferences.getInstance();
    try {
      _m = (jsonDecode(store.getString(_key) ?? '{}') as Map).cast();
    } catch (_) {}
  }

  T get<T>(String k) => (_m[k] ?? _defaults[k]) as T;

  void set(Map<String, Object?> patch) {
    _m = {..._m, ...patch};
    store.setString(_key, jsonEncode(_m));
    notifyListeners();
  }

  String get theme => get('theme');
  int get accent => get('accent');
  String get wallpaper => get('wallpaper');
  bool get dev => get('dev');
  // on a phone keyboard Enter is a new line, like Telegram; the send button sends
  bool get enterSends => get('enterSends');
  bool get legacyCallsOn => dev && get<bool>('legacyCalls');
}

final prefs = Prefs();
