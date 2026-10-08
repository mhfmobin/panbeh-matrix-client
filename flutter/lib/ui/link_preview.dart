import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:matrix/matrix.dart';

import '../logic.dart';
import '../matrix.dart';
import '../prefs.dart';
import '../theme.dart';
import 'common.dart';
import 'message.dart' show openLink;

const _text = ['m.text', 'm.notice', 'm.emote'];
final _cache = <String, Future<Map<String, Object?>?>>{};

/// First web link worth previewing: not a matrix.to pill, not inside code.
String? _firstUrl(Map<String, dynamic> c) {
  // bodies keep their Markdown, so code is still in backticks
  final body = stripReplyFallback('${c['body'] ?? ''}')
      .replaceAll(RegExp(r'```[\s\S]*?```|`[^`\n]*`'), '');
  return extractLinks(body)
      .where((u) => !u.startsWith('https://matrix.to/'))
      .firstOrNull;
}

Future<Map<String, Object?>?> _fetch(String url, int ts) async {
  final u = Uri.parse(url);
  try {
    final p = await client.getUrlPreviewAuthed(u, ts: ts);
    return p.toJson();
  } catch (_) {
    try {
      return (await client.getUrlPreview(
        u,
        ts: ts,
      )).toJson(); // older servers without authenticated media
    } catch (_) {
      return null; // no previews on this server
    }
  }
}

/// Card under a text message for its first link, from the homeserver's preview_url. Nothing on failure.
class LinkPreview extends StatelessWidget {
  final Event ev;
  const LinkPreview(this.ev, {super.key});
  @override
  Widget build(BuildContext context) {
    final c = ev.content;
    final url = _text.contains(c['msgtype']) && !ev.redacted
        ? _firstUrl(c)
        : null;
    if (url == null || !prefs.get<bool>('previews')) return const SizedBox.shrink();
    final f = _cache.putIfAbsent(
      url,
      () => _fetch(url, ev.originServerTs.millisecondsSinceEpoch),
    );
    return FutureBuilder(
      future: f,
      builder: (context, s) {
        final d = s.data;
        String str(String k) => d?[k] is String ? (d![k] as String).trim() : '';
        final title = str('og:title'), desc = str('og:description');
        if (d == null || (title.isEmpty && desc.isEmpty)) return const SizedBox.shrink();
        final t = context.tk, mxc = Uri.tryParse(str('og:image'));
        final site = str('og:site_name').isNotEmpty
            ? str('og:site_name')
            : Uri.parse(url).host;
        return GestureDetector(
          onTap: () => openLink(context, url),
          child: Container(
            margin: const EdgeInsets.only(top: 6, bottom: 16),
            padding: const EdgeInsetsDirectional.only(start: 8),
            decoration: BoxDecoration(
              border: BorderDirectional(
                start: BorderSide(color: t.accent, width: 2),
              ),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  site,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: t.accent,
                    fontWeight: FontWeight.w600,
                    fontSize: 13,
                  ),
                ),
                if (title.isNotEmpty)
                  Text(
                    title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontWeight: FontWeight.w600,
                      fontSize: 14,
                    ),
                  ),
                if (desc.isNotEmpty)
                  Text(
                    desc,
                    maxLines: 4,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 13),
                  ),
                if (mxc != null && mxc.scheme == 'mxc')
                  Padding(
                    padding: const EdgeInsets.only(top: 6),
                    child: ClipRRect(
                      borderRadius: BorderRadius.circular(8),
                      child: FutureBuilder<Uint8List?>(
                        future: loadMxc(mxc, size: 640),
                        builder: (_, i) => i.data == null
                            ? const SizedBox.shrink()
                            : Image.memory(
                                i.data!,
                                width: double.infinity,
                                fit: BoxFit.cover,
                                gaplessPlayback: true,
                              ),
                      ),
                    ),
                  ),
              ],
            ),
          ),
        );
      },
    );
  }
}
