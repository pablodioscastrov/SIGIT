import { describe, expect, it } from "vitest";
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { apiErrorResponse, jsonResponse, mockFetch, renderApp, seedSession } from "./harness";
import { BRANCHES, page } from "./catalog";

function branchRoutes(overrides: (url: string, init?: RequestInit) => Response | undefined = () => undefined) {
  return (url: string, init?: RequestInit) => {
    const custom = overrides(url, init);
    if (custom) return custom;
    if (url.includes("/branches")) return jsonResponse(200, page(BRANCHES));
    return jsonResponse(200, page([]));
  };
}

describe("Alta de sucursal", () => {
  it("solo ADMIN ve el botón de nueva sucursal", async () => {
    seedSession("MANAGER");
    mockFetch(branchRoutes());
    renderApp("/sucursales");

    expect(await screen.findByText("Sucursal Centro")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /nueva sucursal/i })).not.toBeInTheDocument();
  });

  it("no envía nada y señala los campos obligatorios cuando el formulario es inválido", async () => {
    seedSession("ADMIN");
    const fetchSpy = mockFetch(branchRoutes());
    renderApp("/sucursales");

    await userEvent.click(await screen.findByRole("button", { name: /nueva sucursal/i }));
    await userEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: /guardar/i }));

    expect(await screen.findByText(/el código es obligatorio/i)).toBeInTheDocument();
    expect(screen.getByText(/el nombre es obligatorio/i)).toBeInTheDocument();
    expect(fetchSpy.mock.calls.some(([, init]) => (init as RequestInit | undefined)?.method === "POST")).toBe(false);
  });

  it("envía el alta válida y vuelve a consultar el listado", async () => {
    seedSession("ADMIN");
    let created = false;
    const fetchSpy = mockFetch(
      branchRoutes((url, init) => {
        if (url.includes("/branches") && init?.method === "POST") {
          created = true;
          return jsonResponse(201, { id: "3", code: "SUC-003", name: "Sucursal Sur", location: "Sur", active: true });
        }
        if (url.includes("/branches?") && created) {
          return jsonResponse(200, page([...BRANCHES, { id: "3", code: "SUC-003", name: "Sucursal Sur", location: "Sur", active: true }]));
        }
        return undefined;
      }),
    );
    renderApp("/sucursales");

    await userEvent.click(await screen.findByRole("button", { name: /nueva sucursal/i }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText(/código/i), "SUC-003");
    await userEvent.type(within(dialog).getByLabelText(/^nombre$/i), "Sucursal Sur");
    await userEvent.type(within(dialog).getByLabelText(/ubicación/i), "Sur");
    await userEvent.click(within(dialog).getByRole("button", { name: /guardar/i }));

    const post = fetchSpy.mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === "POST");
    expect(post).toBeDefined();
    expect(JSON.parse(String((post![1] as RequestInit).body))).toMatchObject({
      code: "SUC-003",
      name: "Sucursal Sur",
      location: "Sur",
    });
    // Revalidación tras la mutación: la nueva sucursal aparece sin recargar.
    expect(await screen.findByText("Sucursal Sur")).toBeInTheDocument();
  });

  it("muestra el error del backend sin ocultarlo cuando el código ya existe", async () => {
    seedSession("ADMIN");
    mockFetch(
      branchRoutes((url, init) =>
        url.includes("/branches") && init?.method === "POST"
          ? apiErrorResponse(409, "CODIGO_YA_EXISTE", "Ya existe una sucursal con ese código.")
          : undefined,
      ),
    );
    renderApp("/sucursales");

    await userEvent.click(await screen.findByRole("button", { name: /nueva sucursal/i }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText(/código/i), "SUC-001");
    await userEvent.type(within(dialog).getByLabelText(/^nombre$/i), "Duplicada");
    await userEvent.click(within(dialog).getByRole("button", { name: /guardar/i }));

    expect(await screen.findByText(/ya existe una sucursal con ese código/i)).toBeInTheDocument();
  });
});

