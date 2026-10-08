import 'dart:async';
import 'dart:convert';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:matrix/encryption.dart';
import 'package:matrix/matrix.dart';
import 'package:url_launcher/url_launcher.dart';

import '../keyfile.dart';
import '../logic.dart';
import '../matrix.dart';
import '../theme.dart';
import 'common.dart';
import 'verify.dart';

enum Recovery { ok, unlock, setup }

/// "unlock" = account has secret storage we can load; "setup" = nothing yet; "ok" = this device has the secrets.
final recovery = ValueNotifier<Recovery?>(null);

Future<Recovery> recoveryState() async {
  final s = await client.getCryptoIdentityState();
  return s.connected ? Recovery.ok : s.initialized ? Recovery.unlock : Recovery.setup;
}

Future<void> refreshRecovery() async {
  try {
    recovery.value = await recoveryState();
  } catch (_) {}
}

const _red = Color(0xffe53935);

/// Text prompt dialog; null if cancelled.
Future<List<String>?> _ask(BuildContext context, String title, List<String> hints, {String ok = 'تأیید', String? note}) {
  final cs = [for (final _ in hints) TextEditingController()];
  return showDialog<List<String>>(
    context: context,
    builder: (c) => AlertDialog(
      title: Text(title),
      content: Column(mainAxisSize: MainAxisSize.min, spacing: 10, children: [
        if (note != null) Text(note),
        for (var i = 0; i < hints.length; i++)
          TextField(controller: cs[i], obscureText: true, autofocus: i == 0, textDirection: TextDirection.ltr,
            decoration: InputDecoration(hintText: hints[i]), onSubmitted: i == hints.length - 1 ? (_) => Navigator.pop(c, [for (final x in cs) x.text]) : null),
      ]),
      actions: [
        TextButton(onPressed: () => Navigator.pop(c), child: const Text('انصراف')),
        TextButton(onPressed: () => Navigator.pop(c, [for (final x in cs) x.text]), child: Text(ok)),
      ],
    ),
  );
}

class EncryptionPage extends StatefulWidget {
  const EncryptionPage({super.key});
  @override
  State<EncryptionPage> createState() => _EncryptionPageState();
}

class _EncryptionPageState extends State<EncryptionPage> {
  final input = TextEditingController();
  bool busy = false;
  String error = '', newKey = '';

  @override
  void initState() {
    super.initState();
    recovery.addListener(_changed);
    refreshRecovery();
  }

  void _changed() { if (mounted) setState(() {}); }

  @override
  void dispose() {
    recovery.removeListener(_changed);
    input.dispose();
    super.dispose();
  }

  /// Accepts either the recovery key or the security passphrase.
  Future<void> unlock() async {
    setState(() { busy = true; error = ''; });
    try {
      await client.restoreCryptoIdentity(input.text.trim());
      input.clear();
      // old message keys, in the background
      client.encryption?.keyManager.loadAllKeys().catchError((_) {});
    } on InvalidPassphraseException {
      error = 'کلید یا عبارت امنیتی درست نیست';
    } catch (e) {
      // a wrong key surfaces as a bad MAC / mismatch from the secret storage
      error = RegExp('mac|do not match|passphrase', caseSensitive: false).hasMatch('$e') ? 'کلید یا عبارت امنیتی درست نیست' : errText(e);
    }
    await refreshRecovery();
    if (mounted) setState(() => busy = false);
  }

  /// Fresh setup: new cross-signing + secret storage + key backup. The recovery key is shown once.
  /// The server may want proof first (UIA): the account password, or approval in the auth service for OAuth accounts.
  Future<void> setup() async {
    setState(() { busy = true; error = ''; });
    final sub = client.onUiaRequest.stream.listen((uia) {
      if (uia.state == UiaRequestState.waitForUser) _uia(uia);
    });
    try {
      newKey = await client.initCryptoIdentity();
    } catch (e) {
      error = errText(e);
    }
    sub.cancel();
    await refreshRecovery();
    if (mounted) setState(() => busy = false);
  }

