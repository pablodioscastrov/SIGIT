package com.inventario.multisucursal.auth;

import com.inventario.multisucursal.users.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public AppUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        return userRepository.findByEmail(email)
                .map(AppUserDetails::new)
                // Mensaje genérico: no confirmar si el email existe o no (evita
                // enumeración de usuarios). El código HTTP real lo decide
                // AuthController al traducir la AuthenticationException resultante
                // a InvalidCredentialsException (401 CREDENCIALES_INVALIDAS) — salvo
                // que la cuenta exista pero esté desactivada (isEnabled() = false),
                // caso que AuthController distingue como DisabledAccountException
                // (401 CUENTA_DESACTIVADA, BR-055).
                .orElseThrow(() -> new UsernameNotFoundException("Usuario no encontrado"));
    }
}
