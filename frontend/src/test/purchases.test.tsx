import { describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { apiErrorResponse, jsonResponse, mockFetch, renderApp, seedSession, selectOption } from "./harness";
import { PRODUCTS, catalogResponse, page, purchaseOrder } from "./catalog";

function purchaseRoutes(overrides: (url: string, init?: RequestInit) => Response | undefined = () => undefined) {
  return (url: string, init?: RequestInit) => {
    const custom = overrides(url, init);
    if (custom) return custom;
    const catalog = catalogResponse(url);
    if (catalog) return catalog;
    if (url.includes("/products")) return jsonResponse(200, page(PRODUCTS));
    if (/\/purchase-orders\/\d+$/.test(url)) return jsonResponse(200, purchaseOrder());
    if (url.includes("/purchase-orders")) return jsonResponse(200, page([purchaseOrder()]));
    return jsonResponse(200, page([]));
  };
}

describe("Listado de compras", () => {
  it("muestra la orden con proveedor, sucursal, estado y total", async () => {
    seedSession("OPERATOR");
    mockFetch(purchaseRoutes());
    renderApp("/compras");

    const row = (await screen.findByText("OC-ABC12345")).closest("tr")!;
    expect(within(row).getByText("Distribuidora Andina")).toBeInTheDocument();
    expect(within(row).getByText("Sucursal Centro")).toBeInTheDocument();
    expect(within(row).getByText("Creada")).toBeInTheDocument();
    expect(within(row).getByText(/\$\s*310/)).toBeInTheDocument();
  });

  it("muestra el estado vacío cuando no hay órdenes", async () => {
    seedSession("OPERATOR");
    mockFetch(purchaseRoutes((url) => (url.includes("/purchase-orders?") || /\/purchase-orders\?/.test(url) ? jsonResponse(200, page([])) : undefined)));
    renderApp("/compras");

    expect(await screen.findByText(/no hay órdenes de compra/i)).toBeInTheDocument();
  });

  it("muestra el error del backend cuando falla la consulta", async () => {
    seedSession("OPERATOR");
    mockFetch(purchaseRoutes((url) => (url.includes("/purchase-orders") && !url.includes("/nueva") ? apiErrorResponse(500, "ERROR_INTERNO", "Fallo interno.") : undefined)));
    renderApp("/compras");

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/req-de-prueba/);
  });

  describe("permisos visuales", () => {
    it("OPERATOR ve 'Nueva orden' y puede cancelar una orden CREATED", async () => {
      seedSession("OPERATOR");
      mockFetch(purchaseRoutes());
      renderApp("/compras");

      expect(await screen.findByRole("button", { name: /nueva orden/i })).toBeInTheDocument();
      expect(await screen.findByRole("button", { name: /cancelar/i })).toBeInTheDocument();
    });

    it("MANAGER ve las mismas acciones de escritura que ADMIN/OPERATOR", async () => {
      // Ampliación de permisos: MANAGER puede crear y gestionar compras igual que ADMIN.
      seedSession("MANAGER");
      mockFetch(purchaseRoutes());
      renderApp("/compras");

      const row = (await screen.findByText("OC-ABC12345")).closest("tr")!;
      expect(screen.getByRole("button", { name: /nueva orden/i })).toBeInTheDocument();
      expect(within(row).getByRole("button", { name: /cancelar/i })).toBeInTheDocument();
    });

    it("MANAGER puede acceder al formulario de alta", async () => {
      seedSession("MANAGER");
      mockFetch(purchaseRoutes());
      renderApp("/compras/nueva");

      expect(await screen.findByRole("heading", { name: /nueva orden de compra/i })).toBeInTheDocument();
    });
  });
});

