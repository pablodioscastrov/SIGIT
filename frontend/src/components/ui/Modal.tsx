import { useEffect, useRef } from "react";
import type { ReactNode } from "react";

/**
 * Diálogo modal mínimo y accesible: rol y etiqueta correctos, foco inicial
 * dentro del diálogo, cierre con `Escape`. Sin librería de UI y sin
 * animaciones, en línea con ADR-010.
 */
export function Modal({
  title,
  onClose,
  children,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
}) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    ref.current?.querySelector<HTMLElement>("input, select, textarea, button")?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  return (
    <div className="modal__backdrop">
      <div className="modal" role="dialog" aria-modal="true" aria-label={title} ref={ref}>
        <h2 className="modal__title">{title}</h2>
        {children}
      </div>
    </div>
  );
}
