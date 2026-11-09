package com.inventario.multisucursal.transfers;

/** Tratamiento del faltante de una recepción parcial (BR-009; RF-026; flujo F2). */
public enum DiscrepancyTreatment {
    /** Se genera una nueva transferencia por la cantidad faltante. */
    REENVIO,
    /** El faltante se asume como pérdida y se cierra sin reposición. */
    AJUSTE,
    /** Se abre un reclamo al responsable del traslado. */
    RECLAMACION
}
