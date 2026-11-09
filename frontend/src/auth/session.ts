import type { UserSummary } from "../types/api";

/**
 * Persistencia de la sesión entre recargas.
 *
 * <p>Se usa `sessionStorage`, no `localStorage`: el token se borra al cerrar
 * la pestaña, lo que acota la ventana de exposición sin obligar al usuario a
 * reautenticarse cada vez que refresca.
 *
 * <p><b>Limitación asumida y consciente:</b> cualquier almacenamiento
 * accesible por JavaScript es legible ante un XSS. La defensa realmente
 * sólida sería una cookie `HttpOnly`, pero el backend emite el JWT en el
 * cuerpo de la respuesta de login (ADR-005, diseño stateless) y cambiarlo
 * exigiría rediseñar esa decisión aprobada — queda registrado en ADR-010 como
 * el criterio para reconsiderarla, no resuelto en silencio aquí.
 */

const TOKEN_KEY = "inventario.accessToken";
const USER_KEY = "inventario.user";

/** `sessionStorage` puede lanzar (modo privado, cookies bloqueadas): nunca debe tumbar la app. */
function safeStorage(): Storage | null {
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

export function getStoredToken(): string | null {
  return safeStorage()?.getItem(TOKEN_KEY) ?? null;
}

export function getStoredUser(): UserSummary | null {
  const raw = safeStorage()?.getItem(USER_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as UserSummary;
  } catch {
    // Dato corrupto: se trata como "sin sesión" en vez de romper el arranque.
    return null;
  }
}

export function storeSession(token: string, user: UserSummary): void {
  const storage = safeStorage();
  storage?.setItem(TOKEN_KEY, token);
  storage?.setItem(USER_KEY, JSON.stringify(user));
}

export function clearSession(): void {
  const storage = safeStorage();
  storage?.removeItem(TOKEN_KEY);
  storage?.removeItem(USER_KEY);
}
