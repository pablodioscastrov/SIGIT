import { describe, expect, it } from "vitest";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  apiErrorResponse,
  fileResponse,
  jsonResponse,
  mockEventSource,
  mockFetch,
  MockEventSource,
  mockObjectUrl,
  renderApp,
  seedSession,
} from "./harness";
import { PRODUCTS, catalogResponse, page, transfer } from "./catalog";

function transferRoutes(overrides: (url: string, init?: RequestInit) => Response | undefined = () => undefined) {
  return (url: string, init?: RequestInit) => {
    const custom = overrides(url, init);
    if (custom) return custom;
    const catalog = catalogResponse(url);
    if (catalog) return catalog;
    if (url.includes("/products")) return jsonResponse(200, page(PRODUCTS));
    if (/\/transfers\/\d+$/.test(url)) return jsonResponse(200, transfer());
    if (url.includes("/transfers")) return jsonResponse(200, page([transfer()]));
    return jsonResponse(200, page([]));
  };
}

describe("Listado de transferencias", () => {
  it("muestra origen, destino, estado y urgencia", async () => {
    seedSession("OPERATOR");
    mockFetch(transferRoutes());
    renderApp("/transferencias");

    const row = (await screen.findByText("TR-ABC12345")).closest("tr")!;
    expect(within(row).getByText("Sucursal Centro")).toBeInTheDocument();
    expect(within(row).getByText("Sucursal Norte")).toBeInTheDocument();
    expect(within(row).getByText("Solicitada")).toBeInTheDocument();
  });

  it("muestra el estado vacío cuando no hay transferencias", async () => {
    seedSession("OPERATOR");
    mockFetch(transferRoutes((url) => (url.includes("/transfers?") ? jsonResponse(200, page([])) : undefined)));
    renderApp("/transferencias");

    expect(await screen.findByText(/no hay transferencias que coincidan/i)).toBeInTheDocument();
  });

  it("cualquiera de los tres roles puede solicitar una transferencia", async () => {
    seedSession("MANAGER");
    mockFetch(transferRoutes());
    renderApp("/transferencias");

    expect(await screen.findByRole("button", { name: /solicitar transferencia/i })).toBeInTheDocument();
  });

  it("exporta a Excel con el rango de fechas elegido y descarga el archivo", async () => {
    seedSession("OPERATOR");
    const { createObjectURL } = mockObjectUrl();
    const fetchSpy = mockFetch(
      transferRoutes((url) => (url.includes("/reports/transfers/export") ? fileResponse("transferencias.xlsx") : undefined)),
    );
    renderApp("/transferencias");

    await screen.findByText("TR-ABC12345");
    await userEvent.click(screen.getByRole("button", { name: /exportar a excel/i }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/desde/i), { target: { value: "2026-08-01" } });
    fireEvent.change(within(dialog).getByLabelText(/hasta/i), { target: { value: "2026-08-31" } });
    await userEvent.click(within(dialog).getByRole("button", { name: /^exportar$/i }));

    await waitFor(() => expect(createObjectURL).toHaveBeenCalled());
    expect(fetchSpy.mock.calls.some(([url]) => String(url).includes("/reports/transfers/export?"))).toBe(true);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});

describe("Acciones por estado, rol y sucursal", () => {
  it("MANAGER de la sucursal origen ve Aprobar y Rechazar en una transferencia Solicitada", async () => {
    seedSession("MANAGER");
    mockFetch(transferRoutes());
    renderApp("/transferencias/500");

    expect(await screen.findByRole("button", { name: /aprobar/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /rechazar/i })).toBeInTheDocument();
  });

  it("MANAGER de una sucursal ajena no ve acciones de aprobación", async () => {
    seedSession("MANAGER", "2");
    mockFetch(transferRoutes((url) => (/\/transfers\/\d+$/.test(url) ? jsonResponse(200, transfer({ originBranchId: "1", destinationBranchId: "9" })) : undefined)));
    renderApp("/transferencias/500");

    expect(await screen.findByText("TR-ABC12345")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /aprobar/i })).not.toBeInTheDocument();
  });

  it("OPERATOR no ve Aprobar/Rechazar aunque esté en la sucursal origen: esa acción es de MANAGER", async () => {
    seedSession("OPERATOR");
    mockFetch(transferRoutes());
    renderApp("/transferencias/500");

    expect(await screen.findByText("TR-ABC12345")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /aprobar/i })).not.toBeInTheDocument();
  });

  it("OPERATOR de origen ve Despachar solo cuando la transferencia está Aprobada", async () => {
    seedSession("OPERATOR");
    mockFetch(transferRoutes((url) => (/\/transfers\/\d+$/.test(url) ? jsonResponse(200, transfer({ status: "APPROVED", items: [{ ...transfer().items[0], quantityApproved: 10 }] })) : undefined)));
    renderApp("/transferencias/500");

    expect(await screen.findByRole("button", { name: /despachar/i })).toBeInTheDocument();
  });

  it("OPERATOR de destino no ve Despachar: esa acción es de la sucursal origen", async () => {
    seedSession("OPERATOR", "2");
    mockFetch(transferRoutes((url) => (/\/transfers\/\d+$/.test(url) ? jsonResponse(200, transfer({ status: "APPROVED", originBranchId: "1", destinationBranchId: "2", items: [{ ...transfer().items[0], quantityApproved: 10 }] })) : undefined)));
    renderApp("/transferencias/500");

    expect(await screen.findByText("TR-ABC12345")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /despachar/i })).not.toBeInTheDocument();
  });

  it("OPERATOR de destino ve Recibir solo cuando está En tránsito", async () => {
    seedSession("OPERATOR", "2");
    mockFetch(
      transferRoutes((url) =>
        /\/transfers\/\d+$/.test(url)
          ? jsonResponse(
              200,
              transfer({
                status: "IN_TRANSIT",
                originBranchId: "1",
                destinationBranchId: "2",
                items: [{ ...transfer().items[0], quantityApproved: 10, quantityShipped: 10 }],
              }),
            )
          : undefined,
      ),
    );
    renderApp("/transferencias/500");

    expect(await screen.findByRole("button", { name: /recibir/i })).toBeInTheDocument();
  });

  it("MANAGER de origen también ve Despachar (ampliación de permisos)", async () => {
    seedSession("MANAGER");
    mockFetch(transferRoutes((url) => (/\/transfers\/\d+$/.test(url) ? jsonResponse(200, transfer({ status: "APPROVED", items: [{ ...transfer().items[0], quantityApproved: 10 }] })) : undefined)));
    renderApp("/transferencias/500");

    expect(await screen.findByRole("button", { name: /despachar/i })).toBeInTheDocument();
  });

  it("MANAGER de destino también ve Recibir (ampliación de permisos)", async () => {
    seedSession("MANAGER", "2");
    mockFetch(
      transferRoutes((url) =>
        /\/transfers\/\d+$/.test(url)
          ? jsonResponse(
              200,
              transfer({
                status: "IN_TRANSIT",
                originBranchId: "1",
                destinationBranchId: "2",
                items: [{ ...transfer().items[0], quantityApproved: 10, quantityShipped: 10 }],
              }),
            )
          : undefined,
      ),
    );
    renderApp("/transferencias/500");

    expect(await screen.findByRole("button", { name: /recibir/i })).toBeInTheDocument();
  });
});

