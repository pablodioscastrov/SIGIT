package com.inventario.multisucursal.products;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * {@code minimumStock} (BR-010): el valor por defecto que recibirá el mínimo
 * de cada sucursal la primera vez que registre movimiento de este producto —
 * no una cantidad de stock del producto en sí (eso es de {@code Inventory},
 * por sucursal).
 *
 * <p>{@code unitPrice} (BR-051): el precio de venta con el que nace el
 * producto. No se guarda en {@code Product} — {@code ProductService.create}
 * lo fija como el primer {@code Price} vigente de la lista de precios global
 * por defecto, reutilizando el mecanismo de versionado ya existente
 * (docs/DOMAIN_MODEL.md, sección 2.14), para que una venta pueda resolverlo
 * automáticamente sin configuración adicional (BR-030).
 */
public record CreateProductRequest(
        @NotBlank @Size(max = 50) String sku,
        @NotBlank @Size(max = 150) String name,
        @Size(max = 1000) String description,
        @NotNull Long baseUnitOfMeasureId,
        @NotNull @DecimalMin(value = "0", message = "El stock mínimo no puede ser negativo.") BigDecimal minimumStock,
        @NotNull @DecimalMin(value = "0.0", inclusive = false, message = "El precio de venta debe ser mayor que cero.") BigDecimal unitPrice) {
}
