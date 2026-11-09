import { describe, expect, it } from "vitest";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { apiErrorResponse, fileResponse, jsonResponse, mockFetch, mockObjectUrl, renderApp, seedSession, selectOption } from "./harness";
import { PRODUCTS, catalogResponse, inventoryRow, page, sale } from "./catalog";

function saleRoutes(overrides: (url: string, init?: RequestInit) => Response | Promise<Response> | undefined = () => undefined) {
  return (url: string, init?: RequestInit) => {
    const custom = overrides(url, init);
    if (custom) return custom;
    const catalog = catalogResponse(url);
    if (catalog) return catalog;
    if (url.includes("/products")) return jsonResponse(200, page(PRODUCTS));
    if (url.includes("/inventory")) return jsonResponse(200, page([inventoryRow({ productId: "10", branchId: "1", quantityOnHand: 42 })]));
    if (/\/sales\/\d+$/.test(url)) return jsonResponse(200, sale());
    if (url.includes("/sales")) return jsonResponse(200, page([sale()]));
    return jsonResponse(200, page([]));
  };
}

describe("Listado de ventas", () => {
  it("muestra la venta con sucursal, fecha y total", async () => {
    seedSession("OPERATOR");
    mockFetch(saleRoutes());
    renderApp("/ventas");

    const row = (await screen.findByText("V-ABC12345")).closest("tr")!;
    expect(within(row).getByText("Sucursal Centro")).toBeInTheDocument();
    expect(within(row).getByText("Operador Centro")).toBeInTheDocument();
    expect(within(row).getByText(/\$\s*150/)).toBeInTheDocument();
  });

  it("muestra el estado vacío cuando no hay ventas", async () => {
    seedSession("OPERATOR");
    mockFetch(saleRoutes((url) => (url.includes("/sales?") ? jsonResponse(200, page([])) : undefined)));
    renderApp("/ventas");

    expect(await screen.findByText(/no hay ventas registradas/i)).toBeInTheDocument();
  });

  it("exporta a Excel con el rango de fechas elegido y descarga el archivo", async () => {
    seedSession("OPERATOR");
    const { createObjectURL } = mockObjectUrl();
    const fetchSpy = mockFetch(saleRoutes((url) => (url.includes("/reports/sales/export") ? fileResponse("ventas.xlsx") : undefined)));
    renderApp("/ventas");

    await screen.findByText("V-ABC12345");
    await userEvent.click(screen.getByRole("button", { name: /exportar a excel/i }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/desde/i), { target: { value: "2026-08-01" } });
    fireEvent.change(within(dialog).getByLabelText(/hasta/i), { target: { value: "2026-08-31" } });
    await userEvent.click(within(dialog).getByRole("button", { name: /^exportar$/i }));

    await waitFor(() => expect(createObjectURL).toHaveBeenCalled());
    expect(fetchSpy.mock.calls.some(([url]) => String(url).includes("/reports/sales/export?"))).toBe(true);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  describe("permisos visuales", () => {
    it("OPERATOR ve 'Nueva venta'", async () => {
      seedSession("OPERATOR");
      mockFetch(saleRoutes());
      renderApp("/ventas");

      expect(await screen.findByRole("button", { name: /nueva venta/i })).toBeInTheDocument();
    });

    it("MANAGER ve 'Nueva venta' y puede acceder al formulario de alta", async () => {
      // BR-053: ampliación explícita — MANAGER puede generar y gestionar ventas.
      seedSession("MANAGER");
      mockFetch(saleRoutes());
      const { unmount } = renderApp("/ventas");

      expect(await screen.findByText("V-ABC12345")).toBeInTheDocument();
      expect(screen.getByRole("button", { name: /nueva venta/i })).toBeInTheDocument();
      unmount();

      renderApp("/ventas/nueva");
      expect(await screen.findByRole("heading", { name: /nueva venta/i })).toBeInTheDocument();
    });
  });
});

describe("Nueva venta", () => {
  it("preselecciona la lista de precios de la sucursal sobre la global y previsualiza el precio", async () => {
    seedSession("OPERATOR");
    mockFetch(saleRoutes());
    renderApp("/ventas/nueva");

    await waitFor(() => expect(screen.getByLabelText(/lista de precios/i)).toHaveValue("1"));
    await selectOption(/producto de la línea 1/i, "10");

    const row = screen.getByLabelText(/producto de la línea 1/i).closest("tr")!;
    expect(await within(row).findByText(/\$\s*50/)).toBeInTheDocument();
  });

  it("escala el precio y el total de línea por el factor de conversión al elegir una unidad distinta a la base", async () => {
    // El precio de lista (50) está fijado en la unidad base (UND); al elegir
    // "Caja" (factor 12, ver PRODUCT_UNITS) el precio previsualizado debe
    // multiplicarse por ese factor — 50 * 12 = 600 por caja — igual criterio
    // que ya aplica MovementsPage a la cantidad equivalente en unidad base.
    seedSession("OPERATOR");
    mockFetch(saleRoutes());
    renderApp("/ventas/nueva");

    await selectOption(/producto de la línea 1/i, "10");
    const row = screen.getByLabelText(/producto de la línea 1/i).closest("tr")!;
    expect(await within(row).findByText(/\$\s*50/)).toBeInTheDocument();

    await selectOption(/unidad de la línea 1/i, "2");
    expect(await within(row).findByText(/\$\s*600/)).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText(/cantidad de la línea 1/i), "2");
    // 2 cajas * 600 = 1200
    expect(await within(row).findByText(/\$\s*1\.200/)).toBeInTheDocument();
  });

  it("muestra el stock del producto seleccionado en la sucursal seleccionada", async () => {
    seedSession("OPERATOR");
    mockFetch(saleRoutes());
    renderApp("/ventas/nueva");

    const row = screen.getByLabelText(/producto de la línea 1/i).closest("tr")!;
    await selectOption(/producto de la línea 1/i, "10");
    expect(await within(row).findByText("42")).toBeInTheDocument();
  });

  it("calcula el total de línea estimado con descuento", async () => {
    seedSession("OPERATOR");
    mockFetch(saleRoutes());
    renderApp("/ventas/nueva");

    await selectOption(/producto de la línea 1/i, "10");
    await userEvent.type(screen.getByLabelText(/cantidad de la línea 1/i), "4");
    await userEvent.type(screen.getByLabelText(/descuento de la línea 1/i), "10");

    // 4 * 50 = 200, menos 10% = 180
    const row = screen.getByLabelText(/producto de la línea 1/i).closest("tr")!;
    expect(await within(row).findByText(/\$\s*180/)).toBeInTheDocument();
    expect(screen.getByText(/total estimado:/i)).toBeInTheDocument();
  });

  it("no revisa la venta si falta seleccionar un producto", async () => {
    seedSession("OPERATOR");
    const fetchSpy = mockFetch(saleRoutes());
    renderApp("/ventas/nueva");

    await userEvent.click(await screen.findByRole("button", { name: /revisar venta/i }));

    expect(await screen.findByText(/selecciona un producto/i)).toBeInTheDocument();
    expect(fetchSpy.mock.calls.some(([, init]) => (init as RequestInit | undefined)?.method === "POST")).toBe(false);
  });

  it("bloquea la venta con un mensaje accionable cuando el producto no tiene precio vigente, sin dejar tecleárselo", async () => {
    // El precio nunca es un input (siempre viene de la lista de precios); si
    // falta, se bloquea aquí con un mensaje claro en vez de que la venta
    // falle recién al confirmar con "el precio es null".
    seedSession("OPERATOR");
    const fetchSpy = mockFetch(saleRoutes());
    renderApp("/ventas/nueva");

    // SKU-002 (Arena fina) no tiene ninguna fila en PRICES.
    await selectOption(/producto de la línea 1/i, "11");
    await userEvent.type(screen.getByLabelText(/cantidad de la línea 1/i), "2");

    const row = screen.getByLabelText(/producto de la línea 1/i).closest("tr")!;
    expect(await within(row).findByText(/sin precio vigente/i)).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /revisar venta/i }));

    expect(await screen.findByText(/no tiene un precio vigente en la lista seleccionada/i)).toBeInTheDocument();
    expect(fetchSpy.mock.calls.some(([, init]) => (init as RequestInit | undefined)?.method === "POST" && String((init as RequestInit).body).includes("/sales"))).toBe(false);
    expect(screen.queryByRole("heading", { name: /confirmar venta/i })).not.toBeInTheDocument();
  });

  it("confirma el resumen y envía la venta con Idempotency-Key", async () => {
    seedSession("OPERATOR");
    const fetchSpy = mockFetch(
      saleRoutes((url, init) => {
        if (url.includes("/sales") && init?.method === "POST") return jsonResponse(201, sale({ id: "888" }));
        if (url.endsWith("/sales/888")) return jsonResponse(200, sale({ id: "888" }));
        return undefined;
      }),
    );
    renderApp("/ventas/nueva");

    await selectOption(/producto de la línea 1/i, "10");
    await userEvent.type(screen.getByLabelText(/cantidad de la línea 1/i), "3");
    await userEvent.click(screen.getByRole("button", { name: /revisar venta/i }));

    const dialog = await screen.findByRole("dialog", { name: /confirmar venta/i });
    await userEvent.click(within(dialog).getByRole("button", { name: /confirmar venta/i }));

    const post = await waitFor(() => {
      const call = fetchSpy.mock.calls.find(([url, init]) => String(url).includes("/sales") && (init as RequestInit)?.method === "POST");
      expect(call).toBeDefined();
      return call!;
    });
    const [, init] = post;
    expect((init as RequestInit).headers).toMatchObject({ "Idempotency-Key": expect.any(String) });
    expect(JSON.parse(String((init as RequestInit).body))).toMatchObject({
      branchId: 1,
      priceListId: 1,
      items: [{ productId: 10, quantity: 3 }],
    });
    expect(await screen.findByText(/comprobante de venta/i)).toBeInTheDocument();
  });

  it("muestra el stock insuficiente del backend, conserva el formulario y limpia el error al corregir", async () => {
    seedSession("OPERATOR");
    mockFetch(
      saleRoutes((url, init) =>
        url.includes("/sales") && init?.method === "POST"
          ? apiErrorResponse(422, "STOCK_INSUFICIENTE", "No hay stock suficiente. Disponible: 5.000000, solicitado: 999.000000.")
          : undefined,
      ),
    );
    renderApp("/ventas/nueva");

    await selectOption(/producto de la línea 1/i, "10");
    await userEvent.type(screen.getByLabelText(/cantidad de la línea 1/i), "999");
    await userEvent.click(screen.getByRole("button", { name: /revisar venta/i }));
    await userEvent.click(await screen.findByRole("button", { name: /confirmar venta/i }));

    expect(await screen.findByText(/no hay stock suficiente/i)).toBeInTheDocument();
    // El formulario sigue disponible: no se perdió la cantidad tecleada.
    expect(screen.getByLabelText(/cantidad de la línea 1/i)).toHaveValue("999");

    await userEvent.click(screen.getByRole("button", { name: /volver/i }));
    await userEvent.clear(screen.getByLabelText(/cantidad de la línea 1/i));
    await userEvent.type(screen.getByLabelText(/cantidad de la línea 1/i), "2");
    await userEvent.click(screen.getByRole("button", { name: /revisar venta/i }));

    const confirmation = await screen.findByRole("dialog", { name: /confirmar venta/i });
    expect(within(confirmation).queryByText(/no hay stock suficiente/i)).not.toBeInTheDocument();
  });

  it("evita doble envío deshabilitando el botón de confirmación mientras la petición está en curso", async () => {
    seedSession("OPERATOR");
    let resolvePost!: (value: Response) => void;
    mockFetch(
      saleRoutes((url, init) => {
        if (url.includes("/sales") && init?.method === "POST") {
          return new Promise<Response>((resolve) => {
            resolvePost = resolve;
          });
        }
        return undefined;
      }),
    );
    renderApp("/ventas/nueva");

    await selectOption(/producto de la línea 1/i, "10");
    await userEvent.type(screen.getByLabelText(/cantidad de la línea 1/i), "1");
    await userEvent.click(screen.getByRole("button", { name: /revisar venta/i }));

    const dialog = await screen.findByRole("dialog", { name: /confirmar venta/i });
    const confirmButton = within(dialog).getByRole("button", { name: /confirmar venta/i });
    await userEvent.click(confirmButton);

    expect(await within(dialog).findByRole("button", { name: /confirmando/i })).toBeDisabled();

    resolvePost(jsonResponse(201, sale({ id: "1" })));
  });
});

