import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { apiErrorResponse, jsonResponse, loginResponseFor, mockFetch, renderApp, seedSession } from "./harness";
import { dashboardRoutes } from "./catalog";

describe("Sesión: inicio y cierre", () => {
  it("inicia sesión y lleva al usuario a la aplicación", async () => {
    const fetchSpy = mockFetch(
      dashboardRoutes((url) => (url.includes("/auth/login") ? jsonResponse(200, loginResponseFor("ADMIN")) : undefined)),
    );
    renderApp("/login");

    await userEvent.type(screen.getByLabelText(/correo/i), "admin@test.local");
    await userEvent.type(screen.getByLabelText(/contraseña/i), "ChangeMe123!");
    await userEvent.click(screen.getByRole("button", { name: /entrar/i }));

    expect(await screen.findByRole("heading", { name: /dashboard/i })).toBeInTheDocument();
    // El token quedó persistido para sobrevivir a un refresco de página.
    expect(sessionStorage.getItem("inventario.accessToken")).toBe("token-de-prueba");

    const [, init] = fetchSpy.mock.calls[0];
    expect(JSON.parse(String(init?.body))).toEqual({ email: "admin@test.local", password: "ChangeMe123!" });
  });

  it("muestra el error del servidor cuando las credenciales son inválidas, sin iniciar sesión", async () => {
    mockFetch(() => apiErrorResponse(401, "CREDENCIALES_INVALIDAS", "Correo o contraseña incorrectos."));
    renderApp("/login");

    await userEvent.type(screen.getByLabelText(/correo/i), "admin@test.local");
    await userEvent.type(screen.getByLabelText(/contraseña/i), "incorrecta");
    await userEvent.click(screen.getByRole("button", { name: /entrar/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/incorrect/i);
    expect(sessionStorage.getItem("inventario.accessToken")).toBeNull();
  });

  it("cierra sesión, limpia el almacenamiento y devuelve al login", async () => {
    seedSession("ADMIN");
    mockFetch(dashboardRoutes());
    renderApp("/");

    await userEvent.click(await screen.findByRole("button", { name: /cerrar sesión/i }));

    expect(await screen.findByRole("heading", { name: /iniciar sesión/i })).toBeInTheDocument();
    expect(sessionStorage.getItem("inventario.accessToken")).toBeNull();
  });

  it("recupera la sesión guardada al recargar, sin volver a pedir credenciales", async () => {
    seedSession("MANAGER");
    mockFetch(dashboardRoutes());
    renderApp("/");

    expect(await screen.findByRole("heading", { name: /dashboard/i })).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("heading", { name: /iniciar sesión/i })).not.toBeInTheDocument());
  });

  it("adjunta el token a las peticiones posteriores", async () => {
    seedSession("ADMIN");
    const fetchSpy = mockFetch(() => jsonResponse(200, { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }));
    renderApp("/sucursales");

    await waitFor(() => expect(fetchSpy).toHaveBeenCalled());
    const [, init] = fetchSpy.mock.calls[0];
    expect((init?.headers as Record<string, string>).Authorization).toBe("Bearer token-de-prueba");
  });
});
