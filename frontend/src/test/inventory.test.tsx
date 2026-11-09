import { describe, expect, it } from "vitest";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { apiErrorResponse, fileResponse, jsonResponse, mockFetch, mockObjectUrl, renderApp, seedSession } from "./harness";
import { PRODUCTS, catalogResponse, inventoryRow, movement, page } from "./catalog";

function inventoryRoutes(overrides: (url: string, init?: RequestInit) => Response | undefined = () => undefined) {
  return (url: string, init?: RequestInit) => {
    const custom = overrides(url, init);
    if (custom) return custom;
    const catalog = catalogResponse(url);
    if (catalog) return catalog;
    if (url.includes("/inventory-movements")) return jsonResponse(200, page([movement()]));
    if (url.includes("/inventory")) return jsonResponse(200, page([inventoryRow()]));
    if (url.includes("/products")) return jsonResponse(200, page(PRODUCTS));
    return jsonResponse(200, page([]));
  };
}

async function openAdjustment() {
  await userEvent.click(await screen.findByRole("button", { name: /ajustar/i }));
  return screen.findByRole("dialog");
}

describe("Pantalla de inventario", () => {
  it("muestra stock, stock mínimo y estado de reabastecimiento", async () => {
    seedSession("OPERATOR");
    mockFetch(inventoryRoutes());
    renderApp("/inventario");

    const row = (await screen.findByText(/SKU-001 — Cemento gris/)).closest("tr")!;
    expect(within(row).getByText("Sucursal Centro")).toBeInTheDocument();
    expect(within(row).getByText("5")).toBeInTheDocument();
    expect(within(row).getByText("10")).toBeInTheDocument();
    // 5 <= 10: el mismo umbral que aplica el filtro lowStock del backend.
    expect(within(row).getByText("Reabastecer")).toBeInTheDocument();
  });

  it("marca como normal el stock por encima del mínimo", async () => {
    seedSession("OPERATOR");
    mockFetch(inventoryRoutes((url) =>
      url.includes("/inventory?") ? jsonResponse(200, page([inventoryRow({ quantityOnHand: 40 })])) : undefined,
    ));
    renderApp("/inventario");

    expect(await screen.findByText("Normal")).toBeInTheDocument();
  });

  it("permite consultar el inventario de otra sucursal", async () => {
    seedSession("OPERATOR");
    const fetchSpy = mockFetch(inventoryRoutes());
    renderApp("/inventario");

    // Por defecto consulta la sucursal del usuario.
    await waitFor(() =>
      expect(fetchSpy.mock.calls.some(([url]) => String(url).includes("/inventory?branchId=1"))).toBe(true),
    );

    await userEvent.selectOptions(await screen.findByLabelText(/sucursal/i), "2");
    await waitFor(() =>
      expect(fetchSpy.mock.calls.some(([url]) => String(url).includes("/inventory?branchId=2"))).toBe(true),
    );
  });

  it("pide al backend solo las filas bajo mínimo cuando se activa el filtro", async () => {
    seedSession("OPERATOR");
    const fetchSpy = mockFetch(inventoryRoutes());
    renderApp("/inventario");

    await userEvent.click(await screen.findByLabelText(/solo por debajo del stock mínimo/i));
    await waitFor(() =>
      expect(fetchSpy.mock.calls.some(([url]) => String(url).includes("lowStock=true"))).toBe(true),
    );
  });

  it("muestra el error del backend cuando la consulta falla", async () => {
    seedSession("OPERATOR");
    mockFetch(inventoryRoutes((url) =>
      url.includes("/inventory?") ? apiErrorResponse(403, "SUCURSAL_NO_AUTORIZADA", "Sin acceso a la sucursal.") : undefined,
    ));
    renderApp("/inventario");

    expect(await screen.findByRole("alert")).toHaveTextContent(/no tienes acceso a la sucursal/i);
    // Un 403 no cierra la sesión, a diferencia de un 401.
    expect(sessionStorage.getItem("inventario.accessToken")).not.toBeNull();
  });

  describe("permisos visuales del ajuste manual", () => {
    it("ofrece ajustar sobre la sucursal propia a un rol autorizado", async () => {
      seedSession("OPERATOR");
      mockFetch(inventoryRoutes());
      renderApp("/inventario");

      expect(await screen.findByRole("button", { name: /ajustar/i })).toBeInTheDocument();
    });

    it("no ofrece ajustar sobre una sucursal ajena", async () => {
      seedSession("OPERATOR");
      mockFetch(inventoryRoutes((url) =>
        url.includes("/inventory?") ? jsonResponse(200, page([inventoryRow({ branchId: "2" })])) : undefined,
      ));
      renderApp("/inventario");

      const table = await screen.findByRole("table");
      expect(within(table).getByText("Sucursal Norte")).toBeInTheDocument();
      expect(within(table).queryByRole("button", { name: /ajustar/i })).not.toBeInTheDocument();
    });

    it("no ofrece ajustar a un rol sin permiso de escritura de inventario", async () => {
      seedSession("MANAGER");
      mockFetch(inventoryRoutes());
      renderApp("/inventario");

      expect(await screen.findByText(/SKU-001/)).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: /ajustar/i })).not.toBeInTheDocument();
    });

    it("un ADMIN puede ajustar cualquier sucursal", async () => {
      seedSession("ADMIN");
      mockFetch(inventoryRoutes((url) =>
        url.includes("/inventory?") ? jsonResponse(200, page([inventoryRow({ branchId: "2" })])) : undefined,
      ));
      renderApp("/inventario");

      expect(await screen.findByRole("button", { name: /ajustar/i })).toBeInTheDocument();
    });
  });

  describe("ajuste manual", () => {
    it("exige cantidad positiva y motivo escrito antes de enviar", async () => {
      seedSession("OPERATOR");
      const fetchSpy = mockFetch(inventoryRoutes());
      renderApp("/inventario");

      const dialog = await openAdjustment();
      await userEvent.click(within(dialog).getByRole("button", { name: /continuar/i }));

      expect(await screen.findByText(/la cantidad es obligatoria/i)).toBeInTheDocument();
      expect(screen.getByText(/explica el motivo del ajuste/i)).toBeInTheDocument();
      expect(fetchSpy.mock.calls.some(([url]) => String(url).includes("/inventory/adjustments"))).toBe(false);
    });

    it("confirma el resumen y actualiza el inventario tras registrar el ajuste", async () => {
      seedSession("OPERATOR");
      let adjusted = false;
      const fetchSpy = mockFetch(
        inventoryRoutes((url, init) => {
          if (url.includes("/inventory/adjustments") && init?.method === "POST") {
            adjusted = true;
            return jsonResponse(201, movement());
          }
          if (url.includes("/inventory?")) {
            return jsonResponse(200, page([inventoryRow({ quantityOnHand: adjusted ? 15 : 5 })]));
          }
          return undefined;
        }),
      );
      renderApp("/inventario");

      const dialog = await openAdjustment();
      await userEvent.type(within(dialog).getByLabelText(/cantidad/i), "10");
      await userEvent.type(within(dialog).getByLabelText(/motivo del ajuste/i), "Conteo físico");
      await userEvent.click(within(dialog).getByRole("button", { name: /continuar/i }));

      // Acción sensible: se resume lo que va a ocurrir antes de ejecutarla.
      const confirmation = await screen.findByRole("dialog", { name: /confirmar ajuste/i });
      expect(confirmation).toHaveTextContent(/sumará/i);
      expect(confirmation).toHaveTextContent(/Cemento gris/);
      expect(fetchSpy.mock.calls.some(([url]) => String(url).includes("/inventory/adjustments"))).toBe(false);

      await userEvent.click(within(confirmation).getByRole("button", { name: /registrar ajuste/i }));

      const post = await waitFor(() => {
        const call = fetchSpy.mock.calls.find(([url]) => String(url).includes("/inventory/adjustments"));
        expect(call).toBeDefined();
        return call!;
      });
      expect(JSON.parse(String((post[1] as RequestInit).body))).toMatchObject({
        branchId: 1,
        productId: 10,
        direction: "INGRESO",
        quantity: 10,
        notes: "Conteo físico",
      });
      // Revalidación: la tabla refleja el stock nuevo sin recargar la página.
      expect(await screen.findByText("15")).toBeInTheDocument();
    });

    it("muestra el error de negocio del backend en lugar de ocultarlo", async () => {
      seedSession("OPERATOR");
      mockFetch(
        inventoryRoutes((url, init) =>
          url.includes("/inventory/adjustments") && init?.method === "POST"
            ? apiErrorResponse(422, "STOCK_INSUFICIENTE", "Stock insuficiente: disponible 5, solicitado 10.")
            : undefined,
        ),
      );
      renderApp("/inventario");

      const dialog = await openAdjustment();
      await userEvent.selectOptions(within(dialog).getByLabelText(/tipo de movimiento/i), "RETIRO");
      await userEvent.type(within(dialog).getByLabelText(/cantidad/i), "10");
      await userEvent.type(within(dialog).getByLabelText(/motivo del ajuste/i), "Merma");
      await userEvent.click(within(dialog).getByRole("button", { name: /continuar/i }));
      await userEvent.click(await screen.findByRole("button", { name: /registrar ajuste/i }));

      expect(await screen.findByText(/stock insuficiente: disponible 5/i)).toBeInTheDocument();
      // El diálogo sigue abierto: nada se dio por bueno.
      expect(screen.getByRole("dialog", { name: /confirmar ajuste/i })).toBeInTheDocument();
    });

    it("descarta el error anterior al volver al formulario y corregir los datos", async () => {
      seedSession("OPERATOR");
      mockFetch(
        inventoryRoutes((url, init) =>
          url.includes("/inventory/adjustments") && init?.method === "POST"
            ? apiErrorResponse(422, "STOCK_INSUFICIENTE", "Stock insuficiente: disponible 5, solicitado 10.")
            : undefined,
        ),
      );
      renderApp("/inventario");

      const dialog = await openAdjustment();
      await userEvent.selectOptions(within(dialog).getByLabelText(/tipo de movimiento/i), "RETIRO");
      await userEvent.type(within(dialog).getByLabelText(/cantidad/i), "10");
      await userEvent.type(within(dialog).getByLabelText(/motivo del ajuste/i), "Merma");
      await userEvent.click(within(dialog).getByRole("button", { name: /continuar/i }));
      await userEvent.click(await screen.findByRole("button", { name: /registrar ajuste/i }));
      expect(await screen.findByText(/stock insuficiente/i)).toBeInTheDocument();

      await userEvent.click(screen.getByRole("button", { name: /volver/i }));
      await userEvent.clear(screen.getByLabelText(/cantidad/i));
      await userEvent.type(screen.getByLabelText(/cantidad/i), "2");
      await userEvent.click(screen.getByRole("button", { name: /continuar/i }));

      // El resumen corregido no puede arrastrar el error del intento anterior.
      const confirmation = await screen.findByRole("dialog", { name: /confirmar ajuste/i });
      expect(confirmation).toHaveTextContent(/restará/i);
      expect(screen.queryByText(/stock insuficiente/i)).not.toBeInTheDocument();
    });
  });
});