describe("Tabla de compras: producto y orden", () => {
  const orderWithArena = purchaseOrder({
    id: "500",
    orderNumber: "OC-1-ARENA",
    items: [{ id: "5001", productId: "11", unitOfMeasureId: "1", quantityOrdered: 5, quantityReceived: 0, pending: 5, unitPrice: 10, discountPercentage: 0, lineTotal: 50 }],
  });
  const orderWithCemento = purchaseOrder({
    id: "501",
    orderNumber: "OC-2-CEMENTO",
    items: [{ id: "5002", productId: "10", unitOfMeasureId: "1", quantityOrdered: 5, quantityReceived: 0, pending: 5, unitPrice: 10, discountPercentage: 0, lineTotal: 50 }],
  });

  function tableRoutes() {
    return purchaseRoutes((url) => (url.includes("/purchase-orders") ? jsonResponse(200, page([orderWithArena, orderWithCemento])) : undefined));
  }

  function orderedOrderNumbers() {
    return screen.getAllByRole("row").slice(1).map((row) => within(row).getByRole("cell", { name: /^OC-/ }).textContent);
  }

  it("muestra el producto de cada orden en su propia columna", async () => {
    seedSession("OPERATOR");
    mockFetch(tableRoutes());
    renderApp("/compras");

    const arenaRow = (await screen.findByText("OC-1-ARENA")).closest("tr")!;
    expect(within(arenaRow).getByText("SKU-002 — Arena fina")).toBeInTheDocument();
    const cementoRow = screen.getByText("OC-2-CEMENTO").closest("tr")!;
    expect(within(cementoRow).getByText("SKU-001 — Cemento gris")).toBeInTheDocument();
  });

  it("ordena la página actual por producto o por proveedor al hacer clic en el encabezado", async () => {
    seedSession("OPERATOR");
    mockFetch(tableRoutes());
    renderApp("/compras");

    await screen.findByText("OC-1-ARENA");
    // Orden tal cual la devuelve el backend: Arena (SKU-002) antes que Cemento (SKU-001).
    expect(orderedOrderNumbers()).toEqual(["OC-1-ARENA", "OC-2-CEMENTO"]);

    await userEvent.click(screen.getByRole("button", { name: /^producto/i }));
    expect(screen.getByText(/orden alfabético dentro de esta página/i)).toBeInTheDocument();
    expect(orderedOrderNumbers()).toEqual(["OC-2-CEMENTO", "OC-1-ARENA"]);

    await userEvent.click(screen.getByRole("button", { name: /^producto/i }));
    expect(orderedOrderNumbers()).toEqual(["OC-1-ARENA", "OC-2-CEMENTO"]);

    await userEvent.click(screen.getByRole("button", { name: /^producto/i }));
    expect(screen.queryByText(/orden alfabético dentro de esta página/i)).not.toBeInTheDocument();
  });
});

describe("Alta de orden de compra", () => {
  it("no envía nada cuando faltan campos obligatorios", async () => {
    seedSession("OPERATOR");
    const fetchSpy = mockFetch(purchaseRoutes());
    renderApp("/compras/nueva");

    await userEvent.click(await screen.findByRole("button", { name: /crear orden/i }));

    expect(await screen.findByText(/selecciona un proveedor/i)).toBeInTheDocument();
    expect(screen.getByText(/producto\.$/i)).toBeInTheDocument();
    expect(fetchSpy.mock.calls.some(([, init]) => (init as RequestInit | undefined)?.method === "POST")).toBe(false);
  });

  it("rechaza en el cliente dos líneas con el mismo producto", async () => {
    seedSession("OPERATOR");
    mockFetch(purchaseRoutes());
    renderApp("/compras/nueva");

    await selectOption(/proveedor/i, "1");
    await userEvent.click(screen.getByRole("button", { name: /agregar línea/i }));

    await selectOption(/producto de la línea 1/i, "10");
    await selectOption(/producto de la línea 2/i, "10");
    await userEvent.type(screen.getByLabelText(/cantidad de la línea 1/i), "5");
    await userEvent.type(screen.getByLabelText(/precio unitario de la línea 1/i), "10");
    await userEvent.type(screen.getByLabelText(/cantidad de la línea 2/i), "5");
    await userEvent.type(screen.getByLabelText(/precio unitario de la línea 2/i), "10");
    await userEvent.click(screen.getByRole("button", { name: /crear orden/i }));

    expect(await screen.findByText(/ya está en otra línea/i)).toBeInTheDocument();
  });

  it("crea la orden con Idempotency-Key y navega al detalle", async () => {
    seedSession("OPERATOR");
    const fetchSpy = mockFetch(
      purchaseRoutes((url, init) => {
        if (url.includes("/purchase-orders") && init?.method === "POST") return jsonResponse(201, purchaseOrder({ id: "999" }));
        if (url.endsWith("/purchase-orders/999")) return jsonResponse(200, purchaseOrder({ id: "999" }));
        return undefined;
      }),
    );
    renderApp("/compras/nueva");

    await selectOption(/proveedor/i, "1");
    await selectOption(/producto de la línea 1/i, "10");
    await userEvent.type(screen.getByLabelText(/cantidad de la línea 1/i), "20");
    await userEvent.type(screen.getByLabelText(/precio unitario de la línea 1/i), "15.5");
    await userEvent.click(screen.getByRole("button", { name: /crear orden/i }));

    const post = await waitFor(() => {
      const call = fetchSpy.mock.calls.find(([url, init]) => String(url).includes("/purchase-orders") && (init as RequestInit)?.method === "POST");
      expect(call).toBeDefined();
      return call!;
    });
    const [, init] = post;
    expect((init as RequestInit).headers).toMatchObject({ "Idempotency-Key": expect.any(String) });
    expect(JSON.parse(String((init as RequestInit).body))).toMatchObject({
      supplierId: 1,
      branchId: 1,
      items: [{ productId: 10, quantityOrdered: 20, unitPrice: 15.5 }],
    });
    expect(await screen.findByText("OC-ABC12345")).toBeInTheDocument();
  });
});

