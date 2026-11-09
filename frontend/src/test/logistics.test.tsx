import { describe, expect, it } from "vitest";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { apiErrorResponse, fileResponse, jsonResponse, mockFetch, mockObjectUrl, renderApp, seedSession } from "./harness";
import { catalogResponse, logisticsCompliance, page } from "./catalog";

function routeRoutes(overrides: (url: string, init?: RequestInit) => Response | undefined = () => undefined) {
  return (url: string, init?: RequestInit) => {
    const custom = overrides(url, init);
    if (custom) return custom;
    const catalog = catalogResponse(url);
    if (catalog) return catalog;
    return jsonResponse(200, page([]));
  };
}

describe("Rutas", () => {
  it("lista rutas con origen, destino y clasificación", async () => {
    seedSession("OPERATOR");
    mockFetch(routeRoutes());
    renderApp("/logistica/rutas");

    const table = await screen.findByRole("table");
    const row = within(table).getByText("Sucursal Centro").closest("tr")!;
    expect(within(row).getByText("Sucursal Norte")).toBeInTheDocument();
    expect(within(row).getByText("Tiempo")).toBeInTheDocument();
  });

  it("OPERATOR no ve el formulario de alta ni reclasificar", async () => {
    seedSession("OPERATOR");
    mockFetch(routeRoutes());
    renderApp("/logistica/rutas");

    await screen.findByRole("table");
    expect(screen.queryByRole("heading", { name: /nueva ruta/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /guardar/i })).not.toBeInTheDocument();
  });

  it("MANAGER puede crear una ruta", async () => {
    seedSession("MANAGER");
    const fetchSpy = mockFetch(
      routeRoutes((url, init) => {
        if (url.includes("/routes") && init?.method === "POST") {
          return jsonResponse(201, { id: "2", originBranchId: "2", destinationBranchId: "1", classification: "COST" });
        }
        return undefined;
      }),
    );
    renderApp("/logistica/rutas");

    await screen.findByRole("table");
    const form = (await screen.findByRole("heading", { name: /nueva ruta/i })).closest("form")!;
    await userEvent.selectOptions(within(form).getByLabelText(/^origen$/i), "2");
    await userEvent.selectOptions(within(form).getByLabelText(/^destino$/i), "1");
    await userEvent.selectOptions(within(form).getByLabelText(/^clasificación$/i), "COST");
    await userEvent.click(within(form).getByRole("button", { name: /crear ruta/i }));

    const post = await waitFor(() => {
      const call = fetchSpy.mock.calls.find(([url, init]) => String(url).includes("/routes") && (init as RequestInit | undefined)?.method === "POST");
      expect(call).toBeDefined();
      return call!;
    });
    expect(JSON.parse(String((post[1] as RequestInit).body))).toMatchObject({
      originBranchId: 2,
      destinationBranchId: 1,
      classification: "COST",
    });
  });

  it("rechaza en el cliente origen igual a destino", async () => {
    seedSession("MANAGER");
    const fetchSpy = mockFetch(routeRoutes());
    renderApp("/logistica/rutas");

    await screen.findByRole("table");
    const form = (await screen.findByRole("heading", { name: /nueva ruta/i })).closest("form")!;
    await userEvent.selectOptions(within(form).getByLabelText(/^origen$/i), "1");
    await userEvent.selectOptions(within(form).getByLabelText(/^destino$/i), "1");
    await userEvent.click(within(form).getByRole("button", { name: /crear ruta/i }));

    expect(await screen.findByText(/deben ser sucursales distintas/i)).toBeInTheDocument();
    expect(fetchSpy.mock.calls.some(([, init]) => (init as RequestInit | undefined)?.method === "POST")).toBe(false);
  });

  it("muestra el 409 de una ruta duplicada sin ocultarlo", async () => {
    seedSession("MANAGER");
    mockFetch(
      routeRoutes((url, init) =>
        url.includes("/routes") && init?.method === "POST"
          ? apiErrorResponse(409, "RUTA_YA_EXISTE", "Ya existe una ruta clasificada para ese par origen-destino.")
          : undefined,
      ),
    );
    renderApp("/logistica/rutas");

    await screen.findByRole("table");
    await userEvent.selectOptions(screen.getByLabelText(/^origen$/i), "1");
    await userEvent.selectOptions(screen.getByLabelText(/^destino$/i), "2");
    await userEvent.click(screen.getByRole("button", { name: /crear ruta/i }));

    expect(await screen.findByText(/ya existe una ruta clasificada/i)).toBeInTheDocument();
  });
});

describe("Cumplimiento logístico", () => {
  it("muestra el resumen y el detalle por ruta calculados por el backend", async () => {
    seedSession("MANAGER");
    mockFetch(
      routeRoutes((url) => (url.includes("/reports/logistics-compliance") ? jsonResponse(200, logisticsCompliance()) : undefined)),
    );
    renderApp("/logistica/cumplimiento");

    await screen.findByText("Resumen");
    const [summaryTable] = screen.getAllByRole("table");
    expect(within(summaryTable).getByText("4")).toBeInTheDocument();
    expect(within(summaryTable).getByText("66.67%")).toBeInTheDocument();
    expect(within(summaryTable).getByText("12.5 h")).toBeInTheDocument();
  });

  it("OPERATOR ve la sucursal fija a la suya, sin selector", async () => {
    seedSession("OPERATOR");
    mockFetch(
      routeRoutes((url) => (url.includes("/reports/logistics-compliance") ? jsonResponse(200, logisticsCompliance()) : undefined)),
    );
    renderApp("/logistica/cumplimiento");

    await screen.findByText("Resumen");
    expect(screen.queryByLabelText(/^sucursal$/i)).not.toBeInTheDocument();
    expect(screen.getByText("Sucursal Centro")).toBeInTheDocument();
  });

  it("muestra el estado vacío por ruta cuando no hay transferencias despachadas", async () => {
    seedSession("MANAGER");
    mockFetch(
      routeRoutes((url) =>
        url.includes("/reports/logistics-compliance")
          ? jsonResponse(200, logisticsCompliance({ byRoute: [] }))
          : undefined,
      ),
    );
    renderApp("/logistica/cumplimiento");

    expect(await screen.findByText(/no hay transferencias despachadas/i)).toBeInTheDocument();
  });

  it("exporta a Excel con el rango de fechas elegido y descarga el archivo", async () => {
    seedSession("MANAGER");
    const { createObjectURL } = mockObjectUrl();
    const fetchSpy = mockFetch(
      routeRoutes((url) => {
        if (url.includes("/reports/logistics-compliance/export")) return fileResponse("cumplimiento.xlsx");
        if (url.includes("/reports/logistics-compliance")) return jsonResponse(200, logisticsCompliance());
        return undefined;
      }),
    );
    renderApp("/logistica/cumplimiento");

    await screen.findByText("Resumen");
    await userEvent.click(screen.getByRole("button", { name: /exportar a excel/i }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/desde/i), { target: { value: "2026-08-01" } });
    fireEvent.change(within(dialog).getByLabelText(/hasta/i), { target: { value: "2026-08-31" } });
    await userEvent.click(within(dialog).getByRole("button", { name: /^exportar$/i }));

    await waitFor(() => expect(createObjectURL).toHaveBeenCalled());
    expect(fetchSpy.mock.calls.some(([url]) => String(url).includes("/reports/logistics-compliance/export?"))).toBe(true);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});