describe("Historial de movimientos", () => {
  it("lista los movimientos con su origen y notas", async () => {
    seedSession("OPERATOR");
    mockFetch(inventoryRoutes());
    renderApp("/inventario/movimientos");

    const table = await screen.findByRole("table");
    const row = within(table).getByText(/SKU-001 — Cemento gris/).closest("tr")!;
    expect(within(row).getByText("Ingreso")).toBeInTheDocument();
    expect(within(row).getByText("AJUSTE_INGRESO")).toBeInTheDocument();
    expect(within(row).getByText("Ajuste manual")).toBeInTheDocument();
    expect(within(row).getByText("Conteo físico")).toBeInTheDocument();
  });

  it("identifica el documento que originó un movimiento de venta", async () => {
    seedSession("OPERATOR");
    mockFetch(inventoryRoutes((url) =>
      url.includes("/inventory-movements")
        ? jsonResponse(200, page([movement({ direction: "RETIRO", reason: "VENTA", source: { type: "SALE", id: "77" } })]))
        : undefined,
    ));
    renderApp("/inventario/movimientos");

    expect(await screen.findByText("Venta #77")).toBeInTheDocument();
  });

  it("muestra el equivalente en unidad base cuando el movimiento usa una unidad alternativa", async () => {
    seedSession("OPERATOR");
    mockFetch(inventoryRoutes((url) =>
      url.includes("/inventory-movements")
        ? jsonResponse(200, page([movement({ quantity: 2, unitOfMeasureId: "2" })]))
        : undefined,
    ));
    renderApp("/inventario/movimientos");

    const table = await screen.findByRole("table");
    // CJA tiene factor 12 hacia la unidad base (UND, PRODUCT_UNITS en catalog.ts): 2 × 12 = 24.
    expect(within(table).getByText("= 24 UND")).toBeInTheDocument();
  });

  it("no muestra equivalente cuando el movimiento ya está en la unidad base", async () => {
    seedSession("OPERATOR");
    mockFetch(inventoryRoutes());
    renderApp("/inventario/movimientos");

    const table = await screen.findByRole("table");
    expect(within(table).queryByText(/^=/)).not.toBeInTheDocument();
  });

  it("aplica los filtros que llegan por la URL desde la tabla de inventario", async () => {
    seedSession("OPERATOR");
    const fetchSpy = mockFetch(inventoryRoutes());
    renderApp("/inventario/movimientos?branchId=2&productId=10");

    await waitFor(() => {
      const called = fetchSpy.mock.calls.map(([url]) => String(url));
      expect(called.some((url) => url.includes("/inventory-movements?branchId=2&productId=10"))).toBe(true);
    });
  });

  it("muestra el estado vacío cuando no hay movimientos", async () => {
    seedSession("OPERATOR");
    mockFetch(inventoryRoutes((url) => (url.includes("/inventory-movements") ? jsonResponse(200, page([])) : undefined)));
    renderApp("/inventario/movimientos");

    expect(await screen.findByText(/no hay movimientos que coincidan/i)).toBeInTheDocument();
  });

  it("exporta a Excel con el rango de fechas elegido y descarga el archivo con su nombre legible", async () => {
    seedSession("OPERATOR");
    const { createObjectURL, downloadedFilenames } = mockObjectUrl();
    const fetchSpy = mockFetch(inventoryRoutes((url) =>
      url.includes("/reports/inventory-movements/export") ? fileResponse("movimientos-inventario-20260829.xlsx") : undefined,
    ));
    renderApp("/inventario/movimientos");

    await screen.findByRole("table");
    await userEvent.click(screen.getByRole("button", { name: /exportar a excel/i }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/desde/i), { target: { value: "2026-08-01" } });
    fireEvent.change(within(dialog).getByLabelText(/hasta/i), { target: { value: "2026-08-31" } });
    await userEvent.click(within(dialog).getByRole("button", { name: /^exportar$/i }));

    await waitFor(() => expect(createObjectURL).toHaveBeenCalled());
    expect(fetchSpy.mock.calls.some(([url]) => String(url).includes("/reports/inventory-movements/export?"))).toBe(true);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    // El backend manda el nombre dos veces (RFC 2047 y RFC 5987, ver httpClient.ts);
    // debe preferirse la extendida, nunca la codificada `=?UTF-8?Q?...?=`.
    expect(downloadedFilenames).toEqual(["movimientos-inventario-20260829.xlsx"]);
  });
});