describe("Detalle de orden de compra", () => {
  it("muestra las líneas y permite cancelar con confirmación", async () => {
    seedSession("OPERATOR");
    const fetchSpy = mockFetch(purchaseRoutes());
    renderApp("/compras/500");

    await userEvent.click(await screen.findByRole("button", { name: /cancelar orden/i }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/ya no podrá recibirse mercancía/i)).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: /^cancelar orden$/i }));

    await waitFor(() => expect(fetchSpy.mock.calls.some(([url]) => String(url).includes("/500/cancel"))).toBe(true));
  });

  it("exige cantidad y precio antes de continuar con la recepción", async () => {
    seedSession("OPERATOR");
    mockFetch(purchaseRoutes());
    renderApp("/compras/500");

    await userEvent.click(await screen.findByRole("button", { name: /continuar/i }));
    expect(await screen.findByText(/ingresa la cantidad recibida/i)).toBeInTheDocument();
  });

  it("confirma el resumen y registra la recepción con Idempotency-Key", async () => {
    seedSession("OPERATOR");
    const fetchSpy = mockFetch(
      purchaseRoutes((url, init) => {
        if (url.includes("/receipts") && init?.method === "POST") {
          return jsonResponse(200, {
            purchaseOrderId: "500",
            status: "PARTIALLY_RECEIVED",
            items: [],
            inventoryUpdates: [],
          });
        }
        return undefined;
      }),
    );
    renderApp("/compras/500");

    await userEvent.type(await screen.findByLabelText(/cantidad a recibir/i), "8");
    await userEvent.type(screen.getByLabelText(/precio de recepción/i), "16");
    await userEvent.click(screen.getByRole("button", { name: /continuar/i }));

    const confirmation = await screen.findByRole("dialog", { name: /confirmar recepción/i });
    expect(confirmation).toHaveTextContent(/8 UND a \$\s*16/);
    await userEvent.click(within(confirmation).getByRole("button", { name: /registrar recepción/i }));

    const post = await waitFor(() => {
      const call = fetchSpy.mock.calls.find(([url, init]) => String(url).includes("/receipts") && (init as RequestInit)?.method === "POST");
      expect(call).toBeDefined();
      return call!;
    });
    const [, init] = post;
    expect((init as RequestInit).headers).toMatchObject({ "Idempotency-Key": expect.any(String) });
    expect(JSON.parse(String((init as RequestInit).body))).toMatchObject({
      items: [{ purchaseOrderItemId: 5000, quantityReceived: 8, unitPrice: 16 }],
    });
  });

  it("muestra el error de negocio cuando la cantidad excede lo pendiente y limpia al reintentar", async () => {
    seedSession("OPERATOR");
    mockFetch(
      purchaseRoutes((url, init) =>
        url.includes("/receipts") && init?.method === "POST"
          ? apiErrorResponse(422, "CANTIDAD_RECEPCION_EXCEDE_ORDENADO", "La cantidad a recibir (99) excede lo pendiente (20).")
          : undefined,
      ),
    );
    renderApp("/compras/500");

    await userEvent.type(await screen.findByLabelText(/cantidad a recibir/i), "99");
    await userEvent.type(screen.getByLabelText(/precio de recepción/i), "16");
    await userEvent.click(screen.getByRole("button", { name: /continuar/i }));
    const confirmDialog = await screen.findByRole("dialog", { name: /confirmar recepción/i });
    await userEvent.click(within(confirmDialog).getByRole("button", { name: /registrar recepción/i }));

    expect(await within(confirmDialog).findByText(/excede lo pendiente/i)).toBeInTheDocument();

    await userEvent.click(within(confirmDialog).getByRole("button", { name: /volver/i }));
    expect(screen.queryByText(/excede lo pendiente/i)).not.toBeInTheDocument();
  });
});
