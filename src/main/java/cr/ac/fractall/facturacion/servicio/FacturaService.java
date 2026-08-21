package cr.ac.fractall.facturacion.servicio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;

import cr.ac.fractall.catalogo.modelo.Cliente;
import cr.ac.fractall.catalogo.modelo.TipoIdentificacion;
import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.catalogo.servicio.ClienteNoEncontradoException;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.facturacion.dto.CrearFacturaRequest;
import cr.ac.fractall.facturacion.dto.FacturaResumenResponse;
import cr.ac.fractall.facturacion.dto.FacturaResponse;
import cr.ac.fractall.facturacion.dto.LineaFacturaResponse;
import cr.ac.fractall.facturacion.dto.MedioPagoRequest;
import cr.ac.fractall.facturacion.dto.OtrosCargoRequest;
import cr.ac.fractall.facturacion.dto.ReferenciaRequest;
import cr.ac.fractall.facturacion.fe.TipoComprobantePerfil;
import cr.ac.fractall.shared.PaginaResponse;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.modelo.FacturaInformacionReferencia;
import cr.ac.fractall.facturacion.modelo.FacturaMedioPago;
import cr.ac.fractall.facturacion.modelo.FacturaOtrosCargos;
import cr.ac.fractall.facturacion.modelo.LineaCodigoComercial;
import cr.ac.fractall.facturacion.modelo.LineaDescuento;
import cr.ac.fractall.facturacion.modelo.LineaFactura;
import cr.ac.fractall.facturacion.modelo.ImpuestoLineaExoneracion;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaInformacionReferenciaRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaMedioPagoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaOtrosCargosRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.facturacion.repositorio.ImpuestoLineaExoneracionRepository;
import cr.ac.fractall.facturacion.repositorio.LineaCodigoComercialRepository;
import cr.ac.fractall.facturacion.repositorio.LineaDescuentoRepository;
import cr.ac.fractall.facturacion.repositorio.LineaFacturaRepository;
import cr.ac.fractall.hacienda.servicio.HaciendaApiService;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Orquesta la creación atómica de {@code factura} + {@code linea_factura}(s) +
 * {@code comprobante_electronico} (Fase 7, secciones 4.9 y 4.12-4.15 de
 * {@code arquitectura-facturacion-electronica-cr.md}) -- el criterio de salida de la Fase 7 exige
 * que esta orquestación COMPLETA sea segura ante concurrencia, no solo la reclamación aislada del
 * consecutivo: {@link #crear} corre en una única transacción que incluye el bloqueo pesimista de
 * {@code ConsecutivoService#siguienteConsecutivo}, así que un {@code ROLLBACK} en cualquier punto
 * posterior (por ejemplo, un producto que no se encuentra) revierte también el incremento del
 * consecutivo, sin dejar huecos.
 *
 * <p><b>Por qué las validaciones de exoneración se repiten en Java, no solo en los triggers de
 * V10:</b> un {@code RAISE EXCEPTION} de un trigger de Postgres NO se traduce a
 * {@code DataIntegrityViolationException} (pertenece a otra clase de SQLSTATE), así que
 * {@code GlobalExceptionHandler} no lo captura como 409 limpio -- escalaría como un error crudo
 * sin categorizar. Los triggers {@code fn_validar_exoneracion_vigente}/
 * {@code fn_validar_mismo_tenant} (V10) son defensa en profundidad a nivel de motor; las
 * excepciones de dominio limpias que ve el cliente HTTP las lanza este método, ANTES de intentar
 * persistir -- mismo principio ya corregido en revisiones de código de las Fases 5/6.
 *
 * <p><b>Release 2 / Fase B (ver diseño D-B/D-E):</b> el armado de líneas desde catálogo se delegó
 * a {@link LineaFacturaEnsamblador}, y la asignación de consecutivo+clave numérica+creación de
 * {@code ComprobanteElectronico} (además de {@link #reenviar}) se delegó a
 * {@link ComprobanteEmisionService} -- ambas extracciones type-parameterizadas, compartidas con
 * Nota de Crédito/Débito a partir de la Fase 3 de este release. {@link #crear} sigue siendo el
 * dueño de la transacción completa: {@code comprobanteEmisionService.registrarComprobante} corre
 * con {@code @Transactional(propagation = MANDATORY)} precisamente para seguir viviendo DENTRO de
 * esta misma transacción, preservando el invariante de rollback descrito arriba.
 */
@Slf4j
@Service
public class FacturaService {

    private static final String CONDICION_VENTA_DEFECTO = "01";
    private static final String CONDICION_VENTA_CREDITO = "02";
    private static final String MEDIO_PAGO_DEFECTO = "01";
    private static final String MONEDA_DEFECTO = "CRC";
    private static final String MONEDA_DOLAR = "USD";
    private static final BigDecimal TIPO_CAMBIO_DEFECTO = new BigDecimal("1.00000");

    private static final int ESCALA_MONETARIA = 5;

    private final ClienteRepository clienteRepository;
    private final EmpresaRepository empresaRepository;
    private final FacturaRepository facturaRepository;
    private final LineaFacturaRepository lineaFacturaRepository;
    private final ComprobanteElectronicoRepository comprobanteElectronicoRepository;
    private final LineaCodigoComercialRepository lineaCodigoComercialRepository;
    private final LineaDescuentoRepository lineaDescuentoRepository;
    private final ImpuestoLineaExoneracionRepository impuestoLineaExoneracionRepository;
    private final FacturaOtrosCargosRepository facturaOtrosCargosRepository;
    private final FacturaInformacionReferenciaRepository facturaInformacionReferenciaRepository;
    private final FacturaMedioPagoRepository facturaMedioPagoRepository;
    private final LineaFacturaEnsamblador lineaFacturaEnsamblador;
    private final ComprobanteEmisionService comprobanteEmisionService;
    private final HaciendaApiService haciendaApiService;

    public FacturaService(
            ClienteRepository clienteRepository,
            EmpresaRepository empresaRepository,
            FacturaRepository facturaRepository,
            LineaFacturaRepository lineaFacturaRepository,
            ComprobanteElectronicoRepository comprobanteElectronicoRepository,
            LineaCodigoComercialRepository lineaCodigoComercialRepository,
            LineaDescuentoRepository lineaDescuentoRepository,
            ImpuestoLineaExoneracionRepository impuestoLineaExoneracionRepository,
            FacturaOtrosCargosRepository facturaOtrosCargosRepository,
            FacturaInformacionReferenciaRepository facturaInformacionReferenciaRepository,
            FacturaMedioPagoRepository facturaMedioPagoRepository,
            LineaFacturaEnsamblador lineaFacturaEnsamblador,
            ComprobanteEmisionService comprobanteEmisionService,
            HaciendaApiService haciendaApiService) {
        this.clienteRepository = clienteRepository;
        this.empresaRepository = empresaRepository;
        this.facturaRepository = facturaRepository;
        this.lineaFacturaRepository = lineaFacturaRepository;
        this.comprobanteElectronicoRepository = comprobanteElectronicoRepository;
        this.lineaCodigoComercialRepository = lineaCodigoComercialRepository;
        this.lineaDescuentoRepository = lineaDescuentoRepository;
        this.impuestoLineaExoneracionRepository = impuestoLineaExoneracionRepository;
        this.facturaOtrosCargosRepository = facturaOtrosCargosRepository;
        this.facturaInformacionReferenciaRepository = facturaInformacionReferenciaRepository;
        this.facturaMedioPagoRepository = facturaMedioPagoRepository;
        this.lineaFacturaEnsamblador = lineaFacturaEnsamblador;
        this.comprobanteEmisionService = comprobanteEmisionService;
        this.haciendaApiService = haciendaApiService;
    }

    // =========================================================================
    // Read operations — listar + obtener (FR-1, FR-2)
    // =========================================================================

    /**
     * Lista facturas del tenant activo con paginación keyset y filtros opcionales. Req: FR-1,
     * NFR-1 (tenant explícito via {@code TenantContext.get()} — la query nativa no tiene
     * {@code @TenantId} automático), NFR-3 (única query JOIN sin N+1).
     *
     * <p>Patrón limit+1: se solicitan {@code limit+1} filas al repositorio para detectar si hay
     * más páginas sin un COUNT adicional; la lista de resultado se trunca a {@code limit} ítems.
     *
     * <p>La query usa {@code nativeQuery = true} porque la expresión JPQL
     * {@code (:param IS NULL OR ...)} con parámetros de fecha opcionales causa
     * {@code PSQLException: could not determine data type of parameter} en PostgreSQL cuando el
     * valor es null — el driver no puede inferir el tipo del parámetro sin contexto adicional.
     */
    @Transactional(readOnly = true)
    public PaginaResponse<FacturaResumenResponse> listar(
            UUID cursor, UUID clienteId, LocalDate desde, LocalDate hasta, String estado, int limit) {
        UUID empresaId = TenantContext.get();
        List<Object[]> filas = facturaRepository.buscarNativo(
                empresaId,
                cursor != null ? cursor.toString() : null,
                clienteId != null ? clienteId.toString() : null,
                desde != null ? desde.toString() : null,
                hasta != null ? hasta.toString() : null,
                estado,
                limit + 1);
        List<FacturaResumenResponse> resumen = filas.stream()
                .map(FacturaService::mapearFila)
                .toList();
        boolean hayMas = resumen.size() > limit;
        List<FacturaResumenResponse> pagina = hayMas
                ? resumen.subList(0, limit).stream().toList()
                : resumen;
        UUID nextCursor = hayMas ? pagina.get(pagina.size() - 1).id() : null;
        return new PaginaResponse<>(pagina, nextCursor);
    }

    /**
     * Mapea una fila {@code Object[]} del resultado de la query nativa a {@link FacturaResumenResponse}.
     * Orden de columnas: id, consecutivo, cliente_id, nombre, ambiente_hacienda, moneda, total,
     * estado, ultimo_resultado_consulta, fecha_emision.
     */
    private static FacturaResumenResponse mapearFila(Object[] row) {
        UUID id = row[0] instanceof UUID u ? u : UUID.fromString(row[0].toString());
        String consecutivo = row[1] != null ? row[1].toString() : null;
        UUID clienteId = row[2] != null
                ? (row[2] instanceof UUID u ? u : UUID.fromString(row[2].toString()))
                : null;
        String clienteNombre = row[3] != null ? row[3].toString() : null;
        String ambienteHacienda = row[4] != null ? row[4].toString() : null;
        String moneda = row[5] != null ? row[5].toString() : null;
        BigDecimal total = row[6] != null ? new BigDecimal(row[6].toString()) : null;
        String estado = row[7] != null ? row[7].toString() : null;
        String ultimoResultadoConsulta = row[8] != null ? row[8].toString() : null;
        LocalDate fechaEmision = null;
        if (row[9] != null) {
            Object raw = row[9];
            if (raw instanceof java.sql.Timestamp ts) {
                fechaEmision = ts.toLocalDateTime().toLocalDate();
            } else if (raw instanceof java.sql.Date d) {
                fechaEmision = d.toLocalDate();
            } else if (raw instanceof LocalDate ld) {
                fechaEmision = ld;
            } else {
                fechaEmision = LocalDate.parse(raw.toString().substring(0, 10));
            }
        }
        return new FacturaResumenResponse(id, consecutivo, clienteId, clienteNombre,
                ambienteHacienda, moneda, total, estado, ultimoResultadoConsulta, fechaEmision);
    }

    /**
     * Carga el detalle completo de una factura por id. Req: FR-2.
     *
     * <p>El filtro {@code @TenantId} de {@code findById} hace que un id de otro tenant resuelva
     * vacío — tratado igual que "no encontrado" para no revelar existencia de recursos cruzados.
     *
     * <p>Si la factura existe pero no tiene comprobante, se lanza {@link IllegalStateException}
     * (HTTP 500): factura y comprobante se crean en la misma transacción atómica (sección 4.9), su
     * ausencia indica una violación de invariante de datos, no un estado de negocio válido. Un 404
     * aquí mentiría al frontend y ocultaría el bug de integridad real.
     */
    @Transactional(readOnly = true)
    public FacturaResponse obtener(UUID id) {
        Factura factura = facturaRepository.findById(id)
                .orElseThrow(() -> new FacturaNoEncontradaException(id));

        ComprobanteElectronico comprobante = comprobanteElectronicoRepository.findByFacturaId(id)
                .orElseThrow(() -> {
                    log.error("Integridad violada: factura {} existe sin comprobante_electronico", id);
                    return new IllegalStateException(
                            "Integridad violada: factura " + id + " no tiene comprobante_electronico");
                });

        List<LineaFactura> lineas =
                lineaFacturaRepository.findByFacturaIdOrderByNumeroLinea(factura.getId());

        List<LineaFacturaResponse> lineasResponse = lineas.stream().map(linea -> {
            var codigos = lineaCodigoComercialRepository.findByLineaIdOrderByOrden(linea.getId());
            var descuentos = lineaDescuentoRepository.findByLineaIdOrderByOrden(linea.getId());
            var exo = impuestoLineaExoneracionRepository.findByLineaId(linea.getId()).orElse(null);
            return LineaFacturaResponse.desde(linea, codigos, descuentos, exo);
        }).toList();

        var otrosCargos = facturaOtrosCargosRepository.findByFacturaIdOrderByOrden(factura.getId());
        var referencias = facturaInformacionReferenciaRepository.findByFacturaIdOrderByOrden(factura.getId());
        var mediosPago = facturaMedioPagoRepository.findByFacturaIdOrderByOrden(factura.getId());

        String clienteNombre = facturaRepository.findClienteNombreByFacturaId(id, factura.getEmpresaId())
                .orElse(null);

        return FacturaResponse.desde(factura, comprobante, lineasResponse, otrosCargos, referencias, mediosPago, clienteNombre);
    }

    /**
     * Reenvía a Hacienda un comprobante atascado en un estado terminal/inalcanzable (FIRMADO,
     * RECHAZADO, ERROR). Delegado a {@link ComprobanteEmisionService#reenviar} (Fase B, ver el
     * javadoc de la clase) -- {@code FacturaService} sigue siendo el dueño de la proyección
     * {@link FacturaResponse} devuelta al cliente HTTP, vía {@link #obtener}.
     *
     * @throws FacturaNoEncontradaException si no existe comprobante para {@code facturaId}
     * @throws ComprobanteNoReenviableException si el estado no es FIRMADO/RECHAZADO/ERROR, o si
     *     {@code xmlComprobanteReferencia} es null
     */
    public FacturaResponse reenviar(UUID facturaId) {
        comprobanteEmisionService.reenviar(facturaId);
        return obtener(facturaId);
    }

    @Transactional
    public FacturaResponse crear(CrearFacturaRequest request) {
        UUID empresaId = TenantContext.get();

        // findById ya filtra por @TenantId -- un id de otro tenant resuelve vacío, tratado igual
        // que "no encontrado" (mismo principio de ClienteExoneracionService/ProductoService).
        Cliente cliente = clienteRepository.findById(request.clienteId())
                .orElseThrow(() -> new ClienteNoEncontradoException(request.clienteId()));

        Empresa empresa = empresaRepository.findById(empresaId)
                .orElseThrow(() -> new IllegalStateException("Empresa de contexto no encontrada: " + empresaId));

        // Validate OtrosCargos IdentificacionTercero.tipo early (before any persistence)
        validarIdentificacionesTerceros(request.otrosCargos());

        ZonedDateTime ahoraUtc = ZonedDateTime.now(ZoneOffset.UTC);
        LocalDateTime ahora = ahoraUtc.toLocalDateTime();

        // Armado de líneas desde catálogo delegado a LineaFacturaEnsamblador (Fase B, ver diseño
        // D-E) -- no persiste nada todavía, las líneas aún no tienen facturaId.
        LineaFacturaEnsamblador.LineasEnsambladas ensambladas = lineaFacturaEnsamblador.ensamblar(
                request.lineas(), cliente, TipoComprobantePerfil.FACTURA_ELECTRONICA);
        List<LineaFactura> lineas = ensambladas.lineas();
        BigDecimal subtotalFactura = ensambladas.subtotal();
        BigDecimal totalImpuestoFactura = ensambladas.totalImpuesto();

        BigDecimal totalOtrosCargos = BigDecimal.ZERO;
        if (request.otrosCargos() != null) {
            for (OtrosCargoRequest cargo : request.otrosCargos()) {
                if (cargo.montoCargo() != null) {
                    totalOtrosCargos = totalOtrosCargos.add(cargo.montoCargo());
                }
            }
        }
        BigDecimal ivaDevuelto = request.totalIvaDevuelto() != null ? request.totalIvaDevuelto() : BigDecimal.ZERO;
        BigDecimal totalFactura = subtotalFactura.add(totalImpuestoFactura)
                .subtract(ivaDevuelto)
                .add(totalOtrosCargos)
                .setScale(ESCALA_MONETARIA, RoundingMode.HALF_UP);

        String condicionVenta = request.condicionVenta() != null ? request.condicionVenta() : CONDICION_VENTA_DEFECTO;
        validarCondicionVenta(condicionVenta, request.plazoCredito());

        // Resolve legacy medioPago for backward compat (single string field on factura table)
        String legacyMedioPago = resolverLegacyMedioPago(request);

        Factura factura = new Factura();
        factura.setClienteId(cliente.getId());
        factura.setCondicionVenta(condicionVenta);
        factura.setPlazoCredito(request.plazoCredito());
        factura.setMedioPago(legacyMedioPago);
        factura.setMoneda(request.moneda() != null ? request.moneda() : MONEDA_DEFECTO);
        factura.setTipoCambio(resolverTipoCambio(request));
        factura.setSubtotal(subtotalFactura);
        factura.setTotalImpuesto(totalImpuestoFactura);
        factura.setTotal(totalFactura);
        factura.setCreadoPor(resolverUsuarioAutenticado());
        factura.setCreateDate(ahora);
        factura.setUpdateDate(ahora);

        // New V11 factura scalar fields
        factura.setCondicionVentaOtros(request.condicionVentaOtros());
        factura.setCodigoActividadReceptor(request.codigoActividadReceptor());
        factura.setTotalIvaDevuelto(request.totalIvaDevuelto() != null ? request.totalIvaDevuelto() : BigDecimal.ZERO);

        facturaRepository.saveAndFlush(factura);

        // Persistencia de líneas + hijos delegada a LineaFacturaEnsamblador (Fase B, ver diseño
        // D-E) -- fija facturaId en cada línea (recién disponible) y persiste sus hijos.
        lineaFacturaEnsamblador.persistir(factura.getId(), ensambladas);

        // Reclamo del consecutivo + clave numérica + creación de ComprobanteElectronico delegado a
        // ComprobanteEmisionService (Fase B, ver diseño D-B) -- MANDATORY: sigue corriendo DENTRO
        // de esta misma transacción, preservando el invariante de rollback de la Fase 7 (ver el
        // javadoc de la clase).
        ComprobanteElectronico comprobante = comprobanteEmisionService.registrarComprobante(
                factura, TipoComprobantePerfil.FACTURA_ELECTRONICA, empresa, ahoraUtc);

        // Persist factura-level children (after factura has id)
        persistirOtrosCargos(factura.getId(), request.otrosCargos());
        persistirInformacionReferencia(factura.getId(), request.informacionReferencia());
        persistirMediosPago(factura.getId(), request.mediosPago(), legacyMedioPago, totalFactura);

        // Load children for response
        List<FacturaOtrosCargos> otrosCargosGuardados =
                facturaOtrosCargosRepository.findByFacturaIdOrderByOrden(factura.getId());
        List<FacturaInformacionReferencia> referenciasGuardadas =
                facturaInformacionReferenciaRepository.findByFacturaIdOrderByOrden(factura.getId());
        List<FacturaMedioPago> mediosPagoGuardados =
                facturaMedioPagoRepository.findByFacturaIdOrderByOrden(factura.getId());

        List<LineaFacturaResponse> lineasResponse = lineas.stream().map(linea -> {
            List<LineaCodigoComercial> codigos =
                    lineaCodigoComercialRepository.findByLineaIdOrderByOrden(linea.getId());
            List<LineaDescuento> descuentosLinea =
                    lineaDescuentoRepository.findByLineaIdOrderByOrden(linea.getId());
            ImpuestoLineaExoneracion exo =
                    impuestoLineaExoneracionRepository.findByLineaId(linea.getId()).orElse(null);
            return LineaFacturaResponse.desde(linea, codigos, descuentosLinea, exo);
        }).toList();
        return FacturaResponse.desde(factura, comprobante, lineasResponse,
                otrosCargosGuardados, referenciasGuardadas, mediosPagoGuardados, cliente.getNombre());
    }

    // =========================================================================
    // Factura-level child persistence
    // =========================================================================

    private void persistirOtrosCargos(UUID facturaId, List<OtrosCargoRequest> cargos) {
        if (cargos == null || cargos.isEmpty()) return;
        short orden = 1;
        for (OtrosCargoRequest req : cargos) {
            FacturaOtrosCargos entidad = new FacturaOtrosCargos();
            entidad.setFacturaId(facturaId);
            entidad.setOrden(orden++);
            entidad.setTipoDocumentoOc(req.tipoDocumentoOc());
            entidad.setTipoDocumentoOtros(req.tipoDocumentoOtros());
            if (req.identificacionTercero() != null) {
                entidad.setIdentidadTipo(req.identificacionTercero().tipo());
                entidad.setIdentidadNumero(req.identificacionTercero().numero());
            }
            entidad.setNombreTercero(req.nombreTercero());
            entidad.setDetalle(req.detalle());
            entidad.setPorcentajeOc(req.porcentajeOc());
            entidad.setMontoCargo(req.montoCargo());
            facturaOtrosCargosRepository.save(entidad);
        }
    }

    private void persistirInformacionReferencia(UUID facturaId, List<ReferenciaRequest> referencias) {
        if (referencias == null || referencias.isEmpty()) return;
        short orden = 1;
        for (ReferenciaRequest req : referencias) {
            FacturaInformacionReferencia entidad = new FacturaInformacionReferencia();
            entidad.setFacturaId(facturaId);
            entidad.setOrden(orden++);
            entidad.setTipoDocIr(req.tipoDocIr());
            entidad.setTipoDocRefOtro(req.tipoDocRefOtro());
            entidad.setNumero(req.numero());
            entidad.setFechaEmisionIr(req.fechaEmisionIr() != null ? req.fechaEmisionIr().atStartOfDay() : null);
            entidad.setCodigo(req.codigo());
            entidad.setCodigoReferenciaOtro(req.codigoReferenciaOtro());
            entidad.setRazon(req.razon());
            facturaInformacionReferenciaRepository.save(entidad);
        }
    }

    private void persistirMediosPago(UUID facturaId, List<MedioPagoRequest> mediosPago,
            String legacyMedioPago, BigDecimal totalFactura) {
        if (mediosPago != null && !mediosPago.isEmpty()) {
            short orden = 1;
            for (MedioPagoRequest req : mediosPago) {
                FacturaMedioPago entidad = new FacturaMedioPago();
                entidad.setFacturaId(facturaId);
                entidad.setOrden(orden++);
                entidad.setTipoMedioPago(req.tipoMedioPago());
                entidad.setMedioPagoOtros(req.medioPagoOtros());
                entidad.setTotalMedioPago(req.totalMedioPago());
                facturaMedioPagoRepository.save(entidad);
            }
        } else {
            // Legacy fallback: synthesize single payment from legacy medioPago + total
            FacturaMedioPago entidad = new FacturaMedioPago();
            entidad.setFacturaId(facturaId);
            entidad.setOrden((short) 1);
            entidad.setTipoMedioPago(legacyMedioPago != null ? legacyMedioPago : MEDIO_PAGO_DEFECTO);
            entidad.setMedioPagoOtros(null);
            entidad.setTotalMedioPago(totalFactura);
            facturaMedioPagoRepository.save(entidad);
        }
    }

    // =========================================================================
    // Validation helpers
    // =========================================================================

    private void validarIdentificacionesTerceros(List<OtrosCargoRequest> cargos) {
        if (cargos == null || cargos.isEmpty()) return;
        for (OtrosCargoRequest cargo : cargos) {
            if (cargo.identificacionTercero() != null) {
                String tipo = cargo.identificacionTercero().tipo();
                try {
                    TipoIdentificacion.fromCodigo(tipo);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "Tipo de identificación inválido en OtrosCargo.identificacionTercero: " + tipo);
                }
            }
        }
    }

    /**
     * Resuelve {@code factura.tipoCambio}: valor explícito del cliente si viene; si no viene y
     * {@code moneda='USD'}, se autocompleta con el tipo de cambio de VENTA del día publicado por
     * Hacienda (no compra -- {@code venta} es el que se usa para convertir a colones, ver el
     * javadoc de {@code HaciendaApiService#consultarTipoCambioDolar}); en cualquier otro caso
     * (CRC u omitida), el default {@code 1.00000}. El caso "moneda distinta de CRC/USD sin
     * tipoCambio" ya lo bloquea {@code CrearFacturaRequest#isTipoCambioValido} antes de llegar
     * acá (Bean Validation corre antes que el controlador invoque este servicio).
     */
    private BigDecimal resolverTipoCambio(CrearFacturaRequest request) {
        if (request.tipoCambio() != null) {
            return request.tipoCambio();
        }
        if (MONEDA_DOLAR.equals(request.moneda())) {
            return haciendaApiService.consultarTipoCambioDolar().getVenta().getValor();
        }
        return TIPO_CAMBIO_DEFECTO;
    }

    private String resolverLegacyMedioPago(CrearFacturaRequest request) {
        if (request.mediosPago() != null && !request.mediosPago().isEmpty()) {
            return request.mediosPago().get(0).tipoMedioPago();
        }
        return request.medioPago() != null ? request.medioPago() : MEDIO_PAGO_DEFECTO;
    }

    /**
     * Mismo requisito que ya exige el {@code CHECK} de {@code factura} en
     * {@code V4__catalogo_y_facturacion.sql} ({@code condicion_venta <> '02' OR plazo_credito
     * IS NOT NULL}), validado aquí en Java ANTES de {@code saveAndFlush} -- mismo principio ya
     * aplicado en {@code ClienteService#validarUbicacion}: una violación de ese {@code CHECK} sin
     * validar antes llegaría como {@code DataIntegrityViolationException}, y
     * {@code GlobalExceptionHandler} la traduciría a un 409 genérico de "restricción de
     * unicidad" -- mensaje incorrecto para lo que en realidad es una regla de negocio, no un
     * duplicado.
     */
    private void validarCondicionVenta(String condicionVenta, Integer plazoCredito) {
        if (CONDICION_VENTA_CREDITO.equals(condicionVenta) && plazoCredito == null) {
            throw new CondicionVentaInvalidaException(
                    "plazoCredito es obligatorio cuando condicionVenta = '02' (crédito)");
        }
    }

    /**
     * Lee el usuario ya autenticado por {@code JwtAuthenticationFilter} -- mismo patrón que
     * {@code AuthController#usuarioIdAutenticado}, pero lanzando en vez de devolver
     * {@code Optional}: llegar aquí sin autenticar sería un bug de configuración de seguridad
     * (esta ruta ya vive detrás de {@code anyRequest().authenticated()}), no un caso de negocio
     * esperado.
     */
    private UUID resolverUsuarioAutenticado() {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacion != null && autenticacion.isAuthenticated()
                && autenticacion.getPrincipal() instanceof UUID usuarioId) {
            return usuarioId;
        }
        throw new IllegalStateException("No hay usuario autenticado en el contexto de seguridad");
    }
}
