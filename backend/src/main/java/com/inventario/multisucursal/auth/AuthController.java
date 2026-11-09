package com.inventario.multisucursal.auth;

import com.inventario.multisucursal.users.User;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/API_DESIGN.md, sección 7.1: {@code POST /auth/login} (público) y
 * {@code GET /auth/me} (requiere JWT válido, protegido por la regla
 * {@code anyRequest().authenticated()} de {@link SecurityConfig} — no
 * necesita ninguna anotación de autorización adicional en este método).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final LoginRateLimiter loginRateLimiter;

    public AuthController(AuthenticationManager authenticationManager, JwtService jwtService, LoginRateLimiter loginRateLimiter) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.loginRateLimiter = loginRateLimiter;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        // Auditoría de seguridad: sin este límite, /auth/login no tenía tope de
        // intentos — ver LoginRateLimiter.
        loginRateLimiter.checkNotBlocked(request.email());

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email(), request.password()));
        } catch (DisabledException ex) {
            // BR-055: distinto por instrucción explícita del resto de fallos de
            // login — ver DisabledAccountException para la nota de seguridad.
            // DaoAuthenticationProvider comprueba isEnabled() antes que la
            // contraseña, así que esto se dispara incluso con la contraseña
            // incorrecta sobre una cuenta desactivada.
            loginRateLimiter.recordFailure(request.email());
            throw new DisabledAccountException();
        } catch (AuthenticationException ex) {
            // BadCredentialsException (password incorrecta) y UsernameNotFoundException
            // (email inexistente) se tratan igual a propósito - ver InvalidCredentialsException.
            loginRateLimiter.recordFailure(request.email());
            throw new InvalidCredentialsException();
        }

        loginRateLimiter.recordSuccess(request.email());
        User user = ((AppUserDetails) authentication.getPrincipal()).getUser();
        AuthenticatedUser authenticatedUser =
                new AuthenticatedUser(user.getId(), user.getName(), user.getEmail(), user.getRoleCode(), user.getBranchId());
        String token = jwtService.generateToken(authenticatedUser);

        return new LoginResponse(token, jwtService.getExpirationSeconds(), UserSummaryResponse.from(user));
    }

    @GetMapping("/me")
    public UserSummaryResponse me(@AuthenticationPrincipal AuthenticatedUser currentUser) {
        return UserSummaryResponse.from(currentUser);
    }
}
