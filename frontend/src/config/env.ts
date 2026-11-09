/**
 * Configuración resuelta en tiempo de build (Vite inlinea las variables
 * `VITE_*` en el bundle, ver `frontend/Dockerfile`).
 *
 * La URL de la API nunca se escribe en el código: llega por
 * `VITE_API_BASE_URL`. Si falta, la aplicación falla aquí y de forma ruidosa
 * — un bundle sin API configurada es inservible, y es mucho mejor saberlo al
 * arrancar que descubrirlo en la primera petición con un 404 confuso.
 */
function required(name: string, value: string | undefined): string {
  if (!value || value.trim() === "") {
    throw new Error(
      `Falta la variable de entorno ${name}. Defínela antes de construir el frontend (ver .env.example en la raíz).`,
    );
  }
  return value.replace(/\/$/, "");
}

export const env = {
  apiBaseUrl: required("VITE_API_BASE_URL", import.meta.env.VITE_API_BASE_URL),
};
