import { ApiError } from "../../api/ApiError";
import { errorMessageFor } from "../state/states";
import { useState } from "react";
import type { InputHTMLAttributes, ReactNode, SelectHTMLAttributes, TextareaHTMLAttributes } from "react";

/**
 * Campo de formulario con etiqueta, error y accesibilidad ya resueltas.
 *
 * <p><b>Estrategia de formularios y validación</b> — deliberadamente sin
 * librería por ahora. Hoy el único formulario es el login (dos campos), y
 * añadir `react-hook-form` + `zod` para eso sería infraestructura sin uso.
 * La validación de cliente se limita a <i>forma</i>: obligatorio, tipo,
 * longitud — usando restricciones nativas de HTML, que además aportan
 * accesibilidad gratis.
 *
 * <p><b>Nunca se valida negocio en el cliente</b> (si hay stock, si una
 * transición es válida): eso es del backend, que ya responde 422 con un
 * código estable, y duplicarlo aquí crearía dos reglas que se desincronizan.
 *
 * <p>Criterio explícito para adoptar `react-hook-form` + `zod`: cuando lleguen
 * los formularios con líneas dinámicas (venta, compra, transferencia), donde
 * hay arrays de ítems, validación cruzada entre campos y estado de "sucio" por
 * campo. Ahí el costo de la dependencia sí se paga; hoy no.
 */
export function Field({
  label,
  error,
  id,
  ...inputProps
}: { label: string; error?: string; id: string } & InputHTMLAttributes<HTMLInputElement>) {
  const errorId = `${id}-error`;
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <input id={id} aria-invalid={error ? true : undefined} aria-describedby={error ? errorId : undefined} {...inputProps} />
      {error ? (
        <span id={errorId} role="alert" className="field__error">
          {error}
        </span>
      ) : null}
    </div>
  );
}

/**
 * Igual que `Field`, para contraseñas: añade un botón para alternar entre
 * texto oculto y visible, sin cambiar el tipo de dato que viaja al backend
 * (el propio valor del input, nunca uno derivado en el cliente).
 */
export function PasswordField({
  label,
  error,
  id,
  ...inputProps
}: { label: string; error?: string; id: string } & Omit<InputHTMLAttributes<HTMLInputElement>, "type">) {
  const [visible, setVisible] = useState(false);
  const errorId = `${id}-error`;
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <div className="field__password-wrapper">
        <input
          id={id}
          type={visible ? "text" : "password"}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? errorId : undefined}
          {...inputProps}
        />
        {/*
         * Sin `aria-label`: el propio texto del botón ("Mostrar"/"Ocultar")
         * ya es su nombre accesible. Un `aria-label` que repita la palabra
         * "contraseña" (p. ej. "Mostrar contraseña") hace que
         * `getByLabelText(/contraseña/i)` — el patrón ya usado en las pruebas
         * de login/rutas para ubicar el input — encuentre dos elementos
         * (el input y este botón) en vez de uno.
         */}
        <button
          type="button"
          className="field__password-toggle"
          onClick={() => setVisible((current) => !current)}
          aria-pressed={visible}
          tabIndex={-1}
        >
          {visible ? "Ocultar" : "Mostrar"}
        </button>
      </div>
      {error ? (
        <span id={errorId} role="alert" className="field__error">
          {error}
        </span>
      ) : null}
    </div>
  );
}

/** Igual que `Field`, para un desplegable. */
export function SelectField({
  label,
  error,
  id,
  children,
  ...selectProps
}: { label: string; error?: string; id: string; children: ReactNode } & SelectHTMLAttributes<HTMLSelectElement>) {
  const errorId = `${id}-error`;
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <select id={id} aria-invalid={error ? true : undefined} aria-describedby={error ? errorId : undefined} {...selectProps}>
        {children}
      </select>
      {error ? (
        <span id={errorId} role="alert" className="field__error">
          {error}
        </span>
      ) : null}
    </div>
  );
}

/** Igual que `Field`, para texto largo (descripciones, motivos de ajuste). */
export function TextAreaField({
  label,
  error,
  id,
  ...textareaProps
}: { label: string; error?: string; id: string } & TextareaHTMLAttributes<HTMLTextAreaElement>) {
  const errorId = `${id}-error`;
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <textarea id={id} aria-invalid={error ? true : undefined} aria-describedby={error ? errorId : undefined} {...textareaProps} />
      {error ? (
        <span id={errorId} role="alert" className="field__error">
          {error}
        </span>
      ) : null}
    </div>
  );
}

/**
 * Error del formulario que no pertenece a un campo concreto. Muestra el
 * mensaje del backend sin reinterpretarlo y la referencia de la petición,
 * para poder correlacionarla con los logs del servidor.
 */
export function FormErrorMessage({ error }: { error: unknown }) {
  if (!error) return null;
  const apiError = error instanceof ApiError ? error : undefined;
  return (
    <div role="alert" className="form__error">
      <p>{errorMessageFor(error)}</p>
      {apiError ? (
        <p className="state__hint">
          Código: {apiError.code}
          {apiError.requestId ? ` · Referencia: ${apiError.requestId}` : ""}
        </p>
      ) : null}
    </div>
  );
}
