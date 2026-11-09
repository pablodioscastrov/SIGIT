package com.inventario.multisucursal.products;

import java.math.BigDecimal;
import java.time.Instant;

public record PriceResponse(
        String id, String priceListId, String productId, BigDecimal unitPrice, Instant validFrom, Instant validTo) {

    public static PriceResponse from(Price price) {
        return new PriceResponse(
                String.valueOf(price.getId()),
                String.valueOf(price.getPriceListId()),
                String.valueOf(price.getProductId()),
                price.getUnitPrice(),
                price.getValidFrom(),
                price.getValidTo());
    }
}
