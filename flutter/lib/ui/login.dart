import 'dart:async';

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../main.dart' show Logo;
import '../matrix.dart';
import '../theme.dart';
import 'common.dart';

class Login extends StatefulWidget {
  final VoidCallback onDone;
  final VoidCallback? onCancel; // adding a second account
  const Login({super.key, required this.onDone, this.onCancel});
  @override
  State<Login> createState() => _LoginState();
}

class _LoginState extends State<Login> {
  final server = TextEditingController(), user = TextEditingController(), password = TextEditingController();
  String mode = ''; // '' = not checked yet (or unreachable), checking, oauth, password
  String error = '';
  bool busy = false;
  Timer? debounce;

  @override
  void initState() {
    super.initState();
    SharedPreferences.getInstance().then((p) {
      server.text = p.getString(lastServerKey) ?? '';
      if (server.text.isNotEmpty) check();
    });
  }

  @override
  void dispose() {
    debounce?.cancel();
    super.dispose();
  }

  // once typing stops, ask the server how it logs in
  void onServer(String _) {
    setState(() { mode = ''; error = ''; });
    debounce?.cancel();
    if (server.text.trim().isNotEmpty) debounce = Timer(const Duration(milliseconds: 700), check);
  }

  Future<void> check() async {
    final s = server.text;
    setState(() => mode = 'checking');
    try {
      final m = await loginMode(s);
      if (mounted && server.text == s) setState(() => mode = m);
    } catch (e) {
      if (mounted && server.text == s) setState(() { mode = ''; error = errText(e); });
    }
  }

  Future<void> submit() async {
    if (mode == '') return check(); // before the check ran, or retry after an error
    setState(() { busy = true; error = ''; });
    try {
      (await SharedPreferences.getInstance()).setString(lastServerKey, server.text);
      if (mode == 'oauth') {
        await loginOAuth(server.text);
      } else {
        await login(server.text, user.text.trim(), password.text);
      }
      widget.onDone();
    } catch (e) {
      if (mounted) setState(() { error = errText(e); busy = false; });
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tk;
    InputDecoration field(String label, String hint) => InputDecoration(labelText: label, hintText: hint, border: const OutlineInputBorder());
    return Scaffold(
      body: Wallpaper(child: Center(child: SingleChildScrollView(padding: const EdgeInsets.all(16), child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 380),
        child: Card(color: t.panel, child: Padding(padding: const EdgeInsets.all(24), child: AutofillGroup(child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch, spacing: 14,
          children: [
            const Center(child: Logo()),
            const Text('ورود به ماتریکس', textAlign: TextAlign.center, style: TextStyle(fontSize: 22, fontWeight: FontWeight.w700)),
            TextField(controller: server, onChanged: onServer, textDirection: TextDirection.ltr, keyboardType: TextInputType.url,
              autocorrect: false, decoration: field('سرور', 'matrix.example.org:8448'), onSubmitted: (_) => submit()),
            if (mode == 'password') ...[
              TextField(controller: user, textDirection: TextDirection.ltr, autocorrect: false, autofillHints: const [AutofillHints.username],
                decoration: field('نام کاربری', 'alice')),
              TextField(controller: password, textDirection: TextDirection.ltr, obscureText: true, autofillHints: const [AutofillHints.password],
                decoration: field('رمز عبور', ''), onSubmitted: (_) => submit()),
            ],
            if (error.isNotEmpty) Text(error, style: const TextStyle(color: Color(0xffe53935))),
            if (busy && mode == 'oauth') Text('ورود را در مرورگر ادامه دهید؛ پس از آن به پنبه برمی‌گردید.', style: TextStyle(color: t.muted)),
            FilledButton(
              style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(46)),
              onPressed: busy || mode == 'checking' ? null : submit,
              child: Text(busy ? 'در حال ورود…' : const {'': 'ادامه', 'checking': 'در حال بررسی سرور…', 'oauth': 'ادامه در صفحه‌ی ورود سرور', 'password': 'ورود'}[mode]!),
            ),
            if (widget.onCancel != null) TextButton(onPressed: widget.onCancel, child: const Text('انصراف')),
          ],
        )))),
      )))),
    );
  }
}
