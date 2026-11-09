import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import type { RenderResult } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { expect, vi } from "vitest";
import App from "../App";
import { AuthProvider } from "../auth/AuthContext";
import type { LoginResponse, Role, UserSummary } from "../types/api";

/**
 * Monta la aplicación completa —router, caché y sesión— sobre un `fetch`
 * simulado. Se prueba a través de la interfaz real y no de piezas aisladas,
 * porque lo que interesa verificar es el comportamiento observable:
 * "el usuario no ve la sección", "se le devuelve al login".
 */
export function renderApp(initialPath = "/"): RenderResult {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <App />
        </AuthProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

export function userOfRole(role: Role, branchId: string | null = role === "ADMIN" ? null : "1"): UserSummary {
  return {
    id: "1",
    name: role === "ADMIN" ? "Admin General" : "Persona Usuaria",
    email: `${role.toLowerCase()}@test.local`,
    role,
    branchId,
  };
}

/** Deja una sesión ya iniciada, como tras recargar la página. `branchId` permite probar un rol en una sucursal distinta de la "1" por defecto. */
export function seedSession(role: Role, branchId?: string | null): UserSummary {
  const user = userOfRole(role, branchId);
  sessionStorage.setItem("inventario.accessToken", "token-de-prueba");
  sessionStorage.setItem("inventario.user", JSON.stringify(user));
  return user;
}

export function loginResponseFor(role: Role): LoginResponse {
  return { accessToken: "token-de-prueba", expiresIn: 3600, user: userOfRole(role) };
}

export function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

export function apiErrorResponse(status: number, code: string, message: string): Response {
  return jsonResponse(status, { error: { code, message, status, requestId: "req-de-prueba" } });
}

/**
 * Respuesta binaria simulada de un reporte exportable (BR-056): el nombre de
 * archivo viaja en `Content-Disposition`, nunca en el cuerpo. Se reproduce el
 * formato real del backend —simple (RFC 2047, `=?UTF-8?Q?...?=`) *y*
 * extendida (RFC 5987, `filename*=UTF-8''...`) a la vez— porque un cliente
 * que solo mire la primera se queda con el nombre codificado, no el legible.
 */
export function fileResponse(filename: string): Response {
  return new Response("contenido-del-excel", {
    status: 200,
    headers: {
      "Content-Type": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      "Content-Disposition": `attachment; filename="=?UTF-8?Q?${filename}?="; filename*=UTF-8''${filename}`,
    },
  });
}

/**
 * `URL.createObjectURL`/`revokeObjectURL` no existen en jsdom; el diálogo de
 * exportación los usa para disparar la descarga del navegador tras un click
 * real del usuario. Se sustituyen por dobles inertes solo para la prueba.
 * También se anula `HTMLAnchorElement.prototype.click`: jsdom sí lo ejecuta,
 * pero al intentar interpretar el `href` (una URL `blob:` simulada) como una
 * navegación real registra un "Not implemented: navigation" en consola que no
 * es un fallo de la prueba, solo ruido de un enlace que en un navegador real
 * jamás navega (tiene `download`).
 */
export function mockObjectUrl() {
  const createObjectURL = vi.fn(() => "blob:mock-url");
  const revokeObjectURL = vi.fn();
  URL.createObjectURL = createObjectURL;
  URL.revokeObjectURL = revokeObjectURL;
  const downloadedFilenames: string[] = [];
  const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) {
    downloadedFilenames.push(this.download);
  });
  return { createObjectURL, revokeObjectURL, click, downloadedFilenames };
}

/** Reemplaza `fetch` por una función que responde según la ruta pedida. */
export function mockFetch(handler: (url: string, init?: RequestInit) => Response | Promise<Response>) {
  const spy = vi.fn((input: RequestInfo | URL, init?: RequestInit) => Promise.resolve(handler(String(input), init)));
  vi.stubGlobal("fetch", spy);
  return spy;
}

/**
 * Selecciona una opción en un `<select>` de producto que se llena de forma
 * asíncrona (la consulta al catálogo aún no resolvió cuando aparece el
 * elemento). Esperar la opción, no solo el `<select>`, evita una carrera
 * donde `selectOptions` fallaría con "Value not found in options".
 */
export async function selectOption(labelRegex: RegExp, optionValue: string) {
  const select = await screen.findByLabelText(labelRegex);
  await waitFor(() => expect(within(select).getByRole("option", { name: (_, el) => el.getAttribute("value") === optionValue })).toBeInTheDocument());
  await userEvent.selectOptions(select, optionValue);
  return select;
}

/**
 * Doble de prueba de `EventSource` (jsdom no lo implementa). Registra cada
 * instancia creada en `instances` para que la prueba pueda tomar la más
 * reciente y disparar un evento con `emit`, simulando la señal SSE que el
 * backend enviaría (`useTransferRealtime`, ADR-009). `close()` no hace nada
 * más que marcarse — no hay conexión real que cerrar.
 */
export class MockEventSource {
  static instances: MockEventSource[] = [];
  url: string;
  closed = false;
  onopen: (() => void) | null = null;
  private listeners = new Map<string, Array<(event: MessageEvent) => void>>();

  constructor(url: string) {
    this.url = url;
    MockEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: (event: MessageEvent) => void) {
    const current = this.listeners.get(type) ?? [];
    current.push(listener);
    this.listeners.set(type, current);
  }

  close() {
    this.closed = true;
  }

  emit(type: string, data: unknown) {
    const event = { data: JSON.stringify(data) } as MessageEvent;
    for (const listener of this.listeners.get(type) ?? []) listener(event);
  }
}

/** Instala `MockEventSource` como `EventSource` global y limpia instancias previas. */
export function mockEventSource() {
  MockEventSource.instances = [];
  vi.stubGlobal("EventSource", MockEventSource);
}
