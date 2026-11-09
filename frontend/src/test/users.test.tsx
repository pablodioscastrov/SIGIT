import { describe, expect, it } from "vitest";
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { apiErrorResponse, jsonResponse, mockFetch, renderApp, seedSession } from "./harness";
import { BRANCHES, USERS, catalogResponse, page } from "./catalog";

function userRoutes(overrides: (url: string, init?: RequestInit) => Response | undefined = () => undefined) {
  return (url: string, init?: RequestInit) => {
    const custom = overrides(url, init);
    if (custom) return custom;
    const catalog = catalogResponse(url);
    if (catalog) return catalog;
    if (url.includes("/users")) return jsonResponse(200, page(USERS));
    return jsonResponse(200, page([]));
  };
}

describe("Pantalla de usuarios", () => {
  it("un rol distinto de ADMIN forzando la URL cae en Sin permiso", async () => {
    seedSession("OPERATOR");
    mockFetch(userRoutes());
    renderApp("/usuarios");

    expect(await screen.findByText(/sin permiso/i)).toBeInTheDocument();
  });

  it("ADMIN ve el listado con rol, sucursal y estado, y el botón de alta", async () => {
    seedSession("ADMIN");
    mockFetch(userRoutes());
    renderApp("/usuarios");

    expect(await screen.findByRole("button", { name: /nuevo usuario/i })).toBeInTheDocument();

    const adminRow = (await screen.findByText("admin@inventario.local")).closest("tr")!;
    expect(within(adminRow).getByText("ADMIN")).toBeInTheDocument();
    expect(within(adminRow).getByText("—")).toBeInTheDocument();

    const managerRow = screen.getByText("gerente.centro@inventario.local").closest("tr")!;
    expect(within(managerRow).getByText("Sucursal Centro")).toBeInTheDocument();
    expect(within(managerRow).getByText("Activo")).toBeInTheDocument();

    const operatorRow = screen.getByText("operador.centro@inventario.local").closest("tr")!;
    expect(within(operatorRow).getByText("Inactivo")).toBeInTheDocument();
  });

  it("muestra el estado vacío cuando no hay usuarios", async () => {
    seedSession("ADMIN");
    mockFetch(userRoutes((url) => (url.includes("/users?") ? jsonResponse(200, page([])) : undefined)));
    renderApp("/usuarios");

    expect(await screen.findByText(/no hay usuarios registrados/i)).toBeInTheDocument();
  });

  it("muestra el error del backend cuando falla la consulta", async () => {
    seedSession("ADMIN");
    mockFetch(userRoutes((url) => (url.includes("/users?") ? apiErrorResponse(500, "ERROR_INTERNO", "Fallo interno.") : undefined)));
    renderApp("/usuarios");

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/error inesperado/i);
    expect(alert).toHaveTextContent(/req-de-prueba/);
  });

  describe("formulario de alta", () => {
    it("no envía nada y señala los campos obligatorios cuando el formulario es inválido", async () => {
      seedSession("ADMIN");
      const fetchSpy = mockFetch(userRoutes());
      renderApp("/usuarios");

      await userEvent.click(await screen.findByRole("button", { name: /nuevo usuario/i }));
      await userEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: /guardar/i }));

      expect(await screen.findByText(/el nombre es obligatorio/i)).toBeInTheDocument();
      expect(screen.getByText(/el correo es obligatorio/i)).toBeInTheDocument();
      expect(screen.getByText(/la contraseña debe tener al menos 8 caracteres/i)).toBeInTheDocument();
      expect(screen.getByText(/selecciona un rol/i)).toBeInTheDocument();
      expect(fetchSpy.mock.calls.some(([, init]) => (init as RequestInit | undefined)?.method === "POST")).toBe(false);
    });

    it("señala que las contraseñas no coinciden y no envía nada", async () => {
      seedSession("ADMIN");
      const fetchSpy = mockFetch(userRoutes());
      renderApp("/usuarios");

      await userEvent.click(await screen.findByRole("button", { name: /nuevo usuario/i }));
      const dialog = await screen.findByRole("dialog");
      await userEvent.type(within(dialog).getByLabelText(/^contraseña$/i), "ClaveSegura1");
      await userEvent.type(within(dialog).getByLabelText(/confirmar contraseña/i), "OtraClave1");
      await userEvent.click(within(dialog).getByRole("button", { name: /guardar/i }));

      expect(await screen.findByText(/las contraseñas no coinciden/i)).toBeInTheDocument();
      expect(fetchSpy.mock.calls.some(([, init]) => (init as RequestInit | undefined)?.method === "POST")).toBe(false);
    });

    it("alterna entre ocultar y mostrar el texto de la contraseña", async () => {
      seedSession("ADMIN");
      mockFetch(userRoutes());
      renderApp("/usuarios");

      await userEvent.click(await screen.findByRole("button", { name: /nuevo usuario/i }));
      const dialog = await screen.findByRole("dialog");
      const passwordInput = within(dialog).getByLabelText(/^contraseña$/i);
      expect(passwordInput).toHaveAttribute("type", "password");
      const passwordWrapper = within(passwordInput.parentElement as HTMLElement);

      await userEvent.click(passwordWrapper.getByRole("button", { name: /^mostrar$/i }));
      expect(passwordInput).toHaveAttribute("type", "text");

      await userEvent.click(passwordWrapper.getByRole("button", { name: /^ocultar$/i }));
      expect(passwordInput).toHaveAttribute("type", "password");
    });

    it("pide sucursal cuando el rol elegido no es ADMIN", async () => {
      seedSession("ADMIN");
      mockFetch(userRoutes());
      renderApp("/usuarios");

      await userEvent.click(await screen.findByRole("button", { name: /nuevo usuario/i }));
      const dialog = await screen.findByRole("dialog");
      await userEvent.selectOptions(within(dialog).getByLabelText(/^rol$/i), "OPERATOR");
      await userEvent.click(within(dialog).getByRole("button", { name: /guardar/i }));

      expect(await screen.findByText(/selecciona la sucursal/i)).toBeInTheDocument();
    });

    it("un ADMIN nuevo no pide sucursal y muestra la aclaración de alcance global", async () => {
      seedSession("ADMIN");
      mockFetch(userRoutes());
      renderApp("/usuarios");

      await userEvent.click(await screen.findByRole("button", { name: /nuevo usuario/i }));
      const dialog = await screen.findByRole("dialog");
      await userEvent.selectOptions(within(dialog).getByLabelText(/^rol$/i), "ADMIN");

      expect(within(dialog).queryByLabelText(/^sucursal$/i)).not.toBeInTheDocument();
      expect(within(dialog).getByText(/alcance global, sin sucursal asignada/i)).toBeInTheDocument();
    });

    it("envía el alta de un OPERATOR con su sucursal y vuelve a consultar el listado", async () => {
      seedSession("ADMIN");
      let created = false;
      const fetchSpy = mockFetch(
        userRoutes((url, init) => {
          if (url.includes("/users") && init?.method === "POST") {
            created = true;
            return jsonResponse(201, { id: "9", name: "Nuevo Operador", email: "nuevo@inventario.local", role: "OPERATOR", branchId: "1", active: true });
          }
          if (url.includes("/users?") && created) {
            return jsonResponse(200, page([...USERS, { id: "9", name: "Nuevo Operador", email: "nuevo@inventario.local", role: "OPERATOR" as const, branchId: "1", active: true }]));
          }
          return undefined;
        }),
      );
      renderApp("/usuarios");

      await userEvent.click(await screen.findByRole("button", { name: /nuevo usuario/i }));
      const dialog = await screen.findByRole("dialog");
      await userEvent.type(within(dialog).getByLabelText(/^nombre$/i), "Nuevo Operador");
      await userEvent.type(within(dialog).getByLabelText(/correo electrónico/i), "nuevo@inventario.local");
      await userEvent.type(within(dialog).getByLabelText(/^contraseña$/i), "ClaveSegura1");
      await userEvent.type(within(dialog).getByLabelText(/confirmar contraseña/i), "ClaveSegura1");
      await userEvent.selectOptions(within(dialog).getByLabelText(/^rol$/i), "OPERATOR");
      await userEvent.selectOptions(await within(dialog).findByLabelText(/^sucursal$/i), BRANCHES[0].id);
      await userEvent.click(within(dialog).getByRole("button", { name: /guardar/i }));

      const post = fetchSpy.mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === "POST");
      expect(post).toBeDefined();
      expect(JSON.parse(String((post![1] as RequestInit).body))).toMatchObject({
        name: "Nuevo Operador",
        email: "nuevo@inventario.local",
        password: "ClaveSegura1",
        role: "OPERATOR",
        branchId: 1,
      });
      // Revalidación tras la mutación: el nuevo usuario aparece sin recargar.
      expect(await screen.findByText("nuevo@inventario.local")).toBeInTheDocument();
    });

    it("envía branchId null al crear un ADMIN", async () => {
      seedSession("ADMIN");
      const fetchSpy = mockFetch(
        userRoutes((url, init) =>
          url.includes("/users") && init?.method === "POST"
            ? jsonResponse(201, { id: "9", name: "Otro Admin", email: "otro.admin@inventario.local", role: "ADMIN", branchId: null, active: true })
            : undefined,
        ),
      );
      renderApp("/usuarios");

      await userEvent.click(await screen.findByRole("button", { name: /nuevo usuario/i }));
      const dialog = await screen.findByRole("dialog");
      await userEvent.type(within(dialog).getByLabelText(/^nombre$/i), "Otro Admin");
      await userEvent.type(within(dialog).getByLabelText(/correo electrónico/i), "otro.admin@inventario.local");
      await userEvent.type(within(dialog).getByLabelText(/^contraseña$/i), "ClaveSegura1");
      await userEvent.type(within(dialog).getByLabelText(/confirmar contraseña/i), "ClaveSegura1");
      await userEvent.selectOptions(within(dialog).getByLabelText(/^rol$/i), "ADMIN");
      await userEvent.click(within(dialog).getByRole("button", { name: /guardar/i }));

      const post = fetchSpy.mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === "POST");
      expect(post).toBeDefined();
      expect(JSON.parse(String((post![1] as RequestInit).body))).toMatchObject({ role: "ADMIN", branchId: null });
    });

    it("muestra el error del backend sin ocultarlo cuando el correo ya existe", async () => {
      seedSession("ADMIN");
      mockFetch(
        userRoutes((url, init) =>
          url.includes("/users") && init?.method === "POST"
            ? apiErrorResponse(409, "EMAIL_YA_EXISTE", "Ya existe un usuario con ese correo.")
            : undefined,
        ),
      );
      renderApp("/usuarios");

      await userEvent.click(await screen.findByRole("button", { name: /nuevo usuario/i }));
      const dialog = await screen.findByRole("dialog");
      await userEvent.type(within(dialog).getByLabelText(/^nombre$/i), "Duplicado");
      await userEvent.type(within(dialog).getByLabelText(/correo electrónico/i), "admin@inventario.local");
      await userEvent.type(within(dialog).getByLabelText(/^contraseña$/i), "ClaveSegura1");
      await userEvent.type(within(dialog).getByLabelText(/confirmar contraseña/i), "ClaveSegura1");
      await userEvent.selectOptions(within(dialog).getByLabelText(/^rol$/i), "ADMIN");
      await userEvent.click(within(dialog).getByRole("button", { name: /guardar/i }));

      expect(await screen.findByText(/ya existe un usuario con ese correo/i)).toBeInTheDocument();
    });
  });

  describe("editar usuario (BR-058)", () => {
    it("edita nombre, correo, rol y sucursal, y envía el PATCH al backend", async () => {
      seedSession("ADMIN");
      let edited = false;
      const fetchSpy = mockFetch(
        userRoutes((url, init) => {
          if (/\/users\/3$/.test(url) && init?.method === "PATCH") {
            edited = true;
            return jsonResponse(200, { ...USERS[2], name: "Operador Renombrado", email: "renombrado@inventario.local", branchId: "2" });
          }
          if (url.includes("/users?") && edited) {
            return jsonResponse(200, page([USERS[0], USERS[1], { ...USERS[2], name: "Operador Renombrado", email: "renombrado@inventario.local", branchId: "2" }]));
          }
          return undefined;
        }),
      );
      renderApp("/usuarios");

      const row = (await screen.findByText("operador.centro@inventario.local")).closest("tr")!;
      await userEvent.click(within(row).getByRole("button", { name: /^editar$/i }));
      const dialog = await screen.findByRole("dialog");

      expect(within(dialog).getByLabelText(/^nombre$/i)).toHaveValue("Operador Centro");
      expect(within(dialog).getByLabelText(/correo electrónico/i)).toHaveValue("operador.centro@inventario.local");
      expect(within(dialog).queryByLabelText(/contraseña/i)).not.toBeInTheDocument();

      await userEvent.clear(within(dialog).getByLabelText(/^nombre$/i));
      await userEvent.type(within(dialog).getByLabelText(/^nombre$/i), "Operador Renombrado");
      await userEvent.clear(within(dialog).getByLabelText(/correo electrónico/i));
      await userEvent.type(within(dialog).getByLabelText(/correo electrónico/i), "renombrado@inventario.local");
      await userEvent.click(within(dialog).getByRole("button", { name: /guardar/i }));

      const patch = fetchSpy.mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === "PATCH");
      expect(patch).toBeDefined();
      expect(JSON.parse(String((patch![1] as RequestInit).body))).toMatchObject({
        name: "Operador Renombrado",
        email: "renombrado@inventario.local",
        role: "OPERATOR",
      });
      expect(await screen.findByText("Operador Renombrado")).toBeInTheDocument();
    });

    it("muestra el conflicto del backend sin ocultarlo cuando el correo ya existe", async () => {
      seedSession("ADMIN");
      mockFetch(
        userRoutes((url, init) =>
          /\/users\/3$/.test(url) && init?.method === "PATCH"
            ? apiErrorResponse(409, "EMAIL_YA_EXISTE", "Ya existe un usuario con ese correo.")
            : undefined,
        ),
      );
      renderApp("/usuarios");

      const row = (await screen.findByText("operador.centro@inventario.local")).closest("tr")!;
      await userEvent.click(within(row).getByRole("button", { name: /^editar$/i }));
      const dialog = await screen.findByRole("dialog");
      await userEvent.click(within(dialog).getByRole("button", { name: /guardar/i }));

      expect(await screen.findByText(/ya existe un usuario con ese correo/i)).toBeInTheDocument();
    });

    it("también se puede editar la propia cuenta", async () => {
      seedSession("ADMIN");
      mockFetch(userRoutes());
      renderApp("/usuarios");

      const ownRow = (await screen.findByText("admin@inventario.local")).closest("tr")!;
      expect(within(ownRow).getByRole("button", { name: /^editar$/i })).toBeInTheDocument();
    });
  });

  describe("activar, desactivar y eliminar", () => {
    it("oculta las acciones de activar/desactivar/eliminar sobre la propia cuenta", async () => {
      seedSession("ADMIN"); // userOfRole da id "1", igual que USERS[0] (admin@inventario.local).
      mockFetch(userRoutes());
      renderApp("/usuarios");

      const ownRow = (await screen.findByText("admin@inventario.local")).closest("tr")!;
      expect(within(ownRow).getByText(/tu propia cuenta/i)).toBeInTheDocument();
      expect(within(ownRow).queryByRole("button", { name: /desactivar|activar|eliminar/i })).not.toBeInTheDocument();
    });

    it("muestra el motivo de desactivación de un usuario inactivo", async () => {
      seedSession("ADMIN");
      mockFetch(userRoutes());
      renderApp("/usuarios");

      const operatorRow = (await screen.findByText("operador.centro@inventario.local")).closest("tr")!;
      expect(within(operatorRow).getByText(/motivo: renuncia/i)).toBeInTheDocument();
    });

    it("pide el motivo antes de desactivar y lo envía al backend", async () => {
      seedSession("ADMIN");
      let deactivated = false;
      const fetchSpy = mockFetch(
        userRoutes((url, init) => {
          if (url.includes("/users/2/deactivate") && init?.method === "POST") {
            deactivated = true;
            return jsonResponse(200, { ...USERS[1], active: false, deactivationReason: "Cambio de área" });
          }
          if (url.includes("/users?") && deactivated) {
            return jsonResponse(200, page([USERS[0], { ...USERS[1], active: false, deactivationReason: "Cambio de área" }, USERS[2]]));
          }
          return undefined;
        }),
      );
      renderApp("/usuarios");

      const row = (await screen.findByText("gerente.centro@inventario.local")).closest("tr")!;
      await userEvent.click(within(row).getByRole("button", { name: /desactivar/i }));
      const dialog = await screen.findByRole("dialog");

      // Sin motivo, no debe enviar nada.
      await userEvent.click(within(dialog).getByRole("button", { name: /^desactivar$/i }));
      expect(await screen.findByText(/el motivo es obligatorio/i)).toBeInTheDocument();
      expect(fetchSpy.mock.calls.some(([, init]) => (init as RequestInit | undefined)?.method === "POST")).toBe(false);

      await userEvent.type(within(dialog).getByLabelText(/motivo/i), "Cambio de área");
      await userEvent.click(within(dialog).getByRole("button", { name: /^desactivar$/i }));

      const post = fetchSpy.mock.calls.find(([url, init]) => String(url).includes("/deactivate") && (init as RequestInit | undefined)?.method === "POST");
      expect(post).toBeDefined();
      expect(JSON.parse(String((post![1] as RequestInit).body))).toMatchObject({ reason: "Cambio de área" });
      expect(await screen.findByText(/motivo: cambio de área/i)).toBeInTheDocument();
    });

    it("activa a un usuario inactivo tras confirmar", async () => {
      seedSession("ADMIN");
      let activated = false;
      const fetchSpy = mockFetch(
        userRoutes((url, init) => {
          if (url.includes("/users/3/activate") && init?.method === "POST") {
            activated = true;
            return jsonResponse(200, { ...USERS[2], active: true, deactivationReason: null });
          }
          if (url.includes("/users?") && activated) {
            return jsonResponse(200, page([USERS[0], USERS[1], { ...USERS[2], active: true, deactivationReason: null }]));
          }
          return undefined;
        }),
      );
      renderApp("/usuarios");

      const row = (await screen.findByText("operador.centro@inventario.local")).closest("tr")!;
      await userEvent.click(within(row).getByRole("button", { name: /activar/i }));
      const dialog = await screen.findByRole("dialog");
      await userEvent.click(within(dialog).getByRole("button", { name: /^activar$/i }));

      const post = fetchSpy.mock.calls.find(([url, init]) => String(url).includes("/activate") && (init as RequestInit | undefined)?.method === "POST");
      expect(post).toBeDefined();
      await screen.findByText("operador.centro@inventario.local");
      expect(screen.queryByText(/motivo: renuncia/i)).not.toBeInTheDocument();
    });

    it("elimina a un usuario y lo retira del listado", async () => {
      seedSession("ADMIN");
      let deleted = false;
      const fetchSpy = mockFetch(
        userRoutes((url, init) => {
          if (url.includes("/users/3") && init?.method === "DELETE") {
            deleted = true;
            return new Response(null, { status: 204 });
          }
          if (url.includes("/users?") && deleted) {
            return jsonResponse(200, page([USERS[0], USERS[1]]));
          }
          return undefined;
        }),
      );
      renderApp("/usuarios");

      const row = (await screen.findByText("operador.centro@inventario.local")).closest("tr")!;
      await userEvent.click(within(row).getByRole("button", { name: /eliminar/i }));
      const dialog = await screen.findByRole("dialog");
      await userEvent.click(within(dialog).getByRole("button", { name: /eliminar/i }));

      const del = fetchSpy.mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === "DELETE");
      expect(del).toBeDefined();
      await screen.findByText("gerente.centro@inventario.local");
      expect(screen.queryByText("operador.centro@inventario.local")).not.toBeInTheDocument();
    });

    it("muestra el conflicto del backend al eliminar un usuario con historial asociado", async () => {
      seedSession("ADMIN");
      mockFetch(
        userRoutes((url, init) =>
          url.includes("/users/3") && init?.method === "DELETE"
            ? apiErrorResponse(409, "USUARIO_CON_DATOS_ASOCIADOS", "No se puede eliminar el usuario: tiene datos asociados.")
            : undefined,
        ),
      );
      renderApp("/usuarios");

      const row = (await screen.findByText("operador.centro@inventario.local")).closest("tr")!;
      await userEvent.click(within(row).getByRole("button", { name: /eliminar/i }));
      const dialog = await screen.findByRole("dialog");
      await userEvent.click(within(dialog).getByRole("button", { name: /eliminar/i }));

      expect(await screen.findByText(/no se puede eliminar el usuario: tiene datos asociados/i)).toBeInTheDocument();
    });
  });
});
