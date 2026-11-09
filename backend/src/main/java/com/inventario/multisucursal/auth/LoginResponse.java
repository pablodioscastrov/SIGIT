package com.inventario.multisucursal.auth;

public record LoginResponse(String accessToken, long expiresIn, UserSummaryResponse user) {
}
