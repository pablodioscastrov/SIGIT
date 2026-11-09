package com.inventario.multisucursal.branches;

public record BranchResponse(String id, String code, String name, String location, boolean active) {

    public static BranchResponse from(Branch branch) {
        return new BranchResponse(
                String.valueOf(branch.getId()),
                branch.getCode(),
                branch.getName(),
                branch.getLocation(),
                branch.isActive());
    }
}
