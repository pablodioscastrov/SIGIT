import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { apiErrorResponse, jsonResponse, loginResponseFor, mockFetch, renderApp, seedSession } from "./harness";
import { dashboardRoutes } from "./catalog";

const emptyPage = { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 };

describe("Rutas protegidas", () => {
  it("redirige al login cuando no hay sesión", async () => {
    mockFetch(() => jsonResponse(200, emptyPage));
    renderApp("/sucursales");

    expect(await screen.findByRole("heading", { name: /iniciar sesión/i })).toBeInTheDocument();
  });

  it("tras iniciar sesión devuelve al usuario a la ruta que pedía originalmente", async () => {
    mockFetch((url) => {
      if (url.includes("/auth/login")) return jsonResponse(200, loginResponseFor("ADMIN"));
      return jsonResponse(200, emptyPage);
    });
    renderApp("/sucursales");

    await userEvent.type(await screen.findByLabelText(/correo/i), "admin@test.local");
    await userEvent.type(screen.getByLabelText(/contraseña/i), "ChangeMe123!");
    await userEvent.click(screen.getByRole("button", { name: /entrar/i }));

    expect(await screen.findByRole("heading", { name: /sucursales/i })).toBeInTheDocument();
  });

  it("un 401 de la API termina la sesión y devuelve al login", async () => {
    seedSession("ADMIN");
    mockFetch(() => apiErrorResponse(401, "NO_AUTENTICADO", "Token inválido o expirado."));
    renderApp("/sucursales");

    expect(await screen.findByRole("heading", { name: /iniciar sesión/i })).toBeInTheDocument();
    expect(sessionStorage.getItem("inventario.accessToken")).toBeNull();
  });

  it("una ruta inexistente muestra la página de no encontrado", async () => {
    seedSession("ADMIN");
    mockFetch(() => jsonResponse(200, emptyPage));
    renderApp("/ruta-que-no-existe");

    expect(await screen.findByRole("heading", { name: /no encontrada/i })).toBeInTheDocument();
  });
});

describe("Navegación por rol", () => {
  it("el ADMIN ve la sección de usuarios", async () => {
    seedSession("ADMIN");
    mockFetch(dashboardRoutes());
    renderApp("/");

    expect(await screen.findByRole("link", { name: /^Usuarios$/ })).toBeInTheDocument();
  });

  it("OPERATOR y MANAGER no ven la sección de usuarios", async () => {
    for (const role of ["OPERATOR", "MANAGER"] as const) {
      sessionStorage.clear();
      seedSession(role);
      mockFetch(dashboardRoutes());
      const view = renderApp("/");

      expect(await screen.findByRole("link", { name: /^Inventario$/ })).toBeInTheDocument();
      expect(screen.queryByRole("link", { name: /^Usuarios$/ })).not.toBeInTheDocument();
      view.unmount();
    }
  });

  it("ocultar el enlace no es la protección: forzar la URL tampoco deja entrar", async () => {
    seedSession("OPERATOR");
    mockFetch(() => jsonResponse(200, emptyPage));
    renderApp("/usuarios");

    expect(await screen.findByRole("heading", { name: /sin permiso/i })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /^usuarios$/i })).not.toBeInTheDocument();
  });

  it("un 403 del servidor se muestra como falta de permisos, sin cerrar la sesión", async () => {
    // El caso que demuestra por qué la guarda de cliente no es la autorización:
    // aunque la interfaz deje pasar, el backend decide y la UI lo refleja.
    seedSession("ADMIN");
    mockFetch(() => apiErrorResponse(403, "ROL_NO_AUTORIZADO", "No tiene permisos para realizar esta acción."));
    renderApp("/sucursales");

    expect(await screen.findByRole("alert")).toHaveTextContent(/no tiene permiso/i);
    await waitFor(() => expect(sessionStorage.getItem("inventario.accessToken")).toBe("token-de-prueba"));
  });
});
