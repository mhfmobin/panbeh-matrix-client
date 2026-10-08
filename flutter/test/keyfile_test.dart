import 'package:flutter_test/flutter_test.dart';
import 'package:panbeh/keyfile.dart';

void main() {
  test("Megolm key file: Element's test vector, round trip, wrong passphrase", () async {
    const element = '-----BEGIN MEGOLM SESSION DATA-----\nAXNhbHRzYWx0c2FsdHNhbHSIiIiIiIiIiIiIiIiIiIiIAAAACmIRUW2OjZ3L2l6j9h0lHlV3M2dx\ncissyYBxjsfsAndErh065A8=\n-----END MEGOLM SESSION DATA-----';
    expect(await decryptKeyFile(element, 'password'), 'plain');
    final json = '[{"session_id":"s","room_id":"!r:hs","text":"${'سلام ' * 100}"}]';
    final file = await encryptKeyFile(json, 'رمز', 1000);
    expect(await decryptKeyFile(file, 'رمز'), json);
    await expectLater(decryptKeyFile(file, 'wrong'), throwsA(predicate((e) => '$e'.contains('عبارت عبور'))));
  });

  test('500k rounds timing', () async {
    final t = Stopwatch()..start();
    final f = await encryptKeyFile('x', 'p');
    // ignore: avoid_print
    print('500k rounds: ${t.elapsedMilliseconds} ms');
    expect(await decryptKeyFile(f, 'p'), 'x');
  });
}