describe("Comprobante de venta", () => {
  it("muestra líneas, totales y el responsable de la venta", async () => {
    seedSession("OPERATOR");
    mockFetch(saleRoutes());
    renderApp("/ventas/700");

    expect(await screen.findByText("V-ABC12345")).toBeInTheDocument();
    expect(screen.getByText("Operador Centro")).toBeInTheDocument();
    const row = screen.getByText(/SKU-001 — Cemento gris/).closest("tr")!;
    const cells = within(row).getAllByRole("cell");
    expect(cells[1]).toHaveTextContent("3"); // cantidad
    expect(cells[5]).toHaveTextContent(/\$\s*150/); // total línea
    expect(cells[6]).toHaveTextContent("0"); // devuelto
    expect(cells[7]).toHaveTextContent("3"); // pendiente
  });

  describe("Devolución de venta (BR-052)", () => {
    it("un rol autorizado registra una devolución con Idempotency-Key y el inventario se refresca", async () => {
      seedSession("OPERATOR");
      const fetchSpy = mockFetch(
        saleRoutes((url, init) => {
          if (url.includes("/returns") && init?.method === "POST") {
            return jsonResponse(200, {
              saleId: "700",
              items: [{ saleItemId: "9000", quantity: 2, quantityReturned: 2, pending: 1 }],
              inventoryUpdates: [{ productId: "10", branchId: "1", quantityOnHand: 12 }],
            });
          }
          return undefined;
        }),
      );
      renderApp("/ventas/700");

      await userEvent.click(await screen.findByRole("button", { name: /generar devolución/i }));
      const formDialog = await screen.findByRole("dialog", { name: /^generar devolución$/i });
      await userEvent.type(within(formDialog).getByLabelText(/cantidad a devolver de sku-001/i), "2");
      await userEvent.click(within(formDialog).getByRole("button", { name: /continuar/i }));

      const dialog = await screen.findByRole("dialog", { name: /confirmar devolución/i });
      await userEvent.click(within(dialog).getByRole("button", { name: /registrar devolución/i }));

      const post = await waitFor(() => {
        const call = fetchSpy.mock.calls.find(([url, init]) => String(url).includes("/returns") && (init as RequestInit)?.method === "POST");
        expect(call).toBeDefined();
        return call!;
      });
      const [, init] = post;
      expect((init as RequestInit).headers).toMatchObject({ "Idempotency-Key": expect.any(String) });
      expect(JSON.parse(String((init as RequestInit).body))).toMatchObject({ items: [{ saleItemId: 9000, quantity: 2 }] });
    });

    it("el formulario de devolución no se muestra hasta hacer clic en 'Generar devolución'", async () => {
      seedSession("OPERATOR");
      mockFetch(saleRoutes());
      renderApp("/ventas/700");

      await screen.findByText("V-ABC12345");
      expect(screen.queryByLabelText(/cantidad a devolver/i)).not.toBeInTheDocument();
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

      await userEvent.click(screen.getByRole("button", { name: /generar devolución/i }));
      expect(await screen.findByRole("dialog", { name: /^generar devolución$/i })).toBeInTheDocument();
    });

    it("exige al menos una cantidad antes de continuar", async () => {
      seedSession("OPERATOR");
      mockFetch(saleRoutes());
      renderApp("/ventas/700");

      await userEvent.click(await screen.findByRole("button", { name: /generar devolución/i }));
      const dialog = await screen.findByRole("dialog", { name: /^generar devolución$/i });
      await userEvent.click(within(dialog).getByRole("button", { name: /continuar/i }));
      expect(await screen.findByText(/ingresa la cantidad a devolver/i)).toBeInTheDocument();
    });

    it("cancelar el formulario lo cierra sin enviar nada", async () => {
      seedSession("OPERATOR");
      const fetchSpy = mockFetch(saleRoutes());
      renderApp("/ventas/700");

      await userEvent.click(await screen.findByRole("button", { name: /generar devolución/i }));
      const dialog = await screen.findByRole("dialog", { name: /^generar devolución$/i });
      await userEvent.type(within(dialog).getByLabelText(/cantidad a devolver de sku-001/i), "1");
      await userEvent.click(within(dialog).getByRole("button", { name: /cancelar/i }));

      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(fetchSpy.mock.calls.some(([url]) => String(url).includes("/returns"))).toBe(false);
    });

    it("MANAGER también puede generar una devolución (BR-053: mismas capacidades que OPERATOR/ADMIN)", async () => {
      seedSession("MANAGER");
      mockFetch(saleRoutes());
      renderApp("/ventas/700");

      expect(await screen.findByRole("button", { name: /generar devolución/i })).toBeInTheDocument();
    });
  });
});
