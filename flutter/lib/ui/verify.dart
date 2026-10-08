import 'dart:async';

import 'package:flutter/material.dart';
import 'package:matrix/encryption.dart';
import 'package:matrix/matrix.dart';

import '../logic.dart';
import '../matrix.dart';
import '../theme.dart';
import 'common.dart';
import 'encryption.dart';

// ---------- the one verification on screen (incoming or started from Settings/Profile) ----------
bool _showing = false;

/// Shows incoming verification requests; secrets arrive by gossip after self-verification, so the recovery state is re-checked afterwards.
class VerificationGate extends StatefulWidget {
  final Widget child;
  const VerificationGate({super.key, required this.child});
  @override
  State<VerificationGate> createState() => _GateState();
}

class _GateState extends State<VerificationGate> {
  StreamSubscription? sub;

  @override
  void initState() {
    super.initState();
    // one at a time: a second request while one is in progress is left to time out
    sub = client.onKeyVerificationRequest.stream.listen((kv) {
      if (!_showing && mounted) showVerification(context, kv);
    });
  }

  @override
  void dispose() {
    sub?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}

Future<void> showVerification(BuildContext context, KeyVerification kv) async {
  if (_showing) return;
  _showing = true;
  try {
    await showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      isDismissible: false,
      enableDrag: false,
      builder: (_) => _VerifySheet(kv),
    );
  } finally {
    _showing = false;
    // the secrets come a moment after "done"
    for (final s in [0, 3, 10]) {
      Timer(Duration(seconds: s), refreshRecovery);
    }
  }
}

Future<void> _start(BuildContext context, Future<KeyVerification> Function() f) async {
  if (_showing) return;
  try {
    final kv = await f();
    if (context.mounted) await showVerification(context, kv);
  } catch (e) {
    if (context.mounted) await alert(context, errText(e));
  }
}

/// Another device of ours verifies this one (we're the unverified side).
Future<void> verifySelf(BuildContext context) => _start(context, () async {
  final keys = client.userDeviceKeys[me()];
  if (keys == null) throw Exception('کلیدهای دستگاه‌ها هنوز بارگذاری نشده است.');
  return keys.startVerification();
});

Future<void> verifyUser(BuildContext context, String userId) {
  if (userId == me()) return verifySelf(context);
  return _start(context, () async {
    var keys = client.userDeviceKeys[userId];
    if (keys == null) {
      await client.updateUserDeviceKeys(additionalUsers: {userId});
      keys = client.userDeviceKeys[userId];
    }
    if (keys == null) throw Exception('این کاربر رمزنگاری ندارد.');
    return keys.startVerification();
  });
}

Future<void> verifyDevice(BuildContext context, DeviceKeys device) => _start(context, device.startVerification);

// ---------- trust ----------

/// Cross-signing trust of a user, as a label for profiles.
String trustLabel(String userId) {
  final ok = userId == me()
      ? client.userDeviceKeys[userId]?.masterKey?.directVerified ?? false
      : client.userDeviceKeys[userId]?.masterKey?.verified ?? false;
  return ok ? 'هویت تأییدشده' : 'هویت تأییدنشده';
}

/// «تأییدشده» / «تأییدنشده» for a device row; null if it has no keys.
String? deviceTrustLabel(String userId, String deviceId) {
  final d = client.userDeviceKeys[userId]?.deviceKeys[deviceId];
  return d == null ? null : d.verified ? 'تأییدشده' : 'تأییدنشده';
}

// ---------- the sheet ----------

class _VerifySheet extends StatefulWidget {
  final KeyVerification kv;
  const _VerifySheet(this.kv);
  @override
  State<_VerifySheet> createState() => _VerifySheetState();
}

class _VerifySheetState extends State<_VerifySheet> {
  KeyVerification get kv => widget.kv;
  String error = '';
  bool confirmed = false, chose = false, skipped = false;

  @override
  void initState() {
    super.initState();
    kv.onUpdate = () { if (mounted) setState(() {}); };
  }

  @override
  void dispose() {
    kv.onUpdate = null;
    super.dispose();
  }

