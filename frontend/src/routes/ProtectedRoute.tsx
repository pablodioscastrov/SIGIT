import { Navigate, Outlet, useLocation } from "react-router-dom";
import { useAuth } from "../auth/useAuth";
import type { Role } from "../types/api";

/**
 * Guarda de navegación.
 *
 * <p><b>Es conveniencia, no seguridad.</b> Evita que un usuario aterrice en
 * una pantalla donde solo obtendría errores, y nada más: cualquiera puede
 * saltarse una guarda de cliente. La autorización de verdad la aplica el
 * backend en cada petición, y por eso la interfaz sabe mostrar un 403 aunque
 * la guarda haya dejado pasar (ver `ForbiddenPage` y `ErrorState`).
 */
export function ProtectedRoute() {
  const { isAuthenticated } = useAuth();
  const location = useLocation();

  if (!isAuthenticated) {
    // Se recuerda a dónde iba para volver ahí tras iniciar sesión.
    return <Navigate to="/login" replace state={{ from: location }} />;
  }
  return <Outlet />;
}

export function RequireRole({ roles }: { roles: Role[] }) {
  const { user } = useAuth();

  if (!user || !roles.includes(user.role)) {
    return <Navigate to="/sin-permiso" replace />;
  }
  return <Outlet />;
}
