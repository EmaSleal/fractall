package cr.ac.fractall.shared;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import cr.ac.fractall.catalogo.servicio.ClienteExoneracionNoEncontradaException;
import cr.ac.fractall.catalogo.servicio.ClienteNoEncontradoException;
import cr.ac.fractall.catalogo.servicio.ProductoNoEncontradoException;
import cr.ac.fractall.facturacion.servicio.ComprobanteNoReenviableException;
import cr.ac.fractall.facturacion.servicio.CondicionVentaInvalidaException;
import cr.ac.fractall.facturacion.servicio.ContadorConsecutivoNoEncontradoException;
import cr.ac.fractall.facturacion.servicio.CredencialHaciendaNoEncontradaException;
import cr.ac.fractall.facturacion.servicio.DocumentoNoDisponibleException;
import cr.ac.fractall.facturacion.servicio.EmpresaSinCorreoElectronicoException;
import cr.ac.fractall.facturacion.servicio.ExoneracionNoAplicableAFacturaElectronicaException;
import cr.ac.fractall.facturacion.servicio.ExoneracionNoPerteneceAlClienteException;
import cr.ac.fractall.facturacion.servicio.ExoneracionNoVigenteException;
import cr.ac.fractall.facturacion.servicio.FacturaNoEncontradaException;
import cr.ac.fractall.hacienda.servicio.TipoCambioNoDisponibleException;
import cr.ac.fractall.seguridad.dto.MensajeResponse;
import jakarta.validation.ConstraintViolationException;

/**
 * Backstop de defensa en profundidad para el patrón "check-then-act" de los pre-chequeos
 * explícitos de duplicados ({@code ClienteService}/{@code ProductoService}/
 * {@code ClienteExoneracionService}): dos solicitudes concurrentes con la misma clave única
 * pueden ambas pasar el {@code isPresent()} antes de que cualquiera de las dos haga commit -- en
 * ese caso el motor rechaza el segundo {@code INSERT} con una
 * {@code DataIntegrityViolationException} que, sin este advice, escalaría como un 500 crudo. NO
 * reemplaza los pre-chequeos explícitos (que dan un mensaje de dominio preciso en el camino no
 * concurrente); solo asegura que la ruta de carrera también responda con un 409 limpio.
 *
 * <p>Primer {@code @RestControllerAdvice} de la aplicación -- no existía ningún manejador global
 * de excepciones antes de esta clase.
 *
 * <p><b>Release 2 / Fase B (ver diseño D-G):</b> las 10 excepciones que {@code FacturaController}
 * capturaba explícitamente dentro de su try/catch de {@code crear()} se migraron aquí, en 3
 * handlers agrupados con los mismos statuses -- mismo {@code MensajeResponse} y mismo contrato de
 * respuesta para {@code POST /facturas}. <b>Ensanchamiento de comportamiento registrado:</b> antes
 * solo se capturaban dentro de ese único endpoint; mapeadas globalmente, la misma excepción lanzada
 * desde CUALQUIER otro endpoint ahora produce 404/400/503 en vez de un 500 crudo -- una mejora
 * estricta, pero sí es un cambio de contrato (ver {@code GlobalExceptionHandlerTest}).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(FacturaNoEncontradaException.class)
    public ResponseEntity<MensajeResponse> manejarFacturaNoEncontrada(FacturaNoEncontradaException excepcion) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new MensajeResponse(excepcion.getMessage()));
    }

    @ExceptionHandler(DocumentoNoDisponibleException.class)
    public ResponseEntity<MensajeResponse> manejarDocumentoNoDisponible(DocumentoNoDisponibleException excepcion) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new MensajeResponse(excepcion.getMessage()));
    }

    @ExceptionHandler(ComprobanteNoReenviableException.class)
    public ResponseEntity<MensajeResponse> manejarComprobanteNoReenviable(ComprobanteNoReenviableException excepcion) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new MensajeResponse(excepcion.getMessage()));
    }

    @ExceptionHandler(TipoCambioNoDisponibleException.class)
    public ResponseEntity<MensajeResponse> manejarTipoCambioNoDisponible(TipoCambioNoDisponibleException excepcion) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new MensajeResponse(excepcion.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<MensajeResponse> manejarViolacionDeIntegridad(DataIntegrityViolationException excepcion) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new MensajeResponse("El recurso ya existe o viola una restricción de unicidad."));
    }

    /**
     * Convierte {@link ConstraintViolationException} (lanzada por Bean Validation cuando se violan
     * restricciones en {@code @RequestParam} de un controlador anotado con {@code @Validated})
     * en una respuesta 400 con mensaje legible. Sin este handler, el error escalaría como 500.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<MensajeResponse> manejarViolacionDeRestriccion(ConstraintViolationException excepcion) {
        String mensaje = excepcion.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse(excepcion.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new MensajeResponse(mensaje));
    }

    /**
     * Convierte {@link MethodArgumentNotValidException} (lanzada por Bean Validation cuando se violan
     * restricciones en {@code @Valid @RequestBody}) en una respuesta 400 con el mismo formato que
     * {@link #manejarViolacionDeRestriccion}, normalizando el manejo de errores de validación.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<MensajeResponse> manejarValidacionRequestBody(MethodArgumentNotValidException excepcion) {
        String mensaje = excepcion.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Datos de entrada inválidos");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new MensajeResponse(mensaje));
    }

    /**
     * Recurso referenciado por id que no existe para el tenant actual (Fase B, migrado desde
     * {@code FacturaController#crear} -- ver el javadoc de la clase).
     */
    @ExceptionHandler({ClienteNoEncontradoException.class, ProductoNoEncontradoException.class,
            ClienteExoneracionNoEncontradaException.class})
    public ResponseEntity<MensajeResponse> manejarRecursoReferenciadoNoEncontrado(RuntimeException excepcion) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MensajeResponse(excepcion.getMessage()));
    }

    /**
     * Regla de negocio inválida detectada ANTES de persistir (Fase B, migrado desde
     * {@code FacturaController#crear} -- ver el javadoc de la clase).
     */
    @ExceptionHandler({ExoneracionNoPerteneceAlClienteException.class,
            ExoneracionNoAplicableAFacturaElectronicaException.class,
            ExoneracionNoVigenteException.class,
            CondicionVentaInvalidaException.class})
    public ResponseEntity<MensajeResponse> manejarReglaDeNegocioInvalida(RuntimeException excepcion) {
        return ResponseEntity.badRequest().body(new MensajeResponse(excepcion.getMessage()));
    }

    /**
     * Fallo de infraestructura/configuración de la empresa, no un error de datos del cliente
     * (Fase B, migrado desde {@code FacturaController#crear} -- ver el javadoc de la clase y el de
     * cada excepción individual sobre por qué es 503, nunca un 500 crudo ni 400/404/409).
     */
    @ExceptionHandler({ContadorConsecutivoNoEncontradoException.class,
            CredencialHaciendaNoEncontradaException.class,
            EmpresaSinCorreoElectronicoException.class})
    public ResponseEntity<MensajeResponse> manejarFalloDeInfraestructuraOConfiguracion(RuntimeException excepcion) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new MensajeResponse(excepcion.getMessage()));
    }
}
