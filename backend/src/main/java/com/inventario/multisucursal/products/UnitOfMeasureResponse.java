package com.inventario.multisucursal.products;

public record UnitOfMeasureResponse(String id, String code, String name) {

    public static UnitOfMeasureResponse from(UnitOfMeasure unit) {
        return new UnitOfMeasureResponse(String.valueOf(unit.getId()), unit.getCode(), unit.getName());
    }
}
