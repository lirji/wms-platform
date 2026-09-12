import { afterEach } from "vitest";
import { cleanup } from "@testing-library/react";

afterEach(() => {
  cleanup();
});

Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => undefined,
    removeListener: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false
  })
});

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

Object.defineProperty(navigator, "clipboard", {
  configurable: true,
  value: { writeText: async () => undefined }
});

Object.defineProperty(window, "ResizeObserver", {
  writable: true,
  value: ResizeObserverStub
});

const computedStyle = window.getComputedStyle.bind(window);
window.getComputedStyle = ((element: Element) => computedStyle(element)) as typeof window.getComputedStyle;