  Future<void> _uia(UiaRequest uia) async {
    if (!mounted) return uia.cancel();
    final stages = uia.nextStages;
    if (stages.contains(AuthenticationTypes.password)) {
      final r = await _ask(context, 'تأیید با رمز عبور', ['رمز عبور حساب'], note: 'برای راه‌اندازی بازیابی، رمز عبور حسابتان را وارد کنید.');
      if (r == null || r[0].isEmpty) return uia.cancel(Exception('لغو شد'));
      await uia.completeStage(AuthenticationPassword(session: uia.session, password: r[0], identifier: AuthenticationUserIdentifier(user: client.userID!)));
      return;
    }
    // first upload needs no auth; replacing existing keys must be approved in the auth service (MSC4312)
    final p = uia.params;
    final url = (p['m.oauth'] ?? p['org.matrix.cross_signing_reset'])?['url'];
    if (url is! String) return uia.cancel(Exception('این سرور روش تأیید پشتیبانی‌نشده‌ای می‌خواهد.'));
    await launchUrl(Uri.parse(url), mode: LaunchMode.externalApplication);
    if (!mounted || !await confirm(context, 'بازنشانی کلیدها را در صفحه‌ی بازشده تأیید کنید، سپس «تأیید» را بزنید.')) return uia.cancel(Exception('لغو شد'));
    await uia.completeStage(AuthenticationData(type: stages.first, session: uia.session));
  }

  Future<void> save() async {
    final r = await _ask(context, 'ذخیره‌ی کلیدها در فایل', ['عبارت عبور برای فایل', 'تکرار عبارت عبور'], ok: 'ذخیره');
    if (r == null || !mounted) return;
    if (r[0].isEmpty || r[0] != r[1]) return alert(context, 'تکرار عبارت عبور یکی نیست');
    await attempt(context, () async {
      showDialog(context: context, barrierDismissible: false, builder: (_) => const _Busy('در حال رمزنگاری…'));
      final String text;
      try {
        text = await exportKeys(client, r[0]);
      } finally {
        if (mounted) Navigator.pop(context);
      }
      final saved = await FilePicker.saveFile(fileName: 'element-keys.txt', bytes: utf8.encode(text), mimeType: 'text/plain');
      if (saved != null && mounted) toast(context, 'فایل کلیدها ذخیره شد.');
    });
  }

