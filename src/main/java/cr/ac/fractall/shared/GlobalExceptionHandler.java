package cr.ac.fractall.shared;

import java.sql.SQLException;
import java.util.Map;

import org.hibernate.JDBCException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.orm.jpa.JpaSystemException;
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
import cr.ac.fractall.facturacion.servicio.FacturaNoCobrableException;
import cr.ac.fractall.facturacion.servicio.FacturaNoEncontradaException;
import cr.ac.fractall.facturacion.servicio.FacturaOrigenNoAceptadaException;
import cr.ac.fractall.facturacion.servicio.LineaOrigenNoPerteneceAFacturaException;
import cr.ac.fractall.facturacion.servicio.MedioPagoInvalidoException;
import cr.ac.fractall.facturacion.servicio.MontoCobroExcedeSaldoException;
import cr.ac.fractall.facturacion.servicio.MontoNotaCreditoExcedeOrigenException;
import cr.ac.fractall.facturacion.servicio.ReferenciaNoEsFacturaElectronicaException;
import cr.ac.fractall.facturacion.servicio.XmlFacturaFirmaException;
import cr.ac.fractall.hacienda.servicio.TipoCambioNoDisponibleException;
import cr.ac.fractall.reportes.servicio.RangoFechasInvalidaException;
import cr.ac.fractall.seguridad.dto.MensajeResponse;
import cr.ac.fractall.seguridad.servicio.AutoGestionNoPermitidaException;
import cr.ac.fractall.seguridad.servicio.InvitacionInvalidaException;
import cr.ac.fractall.seguridad.servicio.MiembroNoEncontradoException;
import cr.ac.fractall.seguridad.servicio.PermisoDenegadoException;
import cr.ac.fractall.seguridad.servicio.RolInvitacionInvalidoException;
import cr.ac.fractall.seguridad.servicio.UltimoAdministradorException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Mensaje fijo y genérico para el fallback {@code P0001} (ver {@link #manejarErrorSqlNoCategorizado}).
     * Nunca se usa {@code sqlException.getMessage()} en la respuesta HTTP: el texto crudo de un
     * {@code RAISE EXCEPTION} sin SQLSTATE propio puede incluir identificadores internos (p. ej. el
     * {@code empresa_id} de otro tenant en {@code fn_validar_mismo_tenant}), el prefijo {@code
     * "ERROR:"} y contexto PL/pgSQL (ADR-002, ADR-008 -- fail-closed ante lo desconocido). El
     * mensaje real se registra solo en el log del servidor, nunca en la respuesta al cliente.
     */
    private static final String MENSAJE_GENERICO_SQL_NO_CATEGORIZADO =
            "La operación viola una regla de integridad del sistema";

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
     *
     * <p>{@code MiembroNoEncontradoException} se une a este grupo (Fase B, PR5b -- cambio de
     * rol/suspensión de membresías): el {@code usuarioId} objetivo no tiene fila
     * {@code usuario_empresa} en la empresa ACTUAL del actor, mismo criterio de "recurso
     * referenciado inexistente para este tenant" que las otras tres -- ver su javadoc.
     */
    @ExceptionHandler({ClienteNoEncontradoException.class, ProductoNoEncontradoException.class,
            ClienteExoneracionNoEncontradaException.class, MiembroNoEncontradoException.class})
    public ResponseEntity<MensajeResponse> manejarRecursoReferenciadoNoEncontrado(RuntimeException excepcion) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MensajeResponse(excepcion.getMessage()));
    }

    /**
     * Reglas de negocio de administración de membresías (Fase B, PR5b -- ver design.md,
     * decisión G): autogestión (aplicar la acción sobre la propia membresía) y degradar/suspender
     * al último {@code ADMIN_EMPRESA} activo de la empresa son conflictos de estado sobre una
     * operación por lo demás autorizada y bien formada -- mismo criterio que
     * {@link #manejarConflictoDeEstadoNotaCreditoDebito}.
     */
    @ExceptionHandler({AutoGestionNoPermitidaException.class, UltimoAdministradorException.class})
    public ResponseEntity<MensajeResponse> manejarConflictoDeAdministracionDeMembresias(RuntimeException excepcion) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new MensajeResponse(excepcion.getMessage()));
    }

    /**
     * Regla de negocio inválida detectada ANTES de persistir (Fase B, migrado desde
     * {@code FacturaController#crear} -- ver el javadoc de la clase).
     *
     * <p>{@code RolInvitacionInvalidoException} se une a este grupo (Fase B, invitación de
     * usuarios): {@code rolCodigo} en {@code POST /usuarios/invitar} es un valor de negocio
     * elegido por el llamador, no un id de recurso, mismo criterio que las excepciones de
     * exoneración/condición de venta de este grupo -- ver su javadoc.
     *
     * <p>{@code InvitacionInvalidaException} también se une (Fase B, PR3b -- aceptar
     * invitación): un token de invitación vencido/usado/revocado/inexistente es un dato de
     * entrada del llamador inválido para la operación solicitada, no un id de recurso ni un
     * conflicto de estado sobre un recurso ya identificado -- el token nunca llegó a
     * identificar nada persistente desde el punto de vista del llamador.
     *
     * <p>{@code FacturaNoCobrableException}/{@code MedioPagoInvalidoException} se unen (Release 3
     * / Fase C -- registro de cobros, ver diseño de {@code cobro_factura}): la primera cubre una
     * factura fuera del alcance de la operación (mismo criterio que las excepciones de exoneración
     * de este grupo -- ver su javadoc), y la segunda un {@code medio_pago} que no está en el
     * catálogo FE v4.4, un dato de entrada inválido del llamador.
     *
     * <p>{@code RangoFechasInvalidaException} se une (Release 3 / Fase D -- reporte de IVA
     * mensual, ver su diseño): {@code hasta}/{@code desde} inválidos en {@code GET /reportes/iva}
     * son un dato de entrada del llamador, mismo criterio que el resto de este grupo.
     */
    @ExceptionHandler({ExoneracionNoPerteneceAlClienteException.class,
            ExoneracionNoAplicableAFacturaElectronicaException.class,
            ExoneracionNoVigenteException.class,
            ExoneracionRequiereClienteException.class,
            CondicionVentaInvalidaException.class,
            RolInvitacionInvalidoException.class,
            InvitacionInvalidaException.class,
            FacturaNoCobrableException.class,
            MedioPagoInvalidoException.class,
            RangoFechasInvalidaException.class})
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
     *
     * <p>{@code MontoCobroExcedeSaldoException} se une (Release 3 / Fase C -- registro de cobros,
     * ver diseño de {@code cobro_factura}, decisión D5/G): mismo criterio -- un saldo ya consumido
     * (por cobros/NC previas) entra en conflicto con el cobro solicitado.
     */
    @ExceptionHandler({FacturaOrigenNoAceptadaException.class, MontoNotaCreditoExcedeOrigenException.class,
            MontoCobroExcedeSaldoException.class})
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
     * SQLSTATE propios de {@code fn_validar_exoneracion_vigente} (V10, redefinida por T-001 en
     * V25__exoneracion_sqlstate_propio.sql) -- ver el comentario de esa migración sobre la
     * convención de clase personalizada {@code VX}. Mapeados aquí, ANTES del fallback genérico
     * {@code P0001}, a un mensaje de dominio limpio equivalente al de
     * {@link ExoneracionNoVigenteException}/{@link ExoneracionNoAplicableAFacturaElectronicaException}
     * -- la decisión se toma por SQLSTATE, nunca por el contenido del mensaje crudo del motor
     * (ADR-046).
     */
    private static final Map<String, String> MENSAJE_POR_SQLSTATE_EXONERACION = Map.of(
            "VX001", "La exoneración referenciada no está vigente (inactiva o vencida)",
            "VX002", "La exoneración referenciada no está vigente (inactiva o vencida)",
            "VX003", "El tipo de exoneración referenciado es exclusivo de Nota de Crédito/Débito, "
                    + "no aplica a Factura Electrónica");

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
     * <p>T-001 agrega, ANTES del fallback {@code P0001}, los 3 SQLSTATE propios de
     * {@code fn_validar_exoneracion_vigente} ({@code VX001}/{@code VX002}/{@code VX003}): la misma
     * carrera de concurrencia (la exoneración se desactiva o vence entre la validación Java de
     * {@code FacturaService}/{@code LineaFacturaEnsamblador} y el flush) ahora responde 400 con un
     * mensaje limpio, igual que la validación Java no concurrente, en vez del 409 crudo que recibía
     * cualquier otro {@code P0001}.
     *
     * <p><b>Hallazgo de T-001:</b> {@code UncategorizedSQLException} NO es el único envoltorio
     * real -- era una suposición nunca verificada contra Postgres real (el test de V18 solo
     * fabricaba la excepción a mano). Un {@code RAISE EXCEPTION} disparado por Hibernate 7 dentro
     * de un {@code saveAndFlush} de un repositorio Spring Data JPA (la ruta real de {@code
     * FacturaService}/{@code NotaCreditoDebitoService}, confirmado con Postgres real por el IT
     * {@code ExoneracionTriggerSqlStateIT}) llega envuelto en {@link JpaSystemException} con un
     * {@link JDBCException} de Hibernate como causa -- NUNCA en {@code UncategorizedSQLException},
     * que solo aplica a JDBC plano (p. ej. vía {@code JdbcTemplate}). Sin
     * {@link #extraerSqlExceptionDeTrigger}, este handler nunca se activaba para la ruta real de
     * persistencia JPA y el error escalaba como 500 crudo pese a existir desde V18.
     *
     * <p>Para cualquier SQLSTATE que no sea uno de los 3 de exoneración ni {@code P0001}, o para
     * cualquier excepción de la que no se pueda desenvolver un {@link SQLException}, se re-lanza
     * para escalar sin manejar (mismo comportamiento que tenía ANTES de este handler).
     *
     * <p><b>Corrección de T-001 (revisión):</b> el fallback {@code P0001} responde siempre
     * {@link #MENSAJE_GENERICO_SQL_NO_CATEGORIZADO}, nunca {@code sqlException.getMessage()}. Al
     * desenvolver {@link JpaSystemException} para que el caso de exoneración funcionara contra la
     * ruta JPA real, este fallback empezó a activarse también para {@code fn_validar_mismo_tenant}
     * (V10/V18/V19/V23) -- que lanza su {@code RAISE EXCEPTION} sin SQLSTATE propio y SÍ incluye el
     * {@code empresa_id} de OTRO tenant en el texto. Antes de esta corrección esa ruta JPA escalaba
     * como 500 opaco (sin filtrar el UUID, pero tampoco categorizado); con el unwrap ya activo, un
     * mensaje crudo habría devuelto 409 filtrando el UUID del tenant ajeno -- regresión de
     * seguridad detectada en revisión, contraria a ADR-002/ADR-008 (fail-closed). El mensaje real
     * (con SQLSTATE) se registra solo en el log del servidor a nivel WARN; el cliente nunca lo ve.
     */
    @ExceptionHandler({UncategorizedSQLException.class, JpaSystemException.class})
    public ResponseEntity<MensajeResponse> manejarErrorSqlNoCategorizado(RuntimeException excepcion) {
        SQLException sqlException = extraerSqlExceptionDeTrigger(excepcion);
        if (sqlException == null) {
            throw excepcion;
        }
        String sqlState = sqlException.getSQLState();
        String mensajeExoneracion = MENSAJE_POR_SQLSTATE_EXONERACION.get(sqlState);
        if (mensajeExoneracion != null) {
            return ResponseEntity.badRequest().body(new MensajeResponse(mensajeExoneracion));
        }
        if (!"P0001".equals(sqlState)) {
            throw excepcion;
        }
        log.warn("RAISE EXCEPTION de trigger sin SQLSTATE propio (P0001): {}", sqlException.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new MensajeResponse(MENSAJE_GENERICO_SQL_NO_CATEGORIZADO));
    }

    /**
     * Desenvuelve el {@link SQLException} real de un {@code RAISE EXCEPTION} de trigger, que según
     * la ruta por la que llegue la excepción está envuelto de 2 formas distintas -- ver el javadoc
     * de {@link #manejarErrorSqlNoCategorizado}. {@code null} si no es ninguno de los 2 casos
     * conocidos (otro tipo de fallo de acceso a datos, no relacionado con un trigger).
     */
    private static SQLException extraerSqlExceptionDeTrigger(RuntimeException excepcion) {
        if (excepcion instanceof UncategorizedSQLException uncategorizedSqlException) {
            return uncategorizedSqlException.getSQLException();
        }
        if (excepcion.getCause() instanceof JDBCException jdbcException) {
            return jdbcException.getSQLException();
        }
        return null;
    }
}
