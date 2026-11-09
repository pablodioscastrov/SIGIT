import { useContext } from "react";
import { AuthContext } from "./AuthContext";
import type { AuthState } from "./AuthContext";

export function useAuth(): AuthState {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth debe usarse dentro de <AuthProvider>.");
  }
  return context;
}
