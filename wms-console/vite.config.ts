import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import type { Plugin } from "vite";

function healthzPlugin(): Plugin {
  const respond = (res: { setHeader(name: string, value: string): void; end(body: string): void }) => {
    res.setHeader("Access-Control-Allow-Origin", "*");
    res.setHeader("Cache-Control", "no-store");
    res.setHeader("Content-Type", "text/plain; charset=utf-8");
    res.end("ok\n");
  };
  return {
    name: "wms-healthz",
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        if (req.url?.split("?")[0] !== "/healthz") return next();
        respond(res);
      });
    },
    configurePreviewServer(server) {
      server.middlewares.use((req, res, next) => {
        if (req.url?.split("?")[0] !== "/healthz") return next();
        respond(res);
      });
    }
  };
}

const uiPort = Number(process.env.WMS_UI_PORT ?? 4181);

export default defineConfig({
  plugins: [react(), healthzPlugin()],
  server: {
    host: "127.0.0.1",
    port: uiPort,
    proxy: {
      "/inbound-api": { target: "http://127.0.0.1:18181", rewrite: (path) => path.replace(/^\/inbound-api/, "") },
      "/outbound-api": { target: "http://127.0.0.1:18182", rewrite: (path) => path.replace(/^\/outbound-api/, "") },
      "/inventory-api": { target: "http://127.0.0.1:18183", rewrite: (path) => path.replace(/^\/inventory-api/, "") },
      "/fulfillment-api": { target: "http://127.0.0.1:18185", rewrite: (path) => path.replace(/^\/fulfillment-api/, "") }
    }
  },
  preview: { host: "127.0.0.1", port: uiPort },
  test: {
    environment: "jsdom"
  }
});
