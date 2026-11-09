import { FormErrorMessage } from "../form/Field";
import { Modal } from "./Modal";

/**
 * Confirmación para acciones sensibles (desactivar un producto, registrar un
 * ajuste manual de stock). Muestra explícitamente lo que va a ocurrir en vez
 * de un "¿Estás seguro?" genérico.
 *
 * <p>`error` se muestra <b>dentro</b> del diálogo: si se dejara fuera, el
 * modal seguiría abierto tapándolo y el mensaje quedaría invisible hasta
 * cerrar el propio diálogo que lo causó.
 */
export function ConfirmDialog({
  title,
  message,
  confirmLabel = "Confirmar",
  isPending = false,
  error,
  onConfirm,
  onCancel,
}: {
  title: string;
  message: string;
  confirmLabel?: string;
  isPending?: boolean;
  error?: unknown;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <Modal title={title} onClose={onCancel}>
      <p>{message}</p>
      <FormErrorMessage error={error} />
      <div className="modal__actions">
        <button type="button" onClick={onCancel} disabled={isPending}>
          Cancelar
        </button>
        <button type="button" className="button--danger" onClick={onConfirm} disabled={isPending}>
          {isPending ? "Procesando…" : confirmLabel}
        </button>
      </div>
    </Modal>
  );
}
