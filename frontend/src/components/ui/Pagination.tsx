import type { Page } from "../../types/api";

/**
 * Paginación sobre `PageResponse` del backend (docs/API_DESIGN.md, sección 4).
 * La página es 0-based en la API y se muestra 1-based al usuario.
 */
export function Pagination({ page, onPageChange }: { page: Page<unknown>; onPageChange: (page: number) => void }) {
  if (page.totalPages <= 1) return null;
  return (
    <nav className="pagination" aria-label="Paginación">
      <button type="button" onClick={() => onPageChange(page.page - 1)} disabled={page.page <= 0}>
        Anterior
      </button>
      <span>
        Página {page.page + 1} de {page.totalPages} · {page.totalElements} resultado(s)
      </span>
      <button type="button" onClick={() => onPageChange(page.page + 1)} disabled={page.page + 1 >= page.totalPages}>
        Siguiente
      </button>
    </nav>
  );
}
