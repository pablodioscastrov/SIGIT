import { describe, expect, it } from "vitest";
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { apiErrorResponse, jsonResponse, mockFetch, renderApp, seedSession } from "./harness";
import { SUPPLIERS, page } from "./catalog";

function supplierRoutes(overrides: (url: string, init?: RequestInit) => Response | undefined = () => undefined) {
  return (url: string, init?: RequestInit) => {
    const custom = overrides(url, init);
    if (custom) return custom;
    if (url.includes("/suppliers")) return jsonResponse(200, page(SUPPLIERS));
    return jsonResponse(200, page([]));
  };
}

describe("Alta de proveedor", () => {
  it("MANAGER y ADMIN ven el botón de nuevo proveedor; OPERATOR solo lectura (BR-058)", async () => {
    seedSession("MANAGER");
    mockFetch(supplierRoutes());
    const { unmount } = renderApp("/proveedores");

    expect(await screen.findByText("Distribuidora Andina")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /nuevo proveedor/i })).toBeInTheDocument();
    unmount();

    sessionStorage.clear();
    seedSession("OPERATOR");
    renderApp("/proveedores");

    expect(await screen.findByText("Distribuidora Andina")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /nuevo proveedor/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^editar$/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("columnheader", { name: /acciones/i })).not.toBeInTheDocument();
  });

  it("no envía nada y señala los campos obligatorios cuando el formulario es inválido", async () => {
    seedSession("MANAGER");
    const fetchSpy = mockFetch(supplierRoutes());
    renderApp("/proveedores");

    await userEvent.click(await screen.findByRole("button", { name: /nuevo proveedor/i }));
    await userEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: /guardar/i }));

    expect(await screen.findByText(/la identificación fiscal es obligatoria/i)).toBeInTheDocument();
    expect(screen.getByText(/el nombre es obligatorio/i)).toBeInTheDocument();
    expect(fetchSpy.mock.calls.some(([, init]) => (init as RequestInit | undefined)?.method === "POST")).toBe(false);
  });

  it("envía el alta válida y vuelve a consultar el listado", async () => {
    seedSession("MANAGER");
    let created = false;
    const fetchSpy = mockFetch(
      supplierRoutes((url, init) => {
        if (url.includes("/suppliers") && init?.method === "POST") {
          created = true;
          return jsonResponse(201, { id: "3", name: "Proveedor Sur", taxId: "TAX-003", contactName: null, phone: null, email: null, active: true });
        }
        if (url.includes("/suppliers?") && created) {
          return jsonResponse(200, page([...SUPPLIERS, { id: "3", name: "Proveedor Sur", taxId: "TAX-003", contactName: null, phone: null, email: null, active: true }]));
        }
        return undefined;
      }),
    );
    renderApp("/proveedores");

    await userEvent.click(await screen.findByRole("button", { name: /nuevo proveedor/i }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText(/identificación fiscal/i), "TAX-003");
    await userEvent.type(within(dialog).getByLabelText(/razón social/i), "Proveedor Sur");
    await userEvent.click(within(dialog).getByRole("button", { name: /guardar/i }));

    const post = fetchSpy.mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === "POST");
    expect(post).toBeDefined();
    expect(JSON.parse(String((post![1] as RequestInit).body))).toMatchObject({
      taxId: "TAX-003",
      name: "Proveedor Sur",
    });
    // Revalidación tras la mutación: el nuevo proveedor aparece sin recargar.
    expect(await screen.findByText("Proveedor Sur")).toBeInTheDocument();
  });

  it("muestra el error del backend sin ocultarlo cuando la identificación fiscal ya existe", async () => {
    seedSession("ADMIN");
    mockFetch(
      supplierRoutes((url, init) =>
        url.includes("/suppliers") && init?.method === "POST"
          ? apiErrorResponse(409, "IDENTIFICACION_FISCAL_YA_EXISTE", "Ya existe un proveedor con esa identificación fiscal.")
          : undefined,
      ),
    );
    renderApp("/proveedores");

    await userEvent.click(await screen.findByRole("button", { name: /nuevo proveedor/i }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText(/identificación fiscal/i), "TAX-001");
    await userEvent.type(within(dialog).getByLabelText(/razón social/i), "Duplicado");
    await userEvent.click(within(dialog).getByRole("button", { name: /guardar/i }));

    expect(await screen.findByText(/ya existe un proveedor con esa identificación fiscal/i)).toBeInTheDocument();
  });
});

