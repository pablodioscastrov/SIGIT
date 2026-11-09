import "@testing-library/jest-dom/vitest";
import { afterEach, vi } from "vitest";
import { cleanup } from "@testing-library/react";
import { MockEventSource } from "./harness";

/**
 * jsdom no implementa `EventSource` (usado por `useTransferRealtime`, ADR-009).
 * Se instala el doble de prueba una vez para todas las suites: sin esto,
 * cualquier pantalla que lo monte lanzaría `ReferenceError` aunque la prueba
 * no tenga nada que ver con SSE. Las pruebas que sí ejercen el canal toman la
 * instancia más reciente de `MockEventSource.instances`.
 */
vi.stubGlobal("EventSource", MockEventSource);

afterEach(() => {
  cleanup();
  sessionStorage.clear();
  MockEventSource.instances = [];
  vi.restoreAllMocks();
});