describe("Gestión de sucursal (editar, activar/desactivar, eliminar)", () => {
  it("un rol sin permiso de escritura no ve la columna de acciones", async () => {
    seedSession("MANAGER");
    mockFetch(branchRoutes());
    renderApp("/sucursales");

    const table = await screen.findByRole("table");
    expect(await screen.findByText("Sucursal Centro")).toBeInTheDocument();
    expect(within(table).queryByRole("button", { name: /editar/i })).not.toBeInTheDocument();
    expect(within(table).queryByRole("button", { name: /eliminar/i })).not.toBeInTheDocument();
  });

  it("edita una sucursal manteniendo el código fijo", async () => {
    seedSession("ADMIN");
    let edited = false;
    const fetchSpy = mockFetch(
      branchRoutes((url, init) => {
        if (/\/branches\/1$/.test(url) && init?.method === "PATCH") {
          edited = true;
          return jsonResponse(200, { id: "1", code: "SUC-001", name: "Sucursal Centro Renombrada", location: "Nueva dir", active: true });
        }
        if (/\/branches\?/.test(url) && edited) {
          return jsonResponse(200, page([{ ...BRANCHES[0], name: "Sucursal Centro Renombrada", location: "Nueva dir" }, BRANCHES[1]]));
        }
        return undefined;
      }),
    );
    renderApp("/sucursales");

    const row = (await screen.findByText("Sucursal Centro")).closest("tr")!;
    await userEvent.click(within(row).getByRole("button", { name: /editar/i }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/código suc-001/i)).toBeInTheDocument();

    const nameField = within(dialog).getByLabelText(/^nombre$/i);
    await userEvent.clear(nameField);
    await userEvent.type(nameField, "Sucursal Centro Renombrada");
    await userEvent.click(within(dialog).getByRole("button", { name: /guardar/i }));

    const patch = fetchSpy.mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === "PATCH");
    expect(patch).toBeDefined();
    expect(JSON.parse(String((patch![1] as RequestInit).body))).toMatchObject({ name: "Sucursal Centro Renombrada" });
    expect(await screen.findByText("Sucursal Centro Renombrada")).toBeInTheDocument();
  });

  it("desactiva una sucursal tras confirmar en el diálogo", async () => {
    seedSession("ADMIN");
    let active = true;
    const fetchSpy = mockFetch(
      branchRoutes((url, init) => {
        if (/\/branches\/1\/deactivate$/.test(url) && init?.method === "POST") {
          active = false;
          return jsonResponse(200, { ...BRANCHES[0], active: false });
        }
        if (/\/branches\?/.test(url)) return jsonResponse(200, page(active ? BRANCHES : [BRANCHES[1]]));
        return undefined;
      }),
    );
    renderApp("/sucursales");

    const row = (await screen.findByText("Sucursal Centro")).closest("tr")!;
    await userEvent.click(within(row).getByRole("button", { name: /desactivar/i }));
    const dialog = await screen.findByRole("dialog");
    expect(dialog).toHaveTextContent(/dejará de estar disponible/i);
    await userEvent.click(within(dialog).getByRole("button", { name: /desactivar/i }));

    const post = fetchSpy.mock.calls.find(([url, init]) => /\/deactivate$/.test(String(url)) && (init as RequestInit | undefined)?.method === "POST");
    expect(post).toBeDefined();
    // Revalidación tras la mutación: con el filtro "Activas" por defecto, la
    // sucursal recién desactivada deja de listarse sin recargar la página.
    await screen.findByText("Sucursal Norte");
    expect(screen.queryByText("Sucursal Centro")).not.toBeInTheDocument();
  });

  it("elimina una sucursal y la retira del listado", async () => {
    seedSession("ADMIN");
    let deleted = false;
    const fetchSpy = mockFetch(
      branchRoutes((url, init) => {
        if (/\/branches\/1$/.test(url) && init?.method === "DELETE") {
          deleted = true;
          return new Response(null, { status: 204 });
        }
        if (/\/branches\?/.test(url)) return jsonResponse(200, page(deleted ? [BRANCHES[1]] : BRANCHES));
        return undefined;
      }),
    );
    renderApp("/sucursales");

    const row = (await screen.findByText("Sucursal Centro")).closest("tr")!;
    await userEvent.click(within(row).getByRole("button", { name: /eliminar/i }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.click(within(dialog).getByRole("button", { name: /eliminar/i }));

    const del = fetchSpy.mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === "DELETE");
    expect(del).toBeDefined();
    await screen.findByText("Sucursal Norte");
    expect(screen.queryByText("Sucursal Centro")).not.toBeInTheDocument();
  });

  it("muestra el conflicto del backend sin ocultarlo cuando la sucursal tiene datos asociados", async () => {
    seedSession("ADMIN");
    mockFetch(
      branchRoutes((url, init) =>
        /\/branches\/1$/.test(url) && init?.method === "DELETE"
          ? apiErrorResponse(409, "SUCURSAL_CON_DATOS_ASOCIADOS", "No se puede eliminar la sucursal: tiene datos asociados.")
          : undefined,
      ),
    );
    renderApp("/sucursales");

    const row = (await screen.findByText("Sucursal Centro")).closest("tr")!;
    await userEvent.click(within(row).getByRole("button", { name: /eliminar/i }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.click(within(dialog).getByRole("button", { name: /eliminar/i }));

    expect(await screen.findByText(/no se puede eliminar la sucursal: tiene datos asociados/i)).toBeInTheDocument();
  });
});
