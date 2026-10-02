import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
  appId: "ir.panbeh.app",
  appName: "پنبه",
  webDir: "dist",
  // https://localhost: a secure origin, so WebCrypto/IndexedDB behave as on the web
  android: { allowMixedContent: false },
  server: { androidScheme: "https" },
};

export default config;
