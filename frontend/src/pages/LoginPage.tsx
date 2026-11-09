import { useState } from "react";
import type { FormEvent } from "react";
import { Navigate, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/useAuth";
import { Field, PasswordField } from "../components/form/Field";
import { ErrorState } from "../components/state/states";

export function LoginPage() {
  const { isAuthenticated, signIn } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<unknown>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  if (isAuthenticated) {
    return <Navigate to="/" replace />;
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setIsSubmitting(true);
    try {
      await signIn(email, password);
      // Vuelve a donde el usuario quería ir antes de que la guarda lo desviara.
      const from = (location.state as { from?: { pathname: string } } | null)?.from?.pathname ?? "/";
      navigate(from, { replace: true });
    } catch (caught) {
      setError(caught);
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <main className="login">
      <div className="login__card">
        <p className="login__brand">Inventario Multi-Sucursal</p>
        <h1>Iniciar sesión</h1>
        <form onSubmit={handleSubmit} noValidate={false}>
          <Field
            id="email"
            label="Correo electrónico"
            type="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            required
            autoComplete="username"
          />
          <PasswordField
            id="password"
            label="Contraseña"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required
            autoComplete="current-password"
          />
          {/* El error del servidor se muestra tal cual lo clasifica la API:
              credenciales inválidas, red caída, etc. No se adivina el motivo. */}
          {error ? <ErrorState error={error} /> : null}
          <button type="submit" className="login__submit" disabled={isSubmitting}>
            {isSubmitting ? "Entrando…" : "Entrar"}
          </button>
        </form>
      </div>
    </main>
  );
}
