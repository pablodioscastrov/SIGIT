package com.inventario.multisucursal.transfers;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ApplyDiscrepancyTreatmentRequest(@NotNull DiscrepancyTreatment treatment, @Size(max = 1000) String notes) {
}
