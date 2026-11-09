import { describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { jsonResponse, mockFetch, renderApp, seedSession } from "./harness";

describe("Fallo de red", () => {
  it("muestra un mensaje accionable cuando el servidor no responde", async () => {
    seedSession("ADMIN");
    vi.stubGlobal("fetch", vi.fn(() => Promise.reject(new TypeError("Failed to fetch"))));
    renderApp("/sucursales");

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/no se pudo contactar al servidor/i);
    // Sin sesión perdida: la red caída no es un problema de credenciales.
    expect(sessionStorage.getItem("inventario.accessToken")).toBe("token-de-prueba");
  });

  it("permite reintentar y se recupera cuando el servidor vuelve", async () => {
    seedSession("ADMIN");
    let online = false;
    vi.stubGlobal(
      "fetch",
      vi.fn(() =>
        online
          ? Promise.resolve(
              jsonResponse(200, {
                content: [{ id: "1", code: "SUC-001", name: "Sucursal Centro", location: null, active: true }],
                page: 0,
                size: 20,
                totalElements: 1,
                totalPages: 1,
              }),
            )
          : Promise.reject(new TypeError("Failed to fetch")),
      ),
    );
    renderApp("/sucursales");

    await screen.findByRole("alert");
    online = true;
    await userEvent.click(screen.getByRole("button", { name: /reintentar/i }));

    expect(await screen.findByText("Sucursal Centro")).toBeInTheDocument();
  });

  it("una respuesta de error sin el sobre uniforme no rompe la interfaz", async () => {
    // p. ej. un 502 en HTML devuelto por un proxy intermedio.
    seedSession("ADMIN");
    mockFetch(() => new Response("<html>Bad Gateway</html>", { status: 502, headers: { "Content-Type": "text/html" } }));
    renderApp("/sucursales");

    expect(await screen.findByRole("alert")).toHaveTextContent(/error inesperado/i);
  });
});

describe("Estados de datos reutilizables", () => {
  it("muestra el estado vacío cuando la consulta no devuelve resultados", async () => {
    seedSession("ADMIN");
    mockFetch(() => jsonResponse(200, { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }));
    renderApp("/sucursales");

    expect(await screen.findByText(/no hay sucursales activas/i)).toBeInTheDocument();
  });

  it("muestra el estado de carga antes de que lleguen los datos", async () => {
    seedSession("ADMIN");
    let resolve: (value: Response) => void = () => {};
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>((r) => (resolve = r))));
    renderApp("/sucursales");

    expect(await screen.findByRole("status")).toHaveTextContent(/cargando/i);

    resolve(jsonResponse(200, { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }));
    await waitFor(() => expect(screen.queryByRole("status")).not.toBeInTheDocument());
  });
});
