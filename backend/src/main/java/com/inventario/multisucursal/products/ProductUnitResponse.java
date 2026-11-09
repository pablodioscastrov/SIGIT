package com.inventario.multisucursal.products;

import java.math.BigDecimal;

public record ProductUnitResponse(
        String unitOfMeasureId,
        String unitCode,
        String unitName,
        BigDecimal conversionFactorToBase,
        boolean baseUnit) {

    public static ProductUnitResponse from(ProductUnit productUnit, UnitOfMeasure unit) {
        return new ProductUnitResponse(
                String.valueOf(productUnit.getUnitOfMeasureId()),
                unit.getCode(),
                unit.getName(),
                productUnit.getConversionFactorToBase(),
                productUnit.isBaseUnit());
    }
}
