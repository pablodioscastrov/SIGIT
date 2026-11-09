package com.inventario.multisucursal.products;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * docs/API_DESIGN.md, sección 7.4: el PATCH actualiza "nombre/descripción" —
 * ni el SKU ni la unidad base son editables aquí (cambiar la unidad base de
 * un producto ya en uso es una operación estructural fuera de este alcance).
 *
 * <p>{@code unitPrice} (BR-057, por instrucción explícita): a diferencia del
 * SKU y la unidad base, el precio de venta sí se puede editar después de
 * creado — {@code ProductService.update} lo fija como el nuevo {@code Price}
 * vigente en la lista de precios global por defecto, cerrando el anterior
 * (mismo versionado que {@code PriceListService.setPrice}, BR-019).
 *
 * <p>{@code minimumStock} (BR-059, por instrucción explícita): revierte la
 * inmutabilidad de BR-048 — solo cambia el valor de siembra que recibirán
 * las sucursales que todavía no tengan {@code Inventory} para este
 * producto, nunca las que ya lo tienen.
 */
public record UpdateProductRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 1000) String description,
        @NotNull @DecimalMin(value = "0.0", inclusive = false, message = "El precio de venta debe ser mayor que cero.") BigDecimal unitPrice,
        @NotNull @DecimalMin(value = "0.0", message = "El stock mínimo no puede ser negativo.") BigDecimal minimumStock) {
}
