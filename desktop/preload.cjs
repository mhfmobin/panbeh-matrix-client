// The only bridge between the web app and Electron; src/desktop.ts is its other end.
const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("panbehDesktop", {
  setBadge: (n) => ipcRenderer.send("badge", n),
  focus: () => ipcRenderer.send("focus"),
  openExternal: (url) => ipcRenderer.send("open-external", url),
  /** Pass a boolean to change it; resolves to the current setting. */
  autostart: (on) => ipcRenderer.invoke("autostart", on),
  info: () => ipcRenderer.invoke("info"),
  /** Registers the listener, then asks for links that arrived before the page was ready (cold start). */
  onLink: (cb) => {
    const h = (_e, link) => cb(link);
    ipcRenderer.on("link", h);
    ipcRenderer.send("links-ready");
    return () => ipcRenderer.off("link", h);
  },
  onVisibility: (cb) => {
    const h = (_e, visible) => cb(visible);
    ipcRenderer.on("visibility", h);
    return () => ipcRenderer.off("visibility", h);
  },
});
