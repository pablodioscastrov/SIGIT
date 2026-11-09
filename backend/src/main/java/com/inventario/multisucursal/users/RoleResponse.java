package com.inventario.multisucursal.users;

public record RoleResponse(String code, String name) {

    public static RoleResponse from(RoleCode role) {
        return new RoleResponse(role.name(), role.getDisplayName());
    }
}
