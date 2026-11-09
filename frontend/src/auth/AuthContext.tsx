import { createContext, useCallback, useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";
import { login as loginRequest } from "../api/endpoints/auth";
import { setUnauthorizedHandler } from "../api/httpClient";
import { clearSession, getStoredToken, getStoredUser, storeSession } from "./session";
import type { UserSummary } from "../types/api";

export interface AuthState {
  user: UserSummary | null;
  isAuthenticated: boolean;
  signIn: (email: string, password: string) => Promise<void>;
  signOut: () => void;
}

export const AuthContext = createContext<AuthState | undefined>(undefined);

/**
 * Estado de sesión de la aplicación.
 *
 * <p>Se inicializa desde `sessionStorage` de forma síncrona para que un
 * refresco de página no muestre la pantalla de login por un instante antes de
 * recuperar la sesión.
 *
 * <p>La sesión también puede terminar sin que el usuario pulse "salir": si la
 * API responde 401 (token expirado), el cliente HTTP avisa y aquí se limpia.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserSummary | null>(() => (getStoredToken() ? getStoredUser() : null));

  const signOut = useCallback(() => {
    clearSession();
    setUser(null);
  }, []);

  useEffect(() => {
    setUnauthorizedHandler(signOut);
    return () => setUnauthorizedHandler(() => {});
  }, [signOut]);

  const signIn = useCallback(async (email: string, password: string) => {
    const response = await loginRequest({ email, password });
    storeSession(response.accessToken, response.user);
    setUser(response.user);
  }, []);

  const value = useMemo<AuthState>(
    () => ({ user, isAuthenticated: user !== null, signIn, signOut }),
    [user, signIn, signOut],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
