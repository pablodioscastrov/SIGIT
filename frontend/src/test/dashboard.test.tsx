import { describe, expect, it } from "vitest";
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { jsonResponse, mockFetch, renderApp, seedSession } from "./harness";
import { dashboardRoutes, replenishmentDashboard, salesTrend } from "./catalog";

describe("Dashboard de una sucursal", () => {
  it("OPERATOR ve su sucursal fija y los cuatro paneles con datos", async () => {
    seedSession("OPERATOR");
    mockFetch(dashboardRoutes());
    renderApp("/dashboard");

    expect(await screen.findByText("Sucursal Centro")).toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: /sucursal/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /comparar sucursales/i })).not.toBeInTheDocument();

    expect(await screen.findByText(/2 venta\(s\)/)).toBeInTheDocument(); // total del mes actual (ver salesTrend())
    expect(screen.getByText("+87.5%")).toBeInTheDocument();
    expect(screen.getAllByText(/Cemento gris/).length).toBeGreaterThan(0);
    expect(screen.getByText("TR-ABC12345")).toBeInTheDocument();
    expect(screen.getByText(/producto\(s\) bajo el umbral/)).toBeInTheDocument();
  });

  it("ADMIN elige la sucursal y ve el enlace a la comparativa", async () => {
    seedSession("ADMIN");
    mockFetch(dashboardRoutes());
    renderApp("/dashboard");

    expect(await screen.findByRole("link", { name: /comparar sucursales/i })).toBeInTheDocument();
    expect(screen.getByText(/selecciona una sucursal/i)).toBeInTheDocument();

    await screen.findByRole("option", { name: "Sucursal Centro" });
    await userEvent.selectOptions(screen.getByLabelText(/^sucursal$/i), "1");

    expect(await screen.findByText("+87.5%")).toBeInTheDocument();
  });

  it("la variación se muestra como no calculable cuando el mes anterior no tuvo ventas", async () => {
    seedSession("OPERATOR");
    mockFetch(dashboardRoutes((url) => (url.includes("/sales-summary") ? jsonResponse(200, salesTrend({ growthVsPreviousMonthPercentage: null })) : undefined)));
    renderApp("/dashboard");

    expect(await screen.findByText(/no calculable/i)).toBeInTheDocument();
  });

  it("muestra el estado vacío del panel de reabastecimiento cuando nada está bajo el umbral", async () => {
    seedSession("OPERATOR");
    mockFetch(
      dashboardRoutes((url) =>
        url.includes("/dashboard/replenishment") ? jsonResponse(200, replenishmentDashboard({ lowStockCount: 0, mostUrgent: [] })) : undefined,
      ),
    );
    renderApp("/dashboard");

    expect(await screen.findByText(/ningún producto está por debajo/i)).toBeInTheDocument();
  });

  it("muestra el error del backend en un panel sin ocultarlo ni romper los demás", async () => {
    seedSession("OPERATOR");
    mockFetch(
      dashboardRoutes((url) =>
        url.includes("/dashboard/active-transfers") ? new Response(JSON.stringify({ error: { code: "ERROR_INTERNO", message: "Fallo.", status: 500, requestId: "req-de-prueba" } }), { status: 500 }) : undefined,
      ),
    );
    renderApp("/dashboard");

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/req-de-prueba/);
    // El resto del dashboard sigue funcionando aunque un panel falle.
    expect((await screen.findAllByText(/Cemento gris/)).length).toBeGreaterThan(0);
  });
});

describe("Comparativa entre sucursales", () => {
  it("OPERATOR forzando la URL cae en Sin permiso", async () => {
    seedSession("OPERATOR");
    mockFetch(dashboardRoutes());
    renderApp("/dashboard/comparativa");

    expect(await screen.findByText(/sin permiso/i)).toBeInTheDocument();
  });

  it("MANAGER ve una fila por sucursal, incluida la que no tiene datos", async () => {
    seedSession("MANAGER");
    mockFetch(dashboardRoutes());
    renderApp("/dashboard/comparativa");

    const table = await screen.findByRole("table");
    const rowA = within(table).getByText("Sucursal Centro").closest("tr")!;
    const rowB = within(table).getByText("Sucursal Norte").closest("tr")!;
    expect(within(rowA).getByText(/\$\s*150/)).toBeInTheDocument();
    // Las tres métricas de esta sucursal son 0: confirmamos que la fila no se
    // omite (BR-043) contando las tres celdas, no una sola "0" ambigua. Las
    // ventas del mes se muestran en formato de moneda ("$ 0"), así que solo
    // las otras dos (transferencias activas, productos bajo mínimo) quedan
    // como "0" sin formato.
    expect(within(rowB).getByText(/\$\s*0/)).toBeInTheDocument();
    expect(within(rowB).getAllByText("0")).toHaveLength(2);
  });
});
