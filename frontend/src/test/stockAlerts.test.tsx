import { describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { apiErrorResponse, jsonResponse, mockEventSource, mockFetch, renderApp, seedSession, MockEventSource } from "./harness";
import { BRANCHES, catalogResponse, page, stockAlert } from "./catalog";

function alertRoutes(overrides: (url: string) => Response | undefined = () => undefined) {
  return (url: string) => {
    const custom = overrides(url);
    if (custom) return custom;
    const catalog = catalogResponse(url);
    if (catalog) return catalog;
    if (url.includes("/stock-alerts")) return jsonResponse(200, page([stockAlert()]));
    return jsonResponse(200, page([]));
  };
}

describe("Centro de alertas de stock", () => {
  it("muestra el listado con producto, sucursal, stock, mínimo y estado", async () => {
    seedSession("OPERATOR");
    mockFetch(alertRoutes());
    renderApp("/inventario/alertas");

    const row = (await screen.findByText(/SKU-001 — Cemento gris/)).closest("tr")!;
    expect(within(row).getByText("Sucursal Centro")).toBeInTheDocument();
    expect(within(row).getByText("5")).toBeInTheDocument();
    expect(within(row).getByText("10")).toBeInTheDocument();
    expect(within(row).getByText("Activa")).toBeInTheDocument();
    expect(within(row).getByText("—")).toBeInTheDocument(); // sin resolver
  });

  it("muestra el estado vacío cuando no hay alertas activas", async () => {
    seedSession("OPERATOR");
    mockFetch(alertRoutes((url) => (url.includes("/stock-alerts") ? jsonResponse(200, page([])) : undefined)));
    renderApp("/inventario/alertas");

    expect(await screen.findByText(/ningún producto está por debajo de su stock mínimo/i)).toBeInTheDocument();
  });

  it("muestra el error del backend cuando falla la consulta", async () => {
    seedSession("OPERATOR");
    mockFetch(alertRoutes((url) => (url.includes("/stock-alerts") ? apiErrorResponse(500, "ERROR_INTERNO", "Fallo interno.") : undefined)));
    renderApp("/inventario/alertas");

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/error inesperado/i);
    expect(alert).toHaveTextContent(/req-de-prueba/);
  });

  it("una alerta resuelta muestra su fecha de resolución, no un guion", async () => {
    seedSession("OPERATOR");
    mockFetch(
      alertRoutes((url) =>
        url.includes("/stock-alerts")
          ? jsonResponse(200, page([stockAlert({ status: "RESOLVED", resolvedAt: "2026-08-27T15:00:00Z" })]))
          : undefined,
      ),
    );
    renderApp("/inventario/alertas");

    const row = (await screen.findByText(/SKU-001 — Cemento gris/)).closest("tr")!;
    expect(within(row).getByText("Resuelta")).toBeInTheDocument();
    expect(within(row).queryByText("—")).not.toBeInTheDocument();
  });

  it("cambiar la sucursal actualiza la URL y vuelve a consultar", async () => {
    seedSession("ADMIN");
    const fetchSpy = mockFetch(alertRoutes());
    renderApp("/inventario/alertas");

    await screen.findByText(/SKU-001 — Cemento gris/);
    const callsBefore = fetchSpy.mock.calls.length;

    await userEvent.selectOptions(screen.getByLabelText(/sucursal/i), BRANCHES[1].id);

    await waitFor(() => expect(fetchSpy.mock.calls.length).toBeGreaterThan(callsBefore));
    const lastCall = String(fetchSpy.mock.calls[fetchSpy.mock.calls.length - 1][0]);
    expect(lastCall).toContain(`branchId=${BRANCHES[1].id}`);
  });

  it("cambiar el filtro de estado a Resueltas vuelve a consultar con status=RESOLVED", async () => {
    seedSession("OPERATOR");
    const fetchSpy = mockFetch(alertRoutes());
    renderApp("/inventario/alertas");

    await screen.findByText(/SKU-001 — Cemento gris/);
    await userEvent.selectOptions(screen.getByLabelText(/^estado$/i), "RESOLVED");

    await waitFor(() =>
      expect(fetchSpy.mock.calls.some(([url]) => String(url).includes("/stock-alerts") && String(url).includes("status=RESOLVED"))).toBe(true),
    );
  });

  describe("Actualización near-real-time", () => {
    it("una señal stock-alert.triggered refresca el listado sin acción del usuario", async () => {
      seedSession("OPERATOR");
      mockEventSource();
      let triggered = false;
      const fetchSpy = mockFetch(
        alertRoutes((url) => (url.includes("/stock-alerts") ? jsonResponse(200, page(triggered ? [stockAlert(), stockAlert({ id: "901", sku: "SKU-002", name: "Arena fina" })] : [stockAlert()])) : undefined)),
      );
      renderApp("/inventario/alertas");

      await screen.findByText(/SKU-001 — Cemento gris/);
      const callsBefore = fetchSpy.mock.calls.length;

      triggered = true;
      const source = MockEventSource.instances[MockEventSource.instances.length - 1];
      source.emit("stock-alert.triggered", { type: "stock-alert.triggered", branchIds: ["1"], resourceId: "11", occurredAt: "2026-08-27T12:00:00Z" });

      await waitFor(() => expect(fetchSpy.mock.calls.length).toBeGreaterThan(callsBefore));
      expect(await screen.findByText(/SKU-002 — Arena fina/)).toBeInTheDocument();
    });

    it("al reconectar (evento onopen) también se reconcilia contra REST", async () => {
      seedSession("OPERATOR");
      mockEventSource();
      const fetchSpy = mockFetch(alertRoutes());
      renderApp("/inventario/alertas");

      await screen.findByText(/SKU-001 — Cemento gris/);
      const callsBefore = fetchSpy.mock.calls.length;

      const source = MockEventSource.instances[MockEventSource.instances.length - 1];
      source.onopen?.();

      await waitFor(() => expect(fetchSpy.mock.calls.length).toBeGreaterThan(callsBefore));
    });
  });
});