describe("Recepción parcial y faltante", () => {
  it("una recepción menor a lo enviado deja faltante sin tratar, visible para el rol que puede tratarlo", async () => {
    seedSession("MANAGER");
    const partial = transfer({
      status: "RECEIVED_PARTIAL",
      originBranchId: "1",
      destinationBranchId: "1",
      receivedAt: "2026-08-27T12:00:00Z",
      items: [{ ...transfer().items[0], quantityApproved: 10, quantityShipped: 10, quantityReceived: 6, quantityMissing: 4 }],
    });
    mockFetch(transferRoutes((url) => (/\/transfers\/\d+$/.test(url) ? jsonResponse(200, partial) : undefined)));
    renderApp("/transferencias/500");

    expect(await screen.findByText(/recibida con faltante/i)).toBeInTheDocument();
    expect(screen.getByText("Sin tratar")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /tratar faltante/i })).toBeInTheDocument();
    expect(screen.getByText(/1 línea\(s\) con faltante sin tratar/i)).toBeInTheDocument();
  });

  it("registra el tratamiento REENVIO y enlaza la transferencia de reposición creada", async () => {
    seedSession("MANAGER");
    const partial = transfer({
      status: "RECEIVED_PARTIAL",
      originBranchId: "1",
      destinationBranchId: "1",
      items: [{ ...transfer().items[0], quantityApproved: 10, quantityShipped: 10, quantityReceived: 6, quantityMissing: 4 }],
    });
    mockFetch(
      transferRoutes((url, init) => {
        if (url.includes("/discrepancy-treatment") && init?.method === "POST") {
          return jsonResponse(200, { transferItemId: "5000", discrepancyTreatment: "REENVIO", followUpTransferId: "999", transferStatus: "CLOSED" });
        }
        if (/\/transfers\/\d+$/.test(url)) return jsonResponse(200, partial);
        return undefined;
      }),
    );
    renderApp("/transferencias/500");

    await userEvent.click(await screen.findByRole("button", { name: /tratar faltante/i }));
    const dialog = await screen.findByRole("dialog", { name: /tratar faltante/i });
    await userEvent.click(within(dialog).getByRole("button", { name: /registrar tratamiento/i }));

    expect(await screen.findByText(/se creó la transferencia de reposición/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "#999" })).toHaveAttribute("href", "/transferencias/999");
    expect(screen.getByText(/quedó cerrada/i)).toBeInTheDocument();
  });

  it("una reclamación ya registrada muestra su detalle a cualquiera que consulte la transferencia después", async () => {
    // Tanto la sucursal origen como la destino pueden ver el detalle de una
    // RECLAMACION ya creada, no solo quien la registró — el detalle (notas)
    // se persiste y se expone en la respuesta, no se pierde tras crearla.
    seedSession("OPERATOR", "2"); // sucursal destino, no quien la trató
    const closed = transfer({
      status: "CLOSED",
      originBranchId: "1",
      destinationBranchId: "2",
      items: [
        {
          ...transfer().items[0],
          quantityApproved: 10,
          quantityShipped: 10,
          quantityReceived: 6,
          quantityMissing: 4,
          discrepancyTreatment: "RECLAMACION",
          treatmentNotes: "El transportista reconoció el faltante; a la espera de nota de crédito.",
        },
      ],
    });
    mockFetch(transferRoutes((url) => (/\/transfers\/\d+$/.test(url) ? jsonResponse(200, closed) : undefined)));
    renderApp("/transferencias/500");

    expect(await screen.findByText("Reclamación")).toBeInTheDocument();
    expect(screen.getByText(/el transportista reconoció el faltante/i)).toBeInTheDocument();
  });
});

