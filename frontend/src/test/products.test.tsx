import { describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { apiErrorResponse, jsonResponse, mockFetch, renderApp, seedSession } from "./harness";
import { PRODUCTS, UNITS, catalogResponse, page } from "./catalog";

function productRoutes(overrides: (url: string, init?: RequestInit) => Response | undefined = () => undefined) {
  return (url: string, init?: RequestInit) => {
    const custom = overrides(url, init);
    if (custom) return custom;
    const catalog = catalogResponse(url);
    if (catalog) return catalog;
    if (url.includes("/products")) return jsonResponse(200, page(PRODUCTS));
    return jsonResponse(200, page([]));
  };
}

describe("Pantalla de productos", () => {
  it("muestra el listado con SKU, unidad base y estado", async () => {
    seedSession("OPERATOR");
    mockFetch(productRoutes());
    renderApp("/productos");

    const row = (await screen.findByText("SKU-001")).closest("tr")!;
    expect(within(row).getByText("Cemento gris")).toBeInTheDocument();
    expect(within(row).getByText("UND")).toBeInTheDocument();
    expect(within(row).getByText("Activo")).toBeInTheDocument();
    expect(within(screen.getByText("SKU-002").closest("tr")!).getByText("Inactivo")).toBeInTheDocument();
  });

  it("muestra el stock mínimo y el precio de venta de cada producto", async () => {
    seedSession("OPERATOR");
    mockFetch(productRoutes());
    renderApp("/productos");

    const row = (await screen.findByText("SKU-001")).closest("tr")!;
    expect(within(row).getByText("10")).toBeInTheDocument();
    expect(within(row).getByText(/\$\s*50/)).toBeInTheDocument();

    // SKU-002 no tiene precio configurado: se avisa en vez de mostrar vacío.
    const rowWithoutPrice = screen.getByText("SKU-002").closest("tr")!;
    expect(within(rowWithoutPrice).getByText(/sin precio/i)).toBeInTheDocument();
  });

  it("muestra el estado vacío cuando no hay coincidencias", async () => {
    seedSession("OPERATOR");
    mockFetch(productRoutes((url) => (url.includes("/products?") ? jsonResponse(200, page([])) : undefined)));
    renderApp("/productos");

    expect(await screen.findByText(/no hay productos que coincidan/i)).toBeInTheDocument();
  });

  it("muestra el error del backend cuando falla la consulta", async () => {
    seedSession("OPERATOR");
    mockFetch(
      productRoutes((url) =>
        url.includes("/products?") ? apiErrorResponse(500, "ERROR_INTERNO", "Fallo interno.") : undefined,
      ),
    );
    renderApp("/productos");

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/error inesperado/i);
    expect(alert).toHaveTextContent(/req-de-prueba/);
  });

  describe("permisos visuales", () => {
    it("ofrece crear, editar y desactivar a un rol con permiso de escritura", async () => {
      seedSession("OPERATOR");
      mockFetch(productRoutes());
      renderApp("/productos");

      const table = await screen.findByRole("table");
      expect(screen.getByRole("button", { name: /nuevo producto/i })).toBeInTheDocument();
      expect(within(table).getAllByRole("button", { name: /^editar$/i })).not.toHaveLength(0);
      expect(within(table).getByRole("button", { name: /desactivar/i })).toBeInTheDocument();
    });

    it("no ofrece acciones de escritura a un rol que solo consulta", async () => {
      seedSession("MANAGER");
      mockFetch(productRoutes());
      renderApp("/productos");

      expect(await screen.findByText("SKU-001")).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: /nuevo producto/i })).not.toBeInTheDocument();
      expect(screen.queryByRole("button", { name: /^editar$/i })).not.toBeInTheDocument();
      // La consulta de unidades sigue disponible: leer no está restringido.
      expect(screen.getAllByRole("button", { name: /unidades/i })).not.toHaveLength(0);
    });

    it("solo un ADMIN ve el formulario de alta de unidades de medida", async () => {
      seedSession("OPERATOR");
      mockFetch(productRoutes());
      const { unmount } = renderApp("/productos/unidades");
      expect(await screen.findByText(/solo un administrador puede dar de alta/i)).toBeInTheDocument();
      unmount();

      sessionStorage.clear();
      seedSession("ADMIN");
      renderApp("/productos/unidades");
      expect(await screen.findByRole("button", { name: /crear unidad/i })).toBeInTheDocument();
    });
  });

  describe("formulario de alta", () => {
    it("no envía nada y señala los campos obligatorios cuando el formulario es inválido", async () => {
      seedSession("OPERATOR");
      const fetchSpy = mockFetch(productRoutes());
      renderApp("/productos");

      await userEvent.click(await screen.findByRole("button", { name: /nuevo producto/i }));
      await userEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: /guardar/i }));

      expect(await screen.findByText(/el sku es obligatorio/i)).toBeInTheDocument();
      expect(screen.getByText(/el nombre es obligatorio/i)).toBeInTheDocument();
      expect(screen.getByText(/selecciona la unidad base/i)).toBeInTheDocument();
      expect(screen.getByText(/indica el stock mínimo/i)).toBeInTheDocument();
      expect(screen.getByText(/indica el precio de venta/i)).toBeInTheDocument();
      expect(fetchSpy.mock.calls.some(([, init]) => (init as RequestInit | undefined)?.method === "POST")).toBe(false);
    });

    it("envía el alta válida y vuelve a consultar el listado", async () => {
      seedSession("OPERATOR");
      let created = false;
      const fetchSpy = mockFetch(
        productRoutes((url, init) => {
          if (url.includes("/products") && init?.method === "POST") {
            created = true;
            return jsonResponse(201, PRODUCTS[0]);
          }
          if (url.includes("/products?") && created) {
            return jsonResponse(200, page([...PRODUCTS, { ...PRODUCTS[0], id: "12", sku: "SKU-003", name: "Grava" }]));
          }
          return undefined;
        }),
      );
      renderApp("/productos");

      await userEvent.click(await screen.findByRole("button", { name: /nuevo producto/i }));
      const dialog = await screen.findByRole("dialog");
      await userEvent.type(within(dialog).getByLabelText(/^sku$/i), "SKU-003");
      await userEvent.type(within(dialog).getByLabelText(/^nombre$/i), "Grava");
      await userEvent.selectOptions(within(dialog).getByLabelText(/unidad base/i), UNITS[0].id);
      await userEvent.type(within(dialog).getByLabelText(/stock mínimo/i), "5");
      await userEvent.type(within(dialog).getByLabelText(/precio de venta/i), "25");
      await userEvent.click(within(dialog).getByRole("button", { name: /guardar/i }));

      const post = fetchSpy.mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === "POST");
      expect(post).toBeDefined();
      expect(JSON.parse(String((post![1] as RequestInit).body))).toMatchObject({
        sku: "SKU-003",
        name: "Grava",
        baseUnitOfMeasureId: 1,
        minimumStock: 5,
        unitPrice: 25,
      });
      // Revalidación tras la mutación: el nuevo producto aparece sin recargar.
      expect(await screen.findByText("SKU-003")).toBeInTheDocument();
    });

    it("muestra el error del backend sin ocultarlo cuando el SKU ya existe", async () => {
      seedSession("OPERATOR");
      mockFetch(
        productRoutes((url, init) =>
          url.includes("/products") && init?.method === "POST"
            ? apiErrorResponse(409, "SKU_YA_EXISTE", "Ya existe un producto con el SKU SKU-001.")
            : undefined,
        ),
      );
      renderApp("/productos");

      await userEvent.click(await screen.findByRole("button", { name: /nuevo producto/i }));
      const dialog = await screen.findByRole("dialog");
      await userEvent.type(within(dialog).getByLabelText(/^sku$/i), "SKU-001");
      await userEvent.type(within(dialog).getByLabelText(/^nombre$/i), "Cemento");
      await userEvent.selectOptions(within(dialog).getByLabelText(/unidad base/i), UNITS[0].id);
      await userEvent.type(within(dialog).getByLabelText(/stock mínimo/i), "5");
      await userEvent.type(within(dialog).getByLabelText(/precio de venta/i), "25");
      await userEvent.click(within(dialog).getByRole("button", { name: /guardar/i }));

      expect(await screen.findByText(/ya existe un producto con el sku sku-001/i)).toBeInTheDocument();
    });

    it("expone los errores por campo que devuelve el backend", async () => {
      seedSession("OPERATOR");
      mockFetch(
        productRoutes((url, init) =>
          url.includes("/products") && init?.method === "POST"
            ? jsonResponse(400, {
                error: {
                  code: "VALIDATION_ERROR",
                  message: "La solicitud contiene errores de validación.",
                  status: 400,
                  requestId: "req-de-prueba",
                  details: [{ field: "name", issue: "no debe exceder 150 caracteres" }],
                },
              })
            : undefined,
        ),
      );
      renderApp("/productos");

      await userEvent.click(await screen.findByRole("button", { name: /nuevo producto/i }));
      const dialog = await screen.findByRole("dialog");
      await userEvent.type(within(dialog).getByLabelText(/^sku$/i), "SKU-009");
      await userEvent.type(within(dialog).getByLabelText(/^nombre$/i), "Nombre larguísimo");
      await userEvent.selectOptions(within(dialog).getByLabelText(/unidad base/i), UNITS[0].id);
      await userEvent.type(within(dialog).getByLabelText(/stock mínimo/i), "5");
      await userEvent.type(within(dialog).getByLabelText(/precio de venta/i), "25");
      await userEvent.click(within(dialog).getByRole("button", { name: /guardar/i }));

      expect(await screen.findByText(/no debe exceder 150 caracteres/i)).toBeInTheDocument();
    });
  });

  describe("Edición de producto (BR-057)", () => {
    it("permite editar el precio de venta además del nombre y la descripción", async () => {
      seedSession("OPERATOR");
      const fetchSpy = mockFetch(productRoutes());
      renderApp("/productos");

      const row = (await screen.findByText("SKU-001")).closest("tr")!;
      await userEvent.click(within(row).getByRole("button", { name: /^editar$/i }));
      const dialog = await screen.findByRole("dialog");

      const priceField = within(dialog).getByLabelText(/precio de venta/i);
      expect(priceField).toHaveValue("50");
      await userEvent.clear(priceField);
      await userEvent.type(priceField, "75");
      await userEvent.click(within(dialog).getByRole("button", { name: /guardar/i }));

      const patch = fetchSpy.mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === "PATCH");
      expect(patch).toBeDefined();
      expect(JSON.parse(String((patch![1] as RequestInit).body))).toMatchObject({ unitPrice: 75 });
    });

    it("exige un precio de venta mayor que cero también al editar", async () => {
      seedSession("OPERATOR");
      const fetchSpy = mockFetch(productRoutes());
      renderApp("/productos");

      const row = (await screen.findByText("SKU-001")).closest("tr")!;
      await userEvent.click(within(row).getByRole("button", { name: /^editar$/i }));
      const dialog = await screen.findByRole("dialog");

      await userEvent.clear(within(dialog).getByLabelText(/precio de venta/i));
      await userEvent.click(within(dialog).getByRole("button", { name: /guardar/i }));

      expect(await screen.findByText(/indica el precio de venta/i)).toBeInTheDocument();
      expect(fetchSpy.mock.calls.some(([, init]) => (init as RequestInit | undefined)?.method === "PATCH")).toBe(false);
    });
  });

  describe("Edición de stock mínimo (BR-059)", () => {
    it("permite editar el stock mínimo, pre-cargado con el valor actual", async () => {
      seedSession("OPERATOR");
      const fetchSpy = mockFetch(productRoutes());
      renderApp("/productos");

      const row = (await screen.findByText("SKU-001")).closest("tr")!;
      await userEvent.click(within(row).getByRole("button", { name: /^editar$/i }));
      const dialog = await screen.findByRole("dialog");

      const minStockField = within(dialog).getByLabelText(/stock mínimo/i);
      expect(minStockField).toHaveValue("10");
      await userEvent.clear(minStockField);
      await userEvent.type(minStockField, "20");
      await userEvent.click(within(dialog).getByRole("button", { name: /guardar/i }));

      const patch = fetchSpy.mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === "PATCH");
      expect(patch).toBeDefined();
      expect(JSON.parse(String((patch![1] as RequestInit).body))).toMatchObject({ minimumStock: 20 });
    });

    it("no permite un stock mínimo negativo al editar", async () => {
      seedSession("OPERATOR");
      const fetchSpy = mockFetch(productRoutes());
      renderApp("/productos");

      const row = (await screen.findByText("SKU-001")).closest("tr")!;
      await userEvent.click(within(row).getByRole("button", { name: /^editar$/i }));
      const dialog = await screen.findByRole("dialog");

      const minStockField = within(dialog).getByLabelText(/stock mínimo/i);
      await userEvent.clear(minStockField);
      await userEvent.type(minStockField, "-5");
      await userEvent.click(within(dialog).getByRole("button", { name: /guardar/i }));

      expect(await screen.findByText(/indica el stock mínimo/i)).toBeInTheDocument();
      expect(fetchSpy.mock.calls.some(([, init]) => (init as RequestInit | undefined)?.method === "PATCH")).toBe(false);
    });
  });

  describe("Edición de unidades de medida (BR-050)", () => {
    it("ADMIN puede editar el nombre de una unidad, el código permanece fijo", async () => {
      seedSession("ADMIN");
      let edited = false;
      const fetchSpy = mockFetch(
        productRoutes((url, init) => {
          if (/\/units-of-measure\/1$/.test(url) && init?.method === "PATCH") {
            edited = true;
            return jsonResponse(200, { id: "1", code: "UND", name: "Unidad renombrada" });
          }
          if (url.includes("/units-of-measure") && edited) {
            return jsonResponse(200, [{ id: "1", code: "UND", name: "Unidad renombrada" }, UNITS[1]]);
          }
          return undefined;
        }),
      );
      renderApp("/productos/unidades");

      const row = (await screen.findByText("UND")).closest("tr")!;
      await userEvent.click(within(row).getByRole("button", { name: /editar/i }));
      const dialog = await screen.findByRole("dialog");
      expect(within(dialog).getByText(/código und/i)).toBeInTheDocument();

      const nameField = within(dialog).getByLabelText(/^nombre$/i);
      await userEvent.clear(nameField);
      await userEvent.type(nameField, "Unidad renombrada");
      await userEvent.click(within(dialog).getByRole("button", { name: /guardar/i }));

      const patch = fetchSpy.mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === "PATCH");
      expect(patch).toBeDefined();
      expect(JSON.parse(String((patch![1] as RequestInit).body))).toMatchObject({ name: "Unidad renombrada" });
      expect(await screen.findByText("Unidad renombrada")).toBeInTheDocument();
    });

    it("un rol sin permiso no ve el botón editar en unidades de medida", async () => {
      seedSession("OPERATOR");
      mockFetch(productRoutes());
      renderApp("/productos/unidades");

      expect(await screen.findByText("UND")).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: /editar/i })).not.toBeInTheDocument();
    });
  });

  it("pide confirmación antes de desactivar y explica la consecuencia", async () => {
    seedSession("ADMIN");
    const fetchSpy = mockFetch(
      productRoutes((url, init) =>
        url.includes("/deactivate") && init?.method === "POST"
          ? jsonResponse(200, { ...PRODUCTS[0], active: false })
          : undefined,
      ),
    );
    renderApp("/productos");

    await userEvent.click(await screen.findByRole("button", { name: /desactivar/i }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/no se elimina/i)).toBeInTheDocument();
    // Antes de confirmar no se ha llamado a la API.
    expect(fetchSpy.mock.calls.some(([url]) => String(url).includes("/deactivate"))).toBe(false);

    await userEvent.click(within(dialog).getByRole("button", { name: /^desactivar$/i }));
    await waitFor(() =>
      expect(fetchSpy.mock.calls.some(([url]) => String(url).includes("/10/deactivate"))).toBe(true),
    );
  });

  it("muestra las unidades del producto con su factor de conversión", async () => {
    seedSession("OPERATOR");
    mockFetch(productRoutes());
    renderApp("/productos");

    await userEvent.click((await screen.findAllByRole("button", { name: /^unidades$/i }))[0]);
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/CJA — Caja/)).toBeInTheDocument();
    expect(within(dialog).getByText("12")).toBeInTheDocument();
  });
});
