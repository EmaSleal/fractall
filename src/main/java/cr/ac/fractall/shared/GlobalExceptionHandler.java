package cr.ac.fractall.shared;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import cr.ac.fractall.catalogo.servicio.ClienteExoneracionNoEncontradaException;
import cr.ac.fractall.catalogo.servicio.ClienteNoEncontradoException;
import cr.ac.fractall.catalogo.servicio.ProductoNoEncontradoException;
import cr.ac.fractall.facturacion.servicio.CantidadAcreditadaExcedeOrigenException;
import cr.ac.fractall.facturacion.servicio.ComprobanteNoReenviableException;
import cr.ac.fractall.facturacion.servicio.CondicionVentaInvalidaException;
import cr.ac.fractall.facturacion.servicio.ContadorConsecutivoNoEncontradoException;
import cr.ac.fractall.facturacion.servicio.CredencialHaciendaNoEncontradaException;
import cr.ac.fractall.facturacion.servicio.DocumentoNoDisponibleException;
import cr.ac.fractall.facturacion.servicio.EmpresaSinCorreoElectronicoException;
import cr.ac.fractall.facturacion.servicio.ExoneracionNoAplicableAFacturaElectronicaException;
import cr.ac.fractall.facturacion.servicio.ExoneracionNoPerteneceAlClienteException;
import cr.ac.fractall.facturacion.servicio.ExoneracionNoVigenteException;
import cr.ac.fractall.facturacion.servicio.ExoneracionRequiereClienteException;
import cr.ac.fractall.facturacion.servicio.FacturaNoEncontradaException;
import cr.ac.fractall.facturacion.servicio.FacturaOrigenNoAceptadaException;
import cr.ac.fractall.facturacion.servicio.LineaOrigenNoPerteneceAFacturaException;
import cr.ac.fractall.facturacion.servicio.MontoNotaCreditoExcedeOrigenException;
import cr.ac.fractall.facturacion.servicio.ReferenciaNoEsFacturaElectronicaException;
import cr.ac.fractall.facturacion.servicio.XmlFacturaFirmaException;
import cr.ac.fractall.hacienda.servicio.TipoCambioNoDisponibleException;
import cr.ac.fractall.seguridad.dto.MensajeResponse;
import cr.ac.fractall.seguridad.servicio.PermisoDenegadoException;
import cr.ac.fractall.seguridad.servicio.RolInvitacionInvalidoException;
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

    /**
     * Fase B (invitación y administración de membresías): primer 403 global de la aplicación,
     * lanzado por {@code PermisoGuard#exigir} cuando la membresía del actor no está ACTIVA en la
     * empresa objetivo o cuando el permiso solicitado no está en {@code permisos_efectivos}. Ver
     * el diseño de esa feature, decisión B.
     */
    @ExceptionHandler(PermisoDenegadoException.class)
    public ResponseEntity<MensajeResponse> manejarPermisoDenegado(PermisoDenegadoException excepcion) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new MensajeResponse(excepcion.getMessage()));
    }

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
     *
     * <p>{@code RolInvitacionInvalidoException} se une a este grupo (Fase B, invitación de
     * usuarios): {@code rolCodigo} en {@code POST /usuarios/invitar} es un valor de negocio
     * elegido por el llamador, no un id de recurso, mismo criterio que las excepciones de
     * exoneración/condición de venta de este grupo -- ver su javadoc.
     */
    @ExceptionHandler({ExoneracionNoPerteneceAlClienteException.class,
            ExoneracionNoAplicableAFacturaElectronicaException.class,
            ExoneracionNoVigenteException.class,
            ExoneracionRequiereClienteException.class,
            CondicionVentaInvalidaException.class,
            RolInvitacionInvalidoException.class})
    public ResponseEntity<MensajeResponse> manejarReglaDeNegocioInvalida(RuntimeException excepcion) {
        return ResponseEntity.badRequest().body(new MensajeResponse(excepcion.getMessage()));
    }

    /**
     * Fallo de infraestructura/configuración de la empresa, no un error de datos del cliente
     * (Fase B, migrado desde {@code FacturaController#crear} -- ver el javadoc de la clase y el de
     * cada excepción individual sobre por qué es 503, nunca un 500 crudo ni 400/404/409).
     *
     * <p>{@code XmlFacturaFirmaException} se une a este grupo porque cubre tanto la ausencia del
     * certificado {@code .p12}/PIN de la empresa para el ambiente indicado como fallas del propio
     * proceso de firma XAdES-BES (ver su javadoc) -- mismo tipo de fallo de configuración/infra
     * que las otras tres, y como {@code XmlFacturaFirmaServiceImpl#firmar} corre detrás de
     * {@code ComprobanteXmlPersistenceService#generarYPersistirXml}, este handler cubre por igual
     * factura, tiquete, nota de crédito y nota de débito sin duplicar manejo por controlador.
     */
    @ExceptionHandler({ContadorConsecutivoNoEncontradoException.class,
            CredencialHaciendaNoEncontradaException.class,
            EmpresaSinCorreoElectronicoException.class,
            XmlFacturaFirmaException.class})
    public ResponseEntity<MensajeResponse> manejarFalloDeInfraestructuraOConfiguracion(RuntimeException excepcion) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new MensajeResponse(excepcion.getMessage()));
    }

    /**
     * Reglas de negocio de Nota de Crédito/Débito (Release 2 / Fase B, ver diseño D-E/D-G): el
     * estado de datos del origen (estado de aceptación, o saldo ya consumido por NC previas)
     * entra en conflicto con la operación solicitada.
     */
    @ExceptionHandler({FacturaOrigenNoAceptadaException.class, MontoNotaCreditoExcedeOrigenException.class})
    public ResponseEntity<MensajeResponse> manejarConflictoDeEstadoNotaCreditoDebito(RuntimeException excepcion) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new MensajeResponse(excepcion.getMessage()));
    }

    /**
     * Reglas de negocio de Nota de Crédito/Débito detectadas en Java ANTES de persistir (Release
     * 2 / Fase B, ver diseño D-E/D-G).
     */
    @ExceptionHandler({ReferenciaNoEsFacturaElectronicaException.class,
            LineaOrigenNoPerteneceAFacturaException.class,
            CantidadAcreditadaExcedeOrigenException.class})
    public ResponseEntity<MensajeResponse> manejarReglaDeNegocioInvalidaNotaCreditoDebito(RuntimeException excepcion) {
        return ResponseEntity.badRequest().body(new MensajeResponse(excepcion.getMessage()));
    }

    /**
     * Complemento de defensa en profundidad al pre-chequeo de Java de {@code
     * NotaCreditoDebitoService#crearNotaCredito} (regla 3, tope de monto) -- cubre la carrera de
     * concurrencia entre dos Notas de Crédito que ambas pasan el pre-chequeo antes de que
     * cualquiera haga commit (ver el diseño de Fase B, sección "Open Questions", y el javadoc de
     * {@code MontoNotaCreditoExcedeOrigenException}). Un {@code RAISE EXCEPTION} de Postgres sin
     * código SQLSTATE explícito (el caso del trigger {@code fn_validar_tope_nota_credito}, V18)
     * NO se traduce a {@link DataIntegrityViolationException} -- pertenece a otra clase de
     * SQLSTATE ({@code P0001}) -- así que sin este handler escalaría como un 500 crudo.
     *
     * <p>Filtra explícitamente por {@code SQLSTATE == "P0001"} para no capturar cualquier otro
     * {@link UncategorizedSQLException} no relacionado con NC/ND: si no coincide, se re-lanza
     * para escalar sin manejar (mismo comportamiento que tenía ANTES de este handler).
     */
    @ExceptionHandler(UncategorizedSQLException.class)
    public ResponseEntity<MensajeResponse> manejarErrorSqlNoCategorizado(UncategorizedSQLException excepcion) {
        String sqlState = excepcion.getSQLException().getSQLState();
        if (!"P0001".equals(sqlState)) {
            throw excepcion;
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new MensajeResponse(excepcion.getSQLException().getMessage()));
    }
}
