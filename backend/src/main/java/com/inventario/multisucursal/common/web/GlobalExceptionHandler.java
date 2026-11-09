package com.inventario.multisucursal.common.web;

import com.inventario.multisucursal.common.exception.ApiException;
import com.inventario.multisucursal.common.exception.BranchAccessDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * Traduce toda excepción de la capa de entrada al sobre uniforme de error
 * (docs/API_DESIGN.md, sección 3). Punto único: ningún controlador de módulo
 * debe construir su propia respuesta de error a mano.
 *
 * <p>Los handlers de {@link AuthenticationException}/{@link AccessDeniedException}
 * cubren el caso en que la excepción de seguridad ocurre <b>dentro</b> de la
 * invocación de un controlador (p. ej. una denegación de
 * {@code @PreAuthorize}, que Spring evalúa vía un proxy AOP alrededor del
 * método del controlador). Sin ellos, el catch-all de {@link Exception} de
 * más abajo las capturaría primero (Spring resuelve por el handler más
 * específico disponible en este {@code @RestControllerAdvice} antes de dejar
 * que la excepción se propague a la cadena de filtros) y las convertiría
 * incorrectamente en un 500. El caso en que la excepción ocurre en la cadena
 * de filtros de seguridad, antes de llegar a cualquier controlador (p. ej.
 * sin token en absoluto), lo cubren por separado
 * {@code JsonAuthenticationEntryPoint}/{@code JsonAccessDeniedHandler}, con el
 * mismo formato de salida.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(ApiException ex) {
        return build(ex.getStatus(), ex.getCode(), ex.getMessage(), ex.getDetails());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        List<ErrorDetail> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new ErrorDetail(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "La solicitud contiene datos inválidos.", details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        List<ErrorDetail> details = ex.getConstraintViolations().stream()
                .map(violation -> new ErrorDetail(violation.getPropertyPath().toString(), violation.getMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "La solicitud contiene datos inválidos.", details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "El cuerpo de la solicitud no es JSON válido.", List.of());
    }

    /**
     * Ninguna ruta/recurso estático coincide con la solicitud. Debe declararse
     * explícitamente: sin este handler, el catch-all de {@link Exception}
     * de más abajo la capturaría igual y la convertiría incorrectamente en un
     * 500, rompiendo el comportamiento por defecto de Spring de responder 404
     * cuando no hay ningún handler que atienda la ruta.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "RECURSO_NO_ENCONTRADO", "El recurso solicitado no existe.", List.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthentication(AuthenticationException ex) {
        return build(HttpStatus.UNAUTHORIZED, "NO_AUTENTICADO", "Token ausente, inválido o expirado.", List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        if (ex instanceof BranchAccessDeniedException) {
            return build(HttpStatus.FORBIDDEN, "SUCURSAL_NO_AUTORIZADA", ex.getMessage(), List.of());
        }
        return build(HttpStatus.FORBIDDEN, "ROL_NO_AUTORIZADO", "No tiene permisos para realizar esta acción.", List.of());
    }

    /**
     * Resguardo genérico ante una violación de restricción de unicidad/FK que
     * llegó hasta la base de datos — normalmente cada módulo valida
     * explícitamente antes de escribir (p. ej. {@code existsByCode}) y lanza
     * un {@link com.inventario.multisucursal.common.exception.ResourceConflictException}
     * con un código específico; esto solo cubre la carrera residual entre esa
     * comprobación y el `INSERT` real.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        return build(HttpStatus.CONFLICT, "CONFLICTO_DATOS", "La operación viola una restricción de integridad de datos.", List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Error no controlado en {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_INTERNO", "Ocurrió un error inesperado.", List.of());
    }

    private ResponseEntity<ApiErrorResponse> build(HttpStatus status, String code, String message, List<ErrorDetail> details) {
        String requestId = MDC.get(CorrelationIdFilter.MDC_KEY);
        ApiErrorBody body = new ApiErrorBody(code, message, status.value(), requestId, details);
        return ResponseEntity.status(status).body(new ApiErrorResponse(body));
    }
}
