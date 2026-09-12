import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/inbound-api": { target: "http://127.0.0.1:18181", rewrite: (path) => path.replace(/^\/inbound-api/, "") },
      "/outbound-api": { target: "http://127.0.0.1:18182", rewrite: (path) => path.replace(/^\/outbound-api/, "") },
      "/inventory-api": { target: "http://127.0.0.1:18183", rewrite: (path) => path.replace(/^\/inventory-api/, "") },
      "/fulfillment-api": { target: "http://127.0.0.1:18185", rewrite: (path) => path.replace(/^\/fulfillment-api/, "") }
    }
  },
  test: {
    environment: "jsdom"
  }
});