  Future<void> run(Future<void> Function() f) async {
    try {
      await f();
    } catch (e) {
      if (mounted) setState(() => error = errText(e));
    }
  }

  void close() {
    if (!kv.isDone) kv.cancel('m.user').catchError((_) {});
    Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tk;
    final self = kv.userId == me();
    final other = self ? 'دستگاه دیگرتان' : bdi(kv.room?.unsafeGetUserFromMemoryOrFallback(kv.userId).calcDisplayname() ?? kv.userId);
    const spin = Padding(padding: EdgeInsets.all(16), child: SizedBox.square(dimension: 24, child: CircularProgressIndicator(strokeWidth: 2)));
    Widget actions(List<Widget> w) => Row(spacing: 12, mainAxisAlignment: MainAxisAlignment.end, children: w);
    Widget text(String s) => Text(s, textAlign: TextAlign.center, style: const TextStyle(fontSize: 15));

    // we have no secrets to unlock here: they arrive by gossip once the other device has verified us
    if (kv.state == KeyVerificationState.askSSSS && !skipped) {
      skipped = true;
      WidgetsBinding.instance.addPostFrameCallback((_) => run(() => kv.openSSSS(skip: true)));
    }
    // we asked: start the emoji comparison as soon as the other side accepts
    if (kv.state == KeyVerificationState.askChoice && kv.startedVerification && !chose) {
      chose = true;
      WidgetsBinding.instance.addPostFrameCallback((_) => run(() => kv.continueVerification(EventTypes.Sas)));
    }

    final Widget body;
    switch (kv.state) {
      case KeyVerificationState.askAccept:
        body = Column(spacing: 12, children: [
          text(self ? 'یکی دیگر از دستگاه‌های شما می‌خواهد این دستگاه را تأیید کند.' : '$other می‌خواهد هویت شما را تأیید کند.'),
          actions([
            TextButton(onPressed: close, child: const Text('رد کردن')),
            FilledButton(onPressed: () => run(kv.acceptVerification), child: const Text('پذیرفتن')),
          ]),
        ]);
      case KeyVerificationState.waitingAccept when kv.startedVerification && !chose:
        body = Column(children: [
          text(self ? 'پنبه یا برنامه‌ی دیگری را که با آن وارد شده‌اید باز کنید و درخواست تأیید را بپذیرید.' : 'منتظر پذیرش درخواست توسط $other…'),
          spin,
        ]);
      case KeyVerificationState.askChoice when !kv.startedVerification:
        body = Column(spacing: 12, children: [
          text('درخواست پذیرفته شد. شکلک‌ها را در هر دو دستگاه مقایسه کنید.'),
          FilledButton(onPressed: () => run(() => kv.continueVerification(EventTypes.Sas)), child: const Text('مقایسه‌ی شکلک‌ها')),
        ]);
      case KeyVerificationState.askSas:
        final emoji = kv.sasTypes.contains('emoji') ? kv.sasEmojis : null;
        body = Column(spacing: 12, children: [
          text('تأیید کنید که ${emoji != null ? 'شکلک‌های' : 'عددهای'} زیر در $other هم به همین ترتیب نمایش داده می‌شوند.'),
          if (emoji != null)
            Directionality(textDirection: TextDirection.ltr, child: Wrap(alignment: WrapAlignment.center, spacing: 8, runSpacing: 10, children: [
              for (final e in emoji)
                SizedBox(width: 72, child: Column(children: [
                  Text(e.emoji, style: const TextStyle(fontSize: 34)),
                  Text(emojiFa[e.name.toLowerCase()] ?? e.name, textAlign: TextAlign.center, style: TextStyle(fontSize: 12, color: t.muted)),
                ])),
            ]))
          else
            Directionality(textDirection: TextDirection.ltr, child: Text(kv.sasNumbers.map(faNum).join('  '),
              style: const TextStyle(fontSize: 26, fontWeight: FontWeight.w600, letterSpacing: 2))),
          actions([
            TextButton(onPressed: () => run(kv.rejectSas), child: const Text('مطابقت ندارند')),
            FilledButton(
              onPressed: () { setState(() => confirmed = true); run(kv.acceptSas); },
              child: const Text('مطابقت دارند')),
          ]),
        ]);
      case KeyVerificationState.done:
        body = Column(spacing: 12, children: [
          Row(mainAxisAlignment: MainAxisAlignment.center, spacing: 8, children: [
            const Icon(Icons.check_circle, color: Color(0xff43a047)),
            Flexible(child: text(self ? 'این دستگاه تأیید شد.' : 'هویت $other تأیید شد.')),
          ]),
          actions([FilledButton(onPressed: close, child: const Text('بستن'))]),
        ]);
      case KeyVerificationState.error:
        body = Column(spacing: 12, children: [
          Text(cancelReasons[kv.canceledCode ?? ''] ?? 'تأیید لغو شد.', textAlign: TextAlign.center, style: const TextStyle(color: Color(0xffe53935))),
          actions([FilledButton(onPressed: close, child: const Text('بستن'))]),
        ]);
      case KeyVerificationState.waitingSas when confirmed:
        body = Column(children: [text('منتظر تأیید $other…'), spin]);
      default:
        body = spin;
    }

    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) { if (!didPop) close(); },
      child: SafeArea(child: Padding(
        padding: EdgeInsets.fromLTRB(20, 16, 20, 20 + MediaQuery.viewInsetsOf(context).bottom),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, spacing: 14, children: [
          Row(children: [
            Expanded(child: Text(self ? 'تأیید دستگاه' : 'تأیید هویت', style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w600))),
            IconButton(icon: const Icon(Icons.close), onPressed: close),
          ]),
          body,
          if (error.isNotEmpty) Text(error, textAlign: TextAlign.center, style: const TextStyle(color: Color(0xffe53935))),
        ]),
      )),
    );
  }
}

