import { mergeConfig } from "vite";
import base from "../../vite.config.ts";

// Only this preview server can replace the client with an offline SDK instance.
// The production module and build have no test entry point.
export default mergeConfig(base, {
  plugins: [{
    name: "offline-message-preview",
    transform(code: string, id: string) {
      if (id.replaceAll("\\", "/").endsWith("/src/matrix.ts")) {
        return code + "\nexport function __usePreviewClient(value) { client = value; }\n";
      }
    },
  }],
  server: { open: false },
});