describe("Gestión de proveedor (editar, activar/desactivar, eliminar)", () => {
  it("edita un proveedor manteniendo la identificación fiscal fija", async () => {
    seedSession("MANAGER");
    let edited = false;
    const fetchSpy = mockFetch(
      supplierRoutes((url, init) => {
        if (/\/suppliers\/1$/.test(url) && init?.method === "PATCH") {
          edited = true;
          return jsonResponse(200, { ...SUPPLIERS[0], name: "Distribuidora Andina Renombrada", contactName: "Juan" });
        }
        if (/\/suppliers\?/.test(url) && edited) {
          return jsonResponse(200, page([{ ...SUPPLIERS[0], name: "Distribuidora Andina Renombrada", contactName: "Juan" }, SUPPLIERS[1]]));
        }
        return undefined;
      }),
    );
    renderApp("/proveedores");

    const row = (await screen.findByText("Distribuidora Andina")).closest("tr")!;
    await userEvent.click(within(row).getByRole("button", { name: /editar/i }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/identificación fiscal tax-001/i)).toBeInTheDocument();

    const nameField = within(dialog).getByLabelText(/razón social/i);
    await userEvent.clear(nameField);
    await userEvent.type(nameField, "Distribuidora Andina Renombrada");
    await userEvent.click(within(dialog).getByRole("button", { name: /guardar/i }));

    const patch = fetchSpy.mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === "PATCH");
    expect(patch).toBeDefined();
    expect(JSON.parse(String((patch![1] as RequestInit).body))).toMatchObject({ name: "Distribuidora Andina Renombrada" });
    expect(await screen.findByText("Distribuidora Andina Renombrada")).toBeInTheDocument();
  });

  it("desactiva un proveedor tras confirmar en el diálogo", async () => {
    seedSession("MANAGER");
    let active = true;
    const fetchSpy = mockFetch(
      supplierRoutes((url, init) => {
        if (/\/suppliers\/1\/deactivate$/.test(url) && init?.method === "POST") {
          active = false;
          return jsonResponse(200, { ...SUPPLIERS[0], active: false });
        }
        if (/\/suppliers\?/.test(url)) return jsonResponse(200, page(active ? SUPPLIERS : [SUPPLIERS[1]]));
        return undefined;
      }),
    );
    renderApp("/proveedores");

    const row = (await screen.findByText("Distribuidora Andina")).closest("tr")!;
    await userEvent.click(within(row).getByRole("button", { name: /desactivar/i }));
    const dialog = await screen.findByRole("dialog");
    expect(dialog).toHaveTextContent(/dejará de estar disponible/i);
    await userEvent.click(within(dialog).getByRole("button", { name: /desactivar/i }));

    const post = fetchSpy.mock.calls.find(([url, init]) => /\/deactivate$/.test(String(url)) && (init as RequestInit | undefined)?.method === "POST");
    expect(post).toBeDefined();
    // Revalidación tras la mutación: con el filtro "Activos" por defecto, el
    // proveedor recién desactivado deja de listarse sin recargar la página.
    await screen.findByText("Proveedor Norte");
    expect(screen.queryByText("Distribuidora Andina")).not.toBeInTheDocument();
  });

  it("elimina un proveedor y lo retira del listado", async () => {
    seedSession("ADMIN");
    let deleted = false;
    const fetchSpy = mockFetch(
      supplierRoutes((url, init) => {
        if (/\/suppliers\/1$/.test(url) && init?.method === "DELETE") {
          deleted = true;
          return new Response(null, { status: 204 });
        }
        if (/\/suppliers\?/.test(url)) return jsonResponse(200, page(deleted ? [SUPPLIERS[1]] : SUPPLIERS));
        return undefined;
      }),
    );
    renderApp("/proveedores");

    const row = (await screen.findByText("Distribuidora Andina")).closest("tr")!;
    await userEvent.click(within(row).getByRole("button", { name: /eliminar/i }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.click(within(dialog).getByRole("button", { name: /eliminar/i }));

    const del = fetchSpy.mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === "DELETE");
    expect(del).toBeDefined();
    await screen.findByText("Proveedor Norte");
    expect(screen.queryByText("Distribuidora Andina")).not.toBeInTheDocument();
  });

  it("MANAGER ve editar/desactivar pero no eliminar (BR-058: eliminar es exclusivo de ADMIN)", async () => {
    seedSession("MANAGER");
    mockFetch(supplierRoutes());
    renderApp("/proveedores");

    const row = (await screen.findByText("Distribuidora Andina")).closest("tr")!;
    expect(within(row).getByRole("button", { name: /editar/i })).toBeInTheDocument();
    expect(within(row).getByRole("button", { name: /desactivar/i })).toBeInTheDocument();
    expect(within(row).queryByRole("button", { name: /eliminar/i })).not.toBeInTheDocument();
  });

  it("muestra el conflicto del backend sin ocultarlo cuando el proveedor tiene órdenes de compra asociadas", async () => {
    seedSession("ADMIN");
    mockFetch(
      supplierRoutes((url, init) =>
        /\/suppliers\/1$/.test(url) && init?.method === "DELETE"
          ? apiErrorResponse(409, "PROVEEDOR_CON_DATOS_ASOCIADOS", "No se puede eliminar el proveedor: tiene órdenes de compra asociadas.")
          : undefined,
      ),
    );
    renderApp("/proveedores");

    const row = (await screen.findByText("Distribuidora Andina")).closest("tr")!;
    await userEvent.click(within(row).getByRole("button", { name: /eliminar/i }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.click(within(dialog).getByRole("button", { name: /eliminar/i }));

    expect(await screen.findByText(/no se puede eliminar el proveedor: tiene órdenes de compra asociadas/i)).toBeInTheDocument();
  });
});