const cancelReasons = {
  'm.mismatched_sas': 'شکلک‌ها مطابقت نداشتند؛ تأیید لغو شد.',
  'm.user': 'تأیید لغو شد.',
  'm.timeout': 'مهلت تأیید تمام شد.',
  'm.accepted': 'درخواست در دستگاه دیگری پاسخ داده شد.',
};

// SAS emoji names (spec order), keyed by the SDK's English name
const emojiFa = {
  'dog': 'سگ', 'cat': 'گربه', 'lion': 'شیر', 'horse': 'اسب', 'unicorn': 'تک‌شاخ', 'pig': 'خوک', 'elephant': 'فیل', 'rabbit': 'خرگوش',
  'panda': 'پاندا', 'rooster': 'خروس', 'penguin': 'پنگوئن', 'turtle': 'لاک‌پشت', 'fish': 'ماهی', 'octopus': 'هشت‌پا', 'butterfly': 'پروانه', 'flower': 'گل',
  'tree': 'درخت', 'cactus': 'کاکتوس', 'mushroom': 'قارچ', 'globe': 'کره‌ی زمین', 'moon': 'ماه', 'cloud': 'ابر', 'fire': 'آتش', 'banana': 'موز',
  'apple': 'سیب', 'strawberry': 'توت‌فرنگی', 'corn': 'ذرت', 'pizza': 'پیتزا', 'cake': 'کیک', 'heart': 'قلب', 'smiley': 'لبخند', 'robot': 'ربات',
  'hat': 'کلاه', 'glasses': 'عینک', 'spanner': 'آچار', 'santa': 'بابانوئل', 'thumbs up': 'لایک', 'umbrella': 'چتر', 'hourglass': 'ساعت شنی', 'clock': 'ساعت',
  'gift': 'هدیه', 'light bulb': 'لامپ', 'book': 'کتاب', 'pencil': 'مداد', 'paperclip': 'گیره', 'scissors': 'قیچی', 'lock': 'قفل', 'key': 'کلید',
  'hammer': 'چکش', 'telephone': 'تلفن', 'flag': 'پرچم', 'train': 'قطار', 'bicycle': 'دوچرخه', 'aeroplane': 'هواپیما', 'rocket': 'موشک', 'trophy': 'جام',
  'ball': 'توپ', 'guitar': 'گیتار', 'trumpet': 'شیپور', 'bell': 'زنگ', 'anchor': 'لنگر', 'headphones': 'هدفون', 'folder': 'پوشه', 'pin': 'سنجاق',
};
