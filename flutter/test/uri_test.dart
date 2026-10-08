import 'package:flutter_test/flutter_test.dart';
import 'package:panbeh/uri.dart';

void main() {
  test('matrix.to user, room, alias, event and via', () {
    expect(parseMatrixLink('https://matrix.to/#/@a:x.org'), const Target(kind: 'user', id: '@a:x.org'));
    expect(parseMatrixLink('https://matrix.to/#/%23r%3Ax.org'), const Target(kind: 'roomAlias', id: '#r:x.org'));
    expect(parseMatrixLink(r'https://matrix.to/#/!id:x.org/$ev?via=a.org&via=b.org'),
        const Target(kind: 'room', id: '!id:x.org', eventId: r'$ev', via: ['a.org', 'b.org']));
    expect(parseMatrixLink(r'https://matrix.to/#/#r:x.org/$ev')?.eventId, r'$ev');
  });

  test('matrix: URIs', () {
    expect(parseMatrixLink('matrix:u/a:x.org?action=chat'), const Target(kind: 'user', id: '@a:x.org', action: 'chat'));
    expect(parseMatrixLink('matrix:r/r:x.org'), const Target(kind: 'roomAlias', id: '#r:x.org'));
    expect(parseMatrixLink('matrix:roomid/id:x.org/e/ev?via=a.org&action=join'),
        const Target(kind: 'room', id: '!id:x.org', eventId: r'$ev', via: ['a.org'], action: 'join'));
    expect(parseMatrixLink('matrix:r/r%3Ax.org/e/ev')?.eventId, r'$ev');
    expect(parseMatrixLink('MATRIX:u/a:x.org')?.kind, 'user');
  });

  test('rejects malformed and foreign links', () {
    for (final s in [
      'https://example.com/#/@a:x.org', 'http://matrix.to/#/@a:x.org', 'https://matrix.to/#/foo',
      r'https://matrix.to/#/@a:x.org/$ev', 'https://matrix.to/#/!r:x/notevent', r'https://matrix.to/#/!r:x/$e/extra',
      'matrix:x/a:b', 'matrix:u', 'matrix:u/a:x/e/ev', 'matrix:r/r:x/z/ev', 'matrix:r/%E0%A4%A', '', 'hello',
    ]) {
      expect(parseMatrixLink(s), null, reason: s);
    }
  });

  test('page hash form', () {
    expect(parseMatrixHash(r'#/!id:x.org/$ev')?.eventId, r'$ev');
    expect(parseMatrixHash('#!id:x.org'), null);
  });
}