describe("Conflicto de estado (409)", () => {
  it("al aprobar una transferencia que alguien más ya cambió, explica el conflicto y refresca el recurso", async () => {
    seedSession("MANAGER");
    let alreadyChanged = false;
    mockFetch(
      transferRoutes((url, init) => {
        if (url.includes("/approve") && init?.method === "POST") {
          alreadyChanged = true;
          return apiErrorResponse(409, "TRANSICION_INVALIDA", "La transferencia ya no está en estado REQUESTED.");
        }
        if (/\/transfers\/\d+$/.test(url)) {
          return jsonResponse(200, alreadyChanged ? transfer({ status: "APPROVED", approvedByUserId: "9", items: [{ ...transfer().items[0], quantityApproved: 10 }] }) : transfer());
        }
        return undefined;
      }),
    );
    renderApp("/transferencias/500");

    await userEvent.click(await screen.findByRole("button", { name: /aprobar/i }));
    const dialog = await screen.findByRole("dialog", { name: /aprobar/i });
    await userEvent.click(within(dialog).getByRole("button", { name: /^aprobar$/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/alguien más ya cambió el estado/i);
    // El diálogo se cierra: sus datos ya no describen la transferencia real.
    expect(screen.queryByRole("dialog", { name: /aprobar/i })).not.toBeInTheDocument();
    // El recurso se refrescó: ahora se ve Aprobada, sin botón de Aprobar.
    await waitFor(() => expect(document.querySelector(".badge")).toHaveTextContent("Aprobada"));
    expect(screen.queryByRole("button", { name: /aprobar/i })).not.toBeInTheDocument();
  });
});

describe("Actualización near-real-time", () => {
  it("una señal transfer.status-changed refresca el listado sin acción del usuario", async () => {
    seedSession("OPERATOR");
    mockEventSource();
    let statusChanged = false;
    const fetchSpy = mockFetch(
      transferRoutes((url) => {
        if (url.includes("/transfers?") || (url.includes("/transfers") && !/\/transfers\/\d+/.test(url))) {
          return jsonResponse(200, page([transfer({ status: statusChanged ? "APPROVED" : "REQUESTED" })]));
        }
        return undefined;
      }),
    );
    renderApp("/transferencias");

    expect(await screen.findByText("Solicitada")).toBeInTheDocument();
    const callsBefore = fetchSpy.mock.calls.length;

    statusChanged = true;
    const source = MockEventSource.instances[MockEventSource.instances.length - 1];
    source.emit("transfer.status-changed", { type: "transfer.status-changed", branchIds: ["1", "2"], resourceId: "500", occurredAt: "2026-08-27T12:00:00Z" });

    await waitFor(() => expect(fetchSpy.mock.calls.length).toBeGreaterThan(callsBefore));
    await waitFor(() => expect(document.querySelector(".badge")).toHaveTextContent("Aprobada"));
  });

  it("al reconectar (evento onopen) también se reconcilia contra REST", async () => {
    seedSession("OPERATOR");
    mockEventSource();
    const fetchSpy = mockFetch(transferRoutes());
    renderApp("/transferencias");

    await screen.findByText("Solicitada");
    const callsBefore = fetchSpy.mock.calls.length;

    const source = MockEventSource.instances[MockEventSource.instances.length - 1];
    source.onopen?.();

    await waitFor(() => expect(fetchSpy.mock.calls.length).toBeGreaterThan(callsBefore));
  });
});
