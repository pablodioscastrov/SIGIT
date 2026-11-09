import { useEffect, useState } from "react";
import { Link, Outlet, useLocation } from "react-router-dom";
import { useAuth } from "../../auth/useAuth";
import { visibleNavItems } from "../../auth/permissions";

/** Estructura común de las pantallas autenticadas: cabecera, navegación por rol y contenido. */
export function AppLayout() {
  const { user, signOut } = useAuth();
  const items = visibleNavItems(user?.role);
  const [menuOpen, setMenuOpen] = useState(false);
  const location = useLocation();

  // El menú de navegación (pantallas angostas) se cierra solo al cambiar de ruta.
  useEffect(() => {
    setMenuOpen(false);
  }, [location.pathname]);

  /*
   * El "activo" no puede resolverse ítem por ítem (el comportamiento por
   * defecto de `NavLink`, que marca cualquier ruta que empiece por su `to`):
   * "Inventario" (`/inventario`) e "Alertas" (`/inventario/alertas`) son
   * ambos prefijo válido de `/inventario/alertas`, así que los dos se
   * encendían a la vez al entrar a Alertas. Se resuelve una única vez, por
   * toda la lista, cuál es el ítem cuyo `path` coincide de forma más
   * específica con la ruta actual — el mismo criterio que ya evita
   * ambigüedad en cualquier otro par de módulos con el mismo prefijo que
   * pueda aparecer más adelante (p. ej. si "Compras" y una futura entrada
   * "/compras/reportes" coexistieran en el menú).
   */
  const activeItemPath = items
    .map((item) => item.path)
    .filter((path) => location.pathname === path || location.pathname.startsWith(`${path}/`))
    .sort((a, b) => b.length - a.length)[0];

  return (
    <div className="app">
      <header className="app__header">
        <div className="app__header-top">
          <Link to="/" className="app__brand">
            <span className="app__brand-mark" aria-hidden="true">IM</span>
            Inventario Multi-Sucursal
          </Link>
          <button
            type="button"
            className="app__menu-toggle"
            aria-expanded={menuOpen}
            aria-controls="app-nav"
            aria-label={menuOpen ? "Cerrar menú de navegación" : "Abrir menú de navegación"}
            onClick={() => setMenuOpen((open) => !open)}
          >
            <span aria-hidden="true">☰</span>
          </button>
          <div className="app__session">
            {user ? (
              <span>
                {user.name} · {user.role}
              </span>
            ) : null}
            <button type="button" onClick={signOut}>
              Cerrar sesión
            </button>
          </div>
        </div>
        <nav id="app-nav" className="app__nav-bar" aria-label="Navegación principal" data-open={menuOpen}>
          <ul>
            {items.map((item) => (
              <li key={item.path}>
                <Link to={item.path} aria-current={item.path === activeItemPath ? "page" : undefined}>
                  {item.label}
                </Link>
              </li>
            ))}
          </ul>
        </nav>
      </header>
      <main className="app__content">
        <Outlet />
      </main>
    </div>
  );
}