  Future<void> import() async {
    final f = await FilePicker.pickFile();
    if (f == null || !mounted) return;
    final r = await _ask(context, 'وارد کردن کلیدها از فایل', ['عبارت عبور فایل'], ok: 'وارد کردن');
    if (r == null || !mounted) return;
    final progress = ValueNotifier('در حال رمزگشایی…');
    showDialog(context: context, barrierDismissible: false, builder: (_) => ValueListenableBuilder(valueListenable: progress, builder: (_, v, _) => _Busy(v)));
    int? n;
    String? err;
    try {
      final text = utf8.decode(await f.readAsBytes());
      n = await importKeys(client, text, r[0], onProgress: (d, t) => progress.value = 'در حال وارد کردن ${faNum(d)} / ${faNum(t)}');
    } catch (e) {
      err = errText(e);
    }
    if (!mounted) return;
    Navigator.pop(context);
    if (err != null) return alert(context, err);
    toast(context, '${faNum(n!)} کلید وارد شد.');
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tk;
    final state = recovery.value;
    final enc = client.encryption;
    final dev = client.userDeviceKeys[me()]?.deviceKeys[client.deviceID];
    Widget row(String label, bool on, [String yes = 'آماده', String no = 'ناقص']) => ListTile(
      title: Text(label),
      trailing: Row(mainAxisSize: MainAxisSize.min, spacing: 6, children: [
        Text(on ? yes : no, style: TextStyle(color: on ? const Color(0xff43a047) : t.muted)),
        Icon(on ? Icons.check_circle : Icons.circle_outlined, size: 20, color: on ? const Color(0xff43a047) : t.muted),
      ]),
    );
    Widget pad(List<Widget> c) => Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, spacing: 10, children: c));
    final errorText = error.isEmpty ? null : Text(error, style: const TextStyle(color: _red));

    return Scaffold(
      appBar: AppBar(title: const Text('رمزنگاری')),
      body: ListView(children: [
        if (newKey.isNotEmpty)
          Section(title: 'کلید بازیابی', children: [pad([
            const Text('این کلید بازیابی را جای امنی نگه دارید. برای خواندن پیام‌هایتان در دستگاه جدید به آن نیاز دارید. دوباره نمایش داده نمی‌شود.'),
            SelectableText(newKey, textDirection: TextDirection.ltr, style: const TextStyle(fontFamily: 'monospace', fontSize: 15)),
            FilledButton.icon(onPressed: () => copyText(context, newKey), icon: const Icon(Icons.copy, size: 18), label: const Text('کپی')),
          ])]),
        Section(title: 'وضعیت', children: [
          if (state == null) const Padding(padding: EdgeInsets.all(16), child: Text('در حال بررسی…'))
          else ...[
            row('این دستگاه', dev?.verified ?? false, 'تأییدشده', 'تأییدنشده'),
            row('امضای متقابل', enc?.crossSigning.enabled ?? false),
            row('پشتیبان کلیدها', enc?.keyManager.enabled ?? false, 'روشن', 'خاموش'),
          ],
        ]),
        if (state == Recovery.unlock)
          Section(title: 'باز کردن قفل', children: [pad([
            const Text('کلید بازیابی یا عبارت امنیتی خود را وارد کنید تا این دستگاه تأیید شود و پیام‌های رمزنگاری‌شده‌ی قبلی را بخوانید.'),
            TextField(controller: input, textDirection: TextDirection.ltr, autocorrect: false, enableSuggestions: false,
              decoration: const InputDecoration(hintText: 'کلید بازیابی یا عبارت امنیتی'), onSubmitted: (_) => busy ? null : unlock()),
            ?errorText,
            FilledButton(onPressed: busy ? null : unlock, child: Text(busy ? 'در حال انجام…' : 'باز کردن قفل')),
            OutlinedButton(onPressed: busy ? null : () => verifySelf(context), child: const Text('تأیید با دستگاه دیگر')),
          ])]),
        if (state == Recovery.setup)
          Section(title: 'راه‌اندازی بازیابی', children: [pad([
            const Text('بازیابی را راه‌اندازی کنید تا پیام‌های رمزنگاری‌شده را در دستگاه‌های دیگر هم بخوانید.'),
            ?errorText,
            FilledButton(onPressed: busy ? null : setup, child: Text(busy ? 'در حال انجام…' : 'راه‌اندازی بازیابی')),
          ])]),
        Section(title: 'کلیدهای رمزنگاری', children: [
          const Padding(padding: EdgeInsets.fromLTRB(16, 12, 16, 0), child: Text('کلیدهای پیام‌های رمزنگاری‌شده را در یک فایل رمزدار ذخیره کنید تا در برنامه‌ی دیگری (مثل Element) وارد شوند.')),
          ListTile(leading: const Icon(Icons.save_alt), title: const Text('ذخیره‌ی کلیدها در فایل'), onTap: save),
          ListTile(leading: const Icon(Icons.file_open_outlined), title: const Text('وارد کردن کلیدها از فایل'), onTap: import),
        ]),
      ]),
    );
  }
}

class _Busy extends StatelessWidget {
  final String text;
  const _Busy(this.text);
  @override
  Widget build(BuildContext context) => AlertDialog(
    content: Row(spacing: 16, children: [const SizedBox.square(dimension: 24, child: CircularProgressIndicator(strokeWidth: 2)), Expanded(child: Text(text))]),
  );
}
