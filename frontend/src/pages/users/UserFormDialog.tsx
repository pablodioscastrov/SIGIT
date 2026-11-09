import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import type { FormEvent } from "react";
import { createUser, updateUser } from "../../api/endpoints/users";
import { queryPrefixes } from "../../api/queryClient";
import { Field, FormErrorMessage, PasswordField, SelectField } from "../../components/form/Field";
import { toFormErrors } from "../../components/form/formErrors";
import { Modal } from "../../components/ui/Modal";
import type { Branch, Role, RoleInfo, User } from "../../types/api";

/**
 * Alta y edición de usuario en un mismo diálogo (UC-14; BR-058): ambas
 * asignan un rol y, salvo para `ADMIN` (alcance global, sin sucursal), la
 * sucursal a la que queda restringido. El backend vuelve a validar esa
 * consistencia (`ADMIN_SIN_SUCURSAL`/`SUCURSAL_REQUERIDA`,
 * `UserService.validateRoleBranchConsistency`) — aquí solo se evita el envío
 * obviamente incompleto, nunca se decide la regla. La contraseña solo se pide
 * al crear: cambiarla sigue siendo un flujo aparte, fuera de este diálogo.
 */
export function UserFormDialog({
  user,
  roles,
  branches,
  onClose,
}: {
  user?: User;
  roles: RoleInfo[];
  branches: Branch[];
  onClose: () => void;
}) {
  const isEdit = user !== undefined;
  const queryClient = useQueryClient();
  const [name, setName] = useState(user?.name ?? "");
  const [email, setEmail] = useState(user?.email ?? "");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [role, setRole] = useState<Role | "">(user?.role ?? "");
  const [branchId, setBranchId] = useState(user?.branchId ?? "");
  const [localErrors, setLocalErrors] = useState<Record<string, string>>({});

  const requiresBranch = role !== "" && role !== "ADMIN";

  const mutation = useMutation({
    mutationFn: () =>
      isEdit
        ? updateUser(user.id, {
            name: name.trim(),
            email: email.trim(),
            role: role as Role,
            branchId: requiresBranch ? Number(branchId) : null,
          })
        : createUser({
            name: name.trim(),
            email: email.trim(),
            password,
            role: role as Role,
            branchId: requiresBranch ? Number(branchId) : null,
          }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.users });
      onClose();
    },
  });

  const serverErrors = mutation.error ? toFormErrors(mutation.error) : { fields: {} as Record<string, string> };
  const errorFor = (field: string) => localErrors[field] ?? serverErrors.fields[field];

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    // Validación solo de forma; la semántica de negocio —correo repetido,
    // consistencia rol/sucursal— la decide el backend.
    const errors: Record<string, string> = {};
    if (!name.trim()) errors.name = "El nombre es obligatorio.";
    if (!email.trim()) errors.email = "El correo es obligatorio.";
    if (!isEdit && password.length < 8) errors.password = "La contraseña debe tener al menos 8 caracteres.";
    if (!isEdit && password !== confirmPassword) errors.confirmPassword = "Las contraseñas no coinciden.";
    if (!role) errors.role = "Selecciona un rol.";
    if (requiresBranch && !branchId) errors.branchId = "Selecciona la sucursal.";
    setLocalErrors(errors);
    if (Object.keys(errors).length > 0) return;
    mutation.mutate();
  }

  return (
    <Modal title={isEdit ? `Editar ${user.name}` : "Nuevo usuario"} onClose={onClose}>
      <form onSubmit={handleSubmit} noValidate>
        <Field id="user-name" label="Nombre" value={name} maxLength={150} onChange={(event) => setName(event.target.value)} error={errorFor("name")} />

        <Field
          id="user-email"
          label="Correo electrónico"
          type="email"
          value={email}
          maxLength={255}
          onChange={(event) => setEmail(event.target.value)}
          error={errorFor("email")}
        />

        {!isEdit ? (
          <>
            <PasswordField
              id="user-password"
              label="Contraseña"
              value={password}
              maxLength={100}
              onChange={(event) => setPassword(event.target.value)}
              error={errorFor("password")}
              autoComplete="new-password"
            />
            <PasswordField
              id="user-confirm-password"
              label="Confirmar contraseña"
              value={confirmPassword}
              maxLength={100}
              onChange={(event) => setConfirmPassword(event.target.value)}
              error={errorFor("confirmPassword")}
              autoComplete="new-password"
            />
          </>
        ) : null}

        <SelectField
          id="user-role"
          label="Rol"
          value={role}
          onChange={(event) => {
            setRole(event.target.value as Role | "");
            setBranchId("");
          }}
          error={errorFor("role")}
        >
          <option value="">Selecciona…</option>
          {roles.map((option) => (
            <option key={option.code} value={option.code}>
              {option.name}
            </option>
          ))}
        </SelectField>

        {requiresBranch ? (
          <SelectField id="user-branch" label="Sucursal" value={branchId} onChange={(event) => setBranchId(event.target.value)} error={errorFor("branchId")}>
            <option value="">Selecciona…</option>
            {branches.map((branch) => (
              <option key={branch.id} value={branch.id}>
                {branch.name}
              </option>
            ))}
          </SelectField>
        ) : role === "ADMIN" ? (
          <p className="state__hint">Un administrador general tiene alcance global, sin sucursal asignada.</p>
        ) : null}

        <FormErrorMessage error={serverErrors.general ? mutation.error : null} />

        <div className="modal__actions">
          <button type="button" onClick={onClose} disabled={mutation.isPending}>
            Cancelar
          </button>
          <button type="submit" disabled={mutation.isPending}>
            {mutation.isPending ? "Guardando…" : "Guardar"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
