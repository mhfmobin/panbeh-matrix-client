# Message interaction preview

Run `npm run test:ui`, then open <http://127.0.0.1:5174/tests/ui/>.
This uses the real Message, MessageMenu, EmojiPanel and CSS with a local Matrix SDK room. It does not log in or start sync. The client replacement hook exists only in this preview server's Vite transform and is absent from production builds.

Click **اجرای تست‌های تعاملی** to check the initial hold, a second press permitting native selection, preserving a text selection on release, and returning from the reaction picker to the message menu. Touch pointer events and the browser's resulting text range are simulated; native word selection and selection handles must also be checked on a phone.

Manual checks:

1. Right-click a sample message (tap on touch). Open more reactions, click outside, and use an action in the restored menu. Repeat with Escape.
2. Pick a reaction and confirm both menus close and a reaction chip appears.
3. On a phone, hold an unselected message for at least half a second and keep holding: only the message should become selected. Release, then hold a word in that selected message: native word selection and handles should appear. Adjust the handles and copy part of the text.
4. Cancel selection and repeat. Check incoming and outgoing messages, light/dark appearance, and menus near the screen edges.
5. Enable the device's reduced-motion preference: menu animations should become effectively instant.

For a phone on the same trusted local network, run `npm run test:ui -- --host 0.0.0.0` and open `http://<computer-LAN-IP>:5174/tests/ui/`. Stop the preview with Ctrl+C when finished.
