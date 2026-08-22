package cr.ac.fractall.facturacion.servicio.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import cr.ac.fractall.catalogo.modelo.Cliente;
import cr.ac.fractall.catalogo.modelo.ClienteExoneracion;
import cr.ac.fractall.catalogo.modelo.Producto;
import cr.ac.fractall.catalogo.repositorio.ClienteExoneracionRepository;
import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.catalogo.repositorio.ProductoRepository;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.facturacion.fe.TipoComprobantePerfil;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.modelo.FacturaInformacionReferencia;
import cr.ac.fractall.facturacion.modelo.FacturaMedioPago;
import cr.ac.fractall.facturacion.modelo.FacturaOtrosCargos;
import cr.ac.fractall.facturacion.modelo.ImpuestoLineaExoneracion;
import cr.ac.fractall.facturacion.modelo.LineaCodigoComercial;
import cr.ac.fractall.facturacion.modelo.LineaDescuento;
import cr.ac.fractall.facturacion.modelo.LineaFactura;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaInformacionReferenciaRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaMedioPagoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaOtrosCargosRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.facturacion.repositorio.ImpuestoLineaExoneracionRepository;
import cr.ac.fractall.facturacion.repositorio.LineaCodigoComercialRepository;
import cr.ac.fractall.facturacion.repositorio.LineaDescuentoRepository;
import cr.ac.fractall.facturacion.repositorio.LineaFacturaRepository;
import cr.ac.fractall.facturacion.servicio.ComprobanteElectronicoNoEncontradoException;
import cr.ac.fractall.facturacion.servicio.ComprobanteSinReferenciaObligatoriaException;
import cr.ac.fractall.facturacion.servicio.EmpresaSinCorreoElectronicoException;
import cr.ac.fractall.facturacion.servicio.XmlFacturaGeneratorService;
import cr.ac.fractall.facturacion.servicio.XmlFacturaXsdValidator;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementación de {@link XmlFacturaGeneratorService} -- ver su javadoc para el alcance
 * (solo Factura Electrónica, sin las variantes Tiquete/NotaCredito/NotaDebito) y para la nota
 * sobre por qué este servicio resuelve entidades vía repositorio en vez de navegar relaciones
 * JPA como el original.
 *
 * <p><b>Diferencias deliberadas frente al original</b> (todas ya decididas antes de escribir este
 * archivo, ver el reporte de la sub-tarea 1 en engram, topic
 * {@code project/facturacion-cr-fase8-progress}):
 *
 * <ul>
 *   <li>{@code NumeroConsecutivo} viene directo de {@link ComprobanteElectronico#getConsecutivo()}
 *       -- el original lo derivaba recortando la {@code Clave} de 50 dígitos porque no tenía un
 *       campo propio para eso.
 *   <li>El fallback de dirección del Emisor va directo de {@code otrasSenas} a
 *       {@code "Sin especificar"}: {@link Empresa} de este proyecto no tiene un campo
 *       {@code direccion} intermedio como el original.
 *   <li>{@code Producto} no tiene un {@code ArticuloMaestro} maestro con fallback -- sus campos de
 *       impuesto/CABYS se leen directo, y para los campos relevantes a impuesto (gravado,
 *       porcentaje, CABYS aplicado) se prefiere el snapshot ya congelado en {@code LineaFactura}
 *       (columnas {@code *_aplicado} de la Fase 7) sobre releer {@code Producto} en vivo -- mismo
 *       principio de "snapshot congelado, nunca recalculado retroactivamente" documentado en la
 *       sección 4.14 del documento de arquitectura.
 * </ul>
 *
 * <p><b>Bloque {@code <Exoneracion>} (inline v11):</b> si la línea tiene un registro en
 * {@code impuesto_linea_exoneracion} (inline path creado por V11), se emite desde ese registro.
 * Si tiene {@code exoneracion_id} (legacy path, {@code ClienteExoneracion}), se emite desde ahí.
 * Las dos rutas son mutuamente excluyentes por construcción en {@code FacturaService#crear}.
 *
 * <p><b>{@code CodigoActividadReceptor}:</b> se emite a nivel del documento ROOT inmediatamente
 * después de {@code <CodigoActividadEmisor>} (decisión arquitectónica explícita, no dentro del
 * bloque {@code <Receptor>}).
 */
@Service
@Slf4j
public class XmlFacturaGeneratorServiceImpl implements XmlFacturaGeneratorService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final ZoneId CR_ZONE = ZoneId.of("America/Costa_Rica");
    private static final BigDecimal CIEN = new BigDecimal("100");
    private static final int ESCALA_MONETARIA = 5;

    private final ComprobanteElectronicoRepository comprobanteElectronicoRepository;
    private final FacturaRepository facturaRepository;
    private final LineaFacturaRepository lineaFacturaRepository;
    private final EmpresaRepository empresaRepository;
    private final ClienteRepository clienteRepository;
    private final ProductoRepository productoRepository;
    private final ClienteExoneracionRepository clienteExoneracionRepository;
    private final XmlFacturaXsdValidator xmlFacturaXsdValidator;
    // V11 child-table repositories
    private final LineaCodigoComercialRepository lineaCodigoComercialRepository;
    private final LineaDescuentoRepository lineaDescuentoRepository;
    private final ImpuestoLineaExoneracionRepository impuestoLineaExoneracionRepository;
    private final FacturaOtrosCargosRepository facturaOtrosCargosRepository;
    private final FacturaInformacionReferenciaRepository facturaInformacionReferenciaRepository;
    private final FacturaMedioPagoRepository facturaMedioPagoRepository;

    public XmlFacturaGeneratorServiceImpl(
            ComprobanteElectronicoRepository comprobanteElectronicoRepository,
            FacturaRepository facturaRepository,
            LineaFacturaRepository lineaFacturaRepository,
            EmpresaRepository empresaRepository,
            ClienteRepository clienteRepository,
            ProductoRepository productoRepository,
            ClienteExoneracionRepository clienteExoneracionRepository,
            XmlFacturaXsdValidator xmlFacturaXsdValidator,
            LineaCodigoComercialRepository lineaCodigoComercialRepository,
            LineaDescuentoRepository lineaDescuentoRepository,
            ImpuestoLineaExoneracionRepository impuestoLineaExoneracionRepository,
            FacturaOtrosCargosRepository facturaOtrosCargosRepository,
            FacturaInformacionReferenciaRepository facturaInformacionReferenciaRepository,
            FacturaMedioPagoRepository facturaMedioPagoRepository) {
        this.comprobanteElectronicoRepository = comprobanteElectronicoRepository;
        this.facturaRepository = facturaRepository;
        this.lineaFacturaRepository = lineaFacturaRepository;
        this.empresaRepository = empresaRepository;
        this.clienteRepository = clienteRepository;
        this.productoRepository = productoRepository;
        this.clienteExoneracionRepository = clienteExoneracionRepository;
        this.xmlFacturaXsdValidator = xmlFacturaXsdValidator;
        this.lineaCodigoComercialRepository = lineaCodigoComercialRepository;
        this.lineaDescuentoRepository = lineaDescuentoRepository;
        this.impuestoLineaExoneracionRepository = impuestoLineaExoneracionRepository;
        this.facturaOtrosCargosRepository = facturaOtrosCargosRepository;
        this.facturaInformacionReferenciaRepository = facturaInformacionReferenciaRepository;
        this.facturaMedioPagoRepository = facturaMedioPagoRepository;
    }

    @Override
    public String generarXmlFactura(UUID comprobanteId) {
        log.info("Generando XML de factura electrónica para comprobante: {}", comprobanteId);

        // findById ya filtra por @TenantId -- un id de otro tenant resuelve vacío, tratado igual
        // que "no encontrado" (mismo principio que el resto de los servicios de este proyecto,
        // ver el javadoc de FacturaService#crear).
        ComprobanteElectronico comprobante = comprobanteElectronicoRepository.findById(comprobanteId)
                .orElseThrow(() -> new ComprobanteElectronicoNoEncontradoException(comprobanteId));

        // Release 2 / Fase B: el perfil (raíz, namespace, XSD, política de emisión) se deriva del
        // comprobante ya cargado -- la firma de este método NO cambia, ver el diseño de Fase B,
        // decisión D-C. Type-01 (Factura) sigue produciendo exactamente el mismo XML que antes.
        TipoComprobantePerfil perfil = TipoComprobantePerfil.fromCodigo(comprobante.getTipoComprobante());

        // A partir de aquí las FKs (facturaId, clienteId, productoId) son invariantes internas
        // resueltas desde un comprobante ya validado como del tenant actual -- una FK rota acá
        // es un bug de integridad de datos, no una entrada de usuario inválida, así que se lanza
        // IllegalStateException (mismo principio que FacturaService#crear con la Empresa de
        // contexto), no una excepción de dominio 404.
        Factura factura = facturaRepository.findById(comprobante.getFacturaId())
                .orElseThrow(() -> new IllegalStateException(
                        "Factura no encontrada para comprobante " + comprobanteId + ": " + comprobante.getFacturaId()));

        Empresa empresa = empresaRepository.findById(comprobante.getEmpresaId())
                .orElseThrow(() -> new IllegalStateException("Empresa no encontrada: " + comprobante.getEmpresaId()));

        // Release 2 / Fase C, hallazgo real de integración: factura.getClienteId() puede ser null
        // (Tiquete sin receptor identificado, ver TipoComprobantePerfil#receptorObligatorio ==
        // false para TIQUETE) -- el lookup solo corre cuando hay un cliente que resolver. Antes de
        // este guard, clienteRepository.findById(null) lanzaba InvalidDataAccessApiUsageException
        // incondicionalmente, sin importar el perfil (ver el guard de perfil.isReceptorObligatorio()
        // más abajo, que ya anticipaba este caso pero no evitaba el lookup en sí).
        Cliente cliente = null;
        if (factura.getClienteId() != null) {
            cliente = clienteRepository.findById(factura.getClienteId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Cliente no encontrado para factura " + factura.getId() + ": " + factura.getClienteId()));
        }

        List<LineaFactura> lineas = lineaFacturaRepository.findByFacturaIdOrderByNumeroLinea(factura.getId());
        List<LineaContexto> contextos = lineas.stream()
                .map(linea -> resolverContexto(linea))
                .toList();

        // Cargar hijos de factura
        FacturaContexto facturaCtx = new FacturaContexto(
                facturaOtrosCargosRepository.findByFacturaIdOrderByOrden(factura.getId()),
                facturaInformacionReferenciaRepository.findByFacturaIdOrderByOrden(factura.getId()),
                facturaMedioPagoRepository.findByFacturaIdOrderByOrden(factura.getId()));

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        xml.append("<").append(perfil.getElementoRaiz());
        xml.append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"");
        xml.append(" xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"");
        xml.append(" xmlns=\"").append(perfil.namespace()).append("\"");
        xml.append(" xsi:schemaLocation=\"").append(perfil.namespace()).append(" ").append(perfil.namespace()).append(".xsd\"");
        xml.append(">");

        xml.append("<Clave>").append(comprobante.getClaveNumerica()).append("</Clave>");
        xml.append("<ProveedorSistemas>").append(obtenerProveedorSistemas(empresa)).append("</ProveedorSistemas>");

        // CodigoActividadEmisor: obligatorio (con fallback "620100") solo para los perfiles donde
        // el XSD real no lo marca minOccurs="0" (Factura/Tiquete); para ND/NC se emite solo si la
        // empresa tiene un código real, sin sintetizar un fallback que el XSD no exige.
        if (perfil.isCodigoActividadEmisorObligatorio()) {
            xml.append("<CodigoActividadEmisor>").append(obtenerActividadEconomica(empresa)).append("</CodigoActividadEmisor>");
        } else if (empresa.getCodigoActividad() != null && !empresa.getCodigoActividad().isBlank()) {
            xml.append("<CodigoActividadEmisor>").append(empresa.getCodigoActividad()).append("</CodigoActividadEmisor>");
        }

        // T-16: CodigoActividadReceptor at ROOT level (after CodigoActividadEmisor) -- ausente del
        // XSD de Tiquete (perfil.isCodigoActividadReceptorSoportado() == false para ese perfil).
        if (perfil.isCodigoActividadReceptorSoportado()
                && factura.getCodigoActividadReceptor() != null && !factura.getCodigoActividadReceptor().isBlank()) {
            xml.append("<CodigoActividadReceptor>")
                    .append(factura.getCodigoActividadReceptor())
                    .append("</CodigoActividadReceptor>");
        }

        // NumeroConsecutivo ya vive como campo propio (Fase 7) -- ver el javadoc de la clase.
        xml.append("<NumeroConsecutivo>").append(comprobante.getConsecutivo()).append("</NumeroConsecutivo>");
        xml.append("<FechaEmision>").append(formatearFecha(comprobante.getFechaEmision())).append("</FechaEmision>");

        agregarEmisor(xml, empresa);
        // Receptor: se incluye si y solo si HAY un cliente resuelto para este documento -- no si
        // perfil.isReceptorObligatorio() (política de tipo, no dato real de la instancia). Bug de
        // code review sobre Fase C: para tipo 01/02/03, cliente siempre es no-null (obligatorio en
        // esos DTOs), así que este gate se comporta idéntico a antes -- el único perfil que cambia
        // es TIQUETE, donde antes se omitía SIEMPRE (aunque hubiera cliente) porque
        // TipoComprobantePerfil.TIQUETE.receptorObligatorio == false; ahora depende de si el
        // Tiquete realmente identificó un receptor, que es lo que el XSD real exige
        // (minOccurs="0" en los 4 tipos -- ningún perfil "obliga" nada a nivel de esquema).
        if (cliente != null) {
            agregarReceptor(xml, cliente);
        }

        String condicionVenta = factura.getCondicionVenta() != null ? factura.getCondicionVenta() : "01";
        xml.append("<CondicionVenta>").append(condicionVenta).append("</CondicionVenta>");

        // T-16: CondicionVentaOtros (when condicionVenta='99')
        if (factura.getCondicionVentaOtros() != null && !factura.getCondicionVentaOtros().isBlank()) {
            xml.append("<CondicionVentaOtros>")
                    .append(escaparXml(factura.getCondicionVentaOtros()))
                    .append("</CondicionVentaOtros>");
        }

        if ("02".equals(condicionVenta) && factura.getPlazoCredito() != null) {
            xml.append("<PlazoCredito>").append(factura.getPlazoCredito()).append("</PlazoCredito>");
        }

        agregarDetalleFactura(xml, contextos, perfil);
        agregarOtrosCargos(xml, facturaCtx.otrosCargos());
        agregarResumen(xml, factura, contextos, facturaCtx.mediosPago(), facturaCtx.otrosCargos());
        agregarInformacionReferencia(xml, facturaCtx.informacionReferencia(), perfil);

        xml.append(perfil.cierreRaiz());

        // Validación contra el XSD real (equivalente a XmlValidator.validarXml en el original) --
        // ver el javadoc de XmlFacturaXsdValidator. Lanza XmlFacturaInvalidoException si el XML
        // no cumple el esquema; nunca debería pasar con datos ya validados por FacturaService,
        // así que es un IllegalStateException-como-familia (bug interno), no un 404 de dominio.
        String xmlGenerado = xml.toString();
        xmlFacturaXsdValidator.validar(xmlGenerado, perfil);
        return xmlGenerado;
    }

    // =====================================================================
    // Secciones comunes
    // =====================================================================

    private void agregarEmisor(StringBuilder xml, Empresa empresa) {
        xml.append("<Emisor>");
        xml.append("<Nombre>").append(escaparXml(empresa.getRazonSocial())).append("</Nombre>");

        String tipoId = empresa.getTipoIdentificacion() != null ? empresa.getTipoIdentificacion() : "02";
        xml.append("<Identificacion>");
        xml.append("<Tipo>").append(tipoId).append("</Tipo>");
        xml.append("<Numero>").append(limpiarNumero(empresa.getNumeroIdentificacion())).append("</Numero>");
        xml.append("</Identificacion>");

        String nombreComercial = empresa.getNombreComercial() != null && !empresa.getNombreComercial().isBlank()
                ? empresa.getNombreComercial() : empresa.getRazonSocial();
        xml.append("<NombreComercial>").append(escaparXml(nombreComercial)).append("</NombreComercial>");

        xml.append("<Ubicacion>");
        xml.append("<Provincia>").append(empresa.getCodigoProvincia() != null ? empresa.getCodigoProvincia() : "1").append("</Provincia>");
        xml.append("<Canton>").append(empresa.getCanton() != null ? empresa.getCanton() : "01").append("</Canton>");
        xml.append("<Distrito>").append(empresa.getDistrito() != null ? empresa.getDistrito() : "01").append("</Distrito>");
        if (empresa.getBarrio() != null && !empresa.getBarrio().isBlank()) {
            xml.append("<Barrio>").append(escaparXml(empresa.getBarrio())).append("</Barrio>");
        }
        // Sin campo direccion intermedio en este proyecto -- decisión ya tomada, ver el javadoc
        // de la clase: se va directo de otrasSenas al fallback final.
        String otrasSenas = empresa.getOtrasSenas() != null ? empresa.getOtrasSenas().trim() : null;
        if (otrasSenas == null || otrasSenas.length() < 5) {
            log.warn("Empresa sin OtrasSenas válido (mínimo 5 chars) — actualizá los datos de la empresa");
            otrasSenas = "Sin especificar";
        }
        xml.append("<OtrasSenas>").append(escaparXml(otrasSenas)).append("</OtrasSenas>");
        xml.append("</Ubicacion>");

        if (empresa.getTelefono() != null && !empresa.getTelefono().isBlank()) {
            xml.append("<Telefono>");
            xml.append("<CodigoPais>506</CodigoPais>");
            xml.append("<NumTelefono>").append(limpiarNumero(empresa.getTelefono())).append("</NumTelefono>");
            xml.append("</Telefono>");
        }

        // Emisor.CorreoElectronico es obligatorio en el XSD (sin minOccurs="0") -- ver el javadoc
        // de EmpresaSinCorreoElectronicoException sobre por qué esto falla en vez de emitir un
        // <CorreoElectronico> vacío que pasaría la validación de esquema pero no la de Hacienda.
        if (empresa.getEmail() == null || empresa.getEmail().isBlank()) {
            throw new EmpresaSinCorreoElectronicoException(empresa.getId());
        }
        xml.append("<CorreoElectronico>").append(empresa.getEmail()).append("</CorreoElectronico>");
        xml.append("</Emisor>");
    }

    private void agregarReceptor(StringBuilder xml, Cliente cliente) {
        xml.append("<Receptor>");
        xml.append("<Nombre>").append(escaparXml(cliente.getNombre())).append("</Nombre>");

        String numeroIdentificacion = cliente.getNumeroIdentificacion();
        String tipoId = cliente.getTipoIdentificacion() != null
                ? cliente.getTipoIdentificacion()
                : determinarTipoIdentificacion(numeroIdentificacion);
        xml.append("<Identificacion>");
        xml.append("<Tipo>").append(tipoId).append("</Tipo>");
        xml.append("<Numero>").append(limpiarNumero(numeroIdentificacion)).append("</Numero>");
        xml.append("</Identificacion>");

        // OtrasSenas es obligatorio (sin minOccurs="0") y minLength=5 dentro de UbicacionType.
        // Como Receptor.Ubicacion sí es opcional (minOccurs="0") en el XSD, se omite el bloque
        // completo si falta algún campo obligatorio o si OtrasSenas mide menos de 5 caracteres --
        // a diferencia del Emisor, donde Ubicacion es obligatorio y por eso ahí sí hay fallback a
        // "Sin especificar" en vez de omitir el bloque.
        String otrasSenas = cliente.getOtrasSenas() != null ? cliente.getOtrasSenas().trim() : null;
        boolean ubicacionValida = cliente.getCodigoProvincia() != null
                && cliente.getCanton() != null
                && cliente.getDistrito() != null
                && otrasSenas != null && otrasSenas.length() >= 5;
        if (ubicacionValida) {
            xml.append("<Ubicacion>");
            xml.append("<Provincia>").append(cliente.getCodigoProvincia()).append("</Provincia>");
            xml.append("<Canton>").append(cliente.getCanton()).append("</Canton>");
            xml.append("<Distrito>").append(cliente.getDistrito()).append("</Distrito>");
            xml.append("<OtrasSenas>").append(escaparXml(otrasSenas)).append("</OtrasSenas>");
            xml.append("</Ubicacion>");
        }

        if (cliente.getTelefono() != null && !cliente.getTelefono().isBlank()) {
            xml.append("<Telefono>");
            xml.append("<CodigoPais>506</CodigoPais>");
            xml.append("<NumTelefono>").append(limpiarNumero(cliente.getTelefono())).append("</NumTelefono>");
            xml.append("</Telefono>");
        }

        if (cliente.getEmail() != null && !cliente.getEmail().isBlank()) {
            xml.append("<CorreoElectronico>").append(cliente.getEmail()).append("</CorreoElectronico>");
        }

        xml.append("</Receptor>");
    }

    private void agregarDetalleFactura(StringBuilder xml, List<LineaContexto> contextos, TipoComprobantePerfil perfil) {
        xml.append("<DetalleServicio>");

        for (LineaContexto contexto : contextos) {
            LineaFactura linea = contexto.linea();
            Producto producto = contexto.producto();
            LineaCalculo calculo = contexto.calculo();

            xml.append("<LineaDetalle>");
            xml.append("<NumeroLinea>").append(linea.getNumeroLinea()).append("</NumeroLinea>");

            String cabys = linea.getCodigoCabysAplicado() != null && !linea.getCodigoCabysAplicado().isBlank()
                    ? linea.getCodigoCabysAplicado() : "8522000000000";
            xml.append("<CodigoCABYS>").append(cabys).append("</CodigoCABYS>");

            // T-17: CodigosComerciales (after CodigoCABYS per XSD v4.4 element order)
            agregarCodigosComerciales(xml, contexto.codigosComerciales());

            BigDecimal cantidad = linea.getCantidad() != null ? linea.getCantidad() : BigDecimal.ONE;
            xml.append("<Cantidad>").append(fmt(cantidad, 3)).append("</Cantidad>");

            String unidadMedida = producto.getCodigoUnidadFe() != null && !producto.getCodigoUnidadFe().isBlank()
                    ? producto.getCodigoUnidadFe() : "Unid";
            xml.append("<UnidadMedida>").append(unidadMedida).append("</UnidadMedida>");

            if (perfil.isTipoTransaccionSoportado()
                    && linea.getTipoTransaccion() != null && !linea.getTipoTransaccion().isBlank()) {
                xml.append("<TipoTransaccion>").append(linea.getTipoTransaccion()).append("</TipoTransaccion>");
            }

            // T-17: UnidadMedidaComercial (optional)
            if (linea.getUnidadMedidaComercial() != null && !linea.getUnidadMedidaComercial().isBlank()) {
                xml.append("<UnidadMedidaComercial>")
                        .append(escaparXml(linea.getUnidadMedidaComercial()))
                        .append("</UnidadMedidaComercial>");
            }

            xml.append("<Detalle>").append(escaparXml(producto.getDescripcion())).append("</Detalle>");

            BigDecimal precioUnitario = linea.getPrecioUnitario() != null ? linea.getPrecioUnitario() : BigDecimal.ZERO;
            BigDecimal montoTotal = precioUnitario.multiply(cantidad).setScale(ESCALA_MONETARIA, RoundingMode.HALF_UP);

            xml.append("<PrecioUnitario>").append(fmt(precioUnitario, 5)).append("</PrecioUnitario>");
            xml.append("<MontoTotal>").append(fmt(montoTotal, 5)).append("</MontoTotal>");

            // T-17: Descuentos
            agregarDescuentos(xml, contexto.descuentos());

            xml.append("<SubTotal>").append(fmt(calculo.subtotal(), 5)).append("</SubTotal>");
            xml.append("<BaseImponible>").append(fmt(calculo.subtotal(), 5)).append("</BaseImponible>");

            xml.append("<Impuesto>");
            xml.append("<Codigo>01</Codigo>");
            xml.append("<CodigoTarifaIVA>").append(calculo.codigoTarifaIVA()).append("</CodigoTarifaIVA>");
            xml.append("<Tarifa>").append(calculo.porcentajeIVA().toPlainString()).append("</Tarifa>");
            xml.append("<Monto>").append(fmt(calculo.montoImpuesto(), 5)).append("</Monto>");

            // T-18: exoneracion — inline path first, then legacy path
            agregarExoneracion(xml, linea, contexto.exoneracionInline());

            xml.append("</Impuesto>");

            // T-17: IvaCobradoFabrica / ImpuestoAsumidoEmisorFabrica
            BigDecimal ivaCobradoFabrica = linea.getIvaCobradoFabrica() != null
                    ? linea.getIvaCobradoFabrica() : BigDecimal.ZERO;
            xml.append("<ImpuestoAsumidoEmisorFabrica>").append(fmt(ivaCobradoFabrica, 5)).append("</ImpuestoAsumidoEmisorFabrica>");

            // ImpuestoNeto/MontoTotalLinea: fórmula tomada literalmente de la documentación del
            // XSD (ImpuestoType/LineaDetalleType) -- "ImpuestoNeto se obtiene al restar Monto del
            // Impuesto menos Monto del Impuesto Exonerado"; "MontoTotalLinea = SubTotal +
            // ImpuestoNeto". Requisito nuevo que el original no necesitaba modelar (no tenía
            // exoneración a nivel de línea).
            xml.append("<ImpuestoNeto>").append(fmt(calculo.impuestoNeto(), 5)).append("</ImpuestoNeto>");
            xml.append("<MontoTotalLinea>")
                    .append(fmt(calculo.subtotal().add(calculo.impuestoNeto()), 5))
                    .append("</MontoTotalLinea>");

            xml.append("</LineaDetalle>");
        }

        xml.append("</DetalleServicio>");
    }

    // T-17: CodigosComerciales block within LineaDetalle
    private void agregarCodigosComerciales(StringBuilder xml, List<LineaCodigoComercial> codigos) {
        for (LineaCodigoComercial cc : codigos) {
            xml.append("<CodigoComercial>");
            xml.append("<Tipo>").append(cc.getTipo()).append("</Tipo>");
            xml.append("<Codigo>").append(escaparXml(cc.getCodigo())).append("</Codigo>");
            xml.append("</CodigoComercial>");
        }
    }

    // T-17: Descuentos block within LineaDetalle (after MontoTotal, before SubTotal)
    private void agregarDescuentos(StringBuilder xml, List<LineaDescuento> descuentos) {
        for (LineaDescuento d : descuentos) {
            xml.append("<Descuento>");
            xml.append("<MontoDescuento>").append(fmt(d.getMontoDescuento(), 5)).append("</MontoDescuento>");
            if (d.getCodigoDescuento() != null && !d.getCodigoDescuento().isBlank()) {
                xml.append("<CodigoDescuento>").append(d.getCodigoDescuento()).append("</CodigoDescuento>");
            }
            if (d.getCodigoDescuentoOtro() != null && !d.getCodigoDescuentoOtro().isBlank()) {
                xml.append("<CodigoDescuentoOtros>").append(escaparXml(d.getCodigoDescuentoOtro())).append("</CodigoDescuentoOtros>");
            }
            if (d.getNaturalezaDescuento() != null && !d.getNaturalezaDescuento().isBlank()) {
                xml.append("<NaturalezaDescuento>").append(escaparXml(d.getNaturalezaDescuento())).append("</NaturalezaDescuento>");
            }
            xml.append("</Descuento>");
        }
    }

    /**
     * T-18: Bloque {@code <Exoneracion>} de una línea.
     *
     * <p>Inline path (V11): si {@code exoneracionInline} no es nulo, se emite desde él directamente
     * sin consultar {@code ClienteExoneracion}. Legacy path: si la línea tiene
     * {@code exoneracion_id} y no hay inline, se consulta {@code ClienteExoneracion}.
     * Ambos paths son mutuamente excluyentes por construcción en {@code FacturaService#crear}.
     */
    private void agregarExoneracion(StringBuilder xml, LineaFactura linea,
            ImpuestoLineaExoneracion exoneracionInline) {
        if (exoneracionInline != null) {
            agregarExoneracionInline(xml, linea, exoneracionInline);
        } else {
            agregarExoneracionLegacy(xml, linea);
        }
    }

    private void agregarExoneracionInline(StringBuilder xml, LineaFactura linea,
            ImpuestoLineaExoneracion ex) {
        if (ex.getMontoExoneracion() == null || ex.getMontoExoneracion().compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        xml.append("<Exoneracion>");
        xml.append("<TipoDocumentoEX1>").append(ex.getTipoDocumentoEx1()).append("</TipoDocumentoEX1>");

        if ("99".equals(ex.getTipoDocumentoEx1())
                && ex.getTipoDocumentoOtro() != null && !ex.getTipoDocumentoOtro().isBlank()) {
            xml.append("<TipoDocumentoOTRO>")
                    .append(escaparXml(ex.getTipoDocumentoOtro()))
                    .append("</TipoDocumentoOTRO>");
        }

        xml.append("<NumeroDocumento>").append(escaparXml(ex.getNumeroDocumento())).append("</NumeroDocumento>");

        if (ex.getArticulo() != null) {
            xml.append("<Articulo>").append(ex.getArticulo()).append("</Articulo>");
        }
        if (ex.getInciso() != null) {
            xml.append("<Inciso>").append(ex.getInciso()).append("</Inciso>");
        }

        xml.append("<NombreInstitucion>").append(escaparXml(ex.getNombreInstitucion())).append("</NombreInstitucion>");

        if ("99".equals(ex.getNombreInstitucion())
                && ex.getNombreInstitucionOtros() != null && !ex.getNombreInstitucionOtros().isBlank()) {
            xml.append("<NombreInstitucionOtros>")
                    .append(escaparXml(ex.getNombreInstitucionOtros()))
                    .append("</NombreInstitucionOtros>");
        }

        if (ex.getFechaEmisionEx() != null) {
            xml.append("<FechaEmisionEX>").append(formatearFecha(ex.getFechaEmisionEx())).append("</FechaEmisionEX>");
        }

        BigDecimal tarifaExonerada = ex.getTarifaExonerada() != null ? ex.getTarifaExonerada() : BigDecimal.ZERO;
        xml.append("<TarifaExonerada>").append(fmt(tarifaExonerada, 2)).append("</TarifaExonerada>");
        xml.append("<MontoExoneracion>").append(fmt(ex.getMontoExoneracion(), 5)).append("</MontoExoneracion>");

        xml.append("</Exoneracion>");
    }

    /**
     * Legacy path: emite {@code <Exoneracion>} desde {@link ClienteExoneracion}.
     * Ver el javadoc de la clase para el detalle campo a campo y los gaps de modelo de datos.
     */
    private void agregarExoneracionLegacy(StringBuilder xml, LineaFactura linea) {
        if (linea.getExoneracionId() == null
                || linea.getMontoExoneracionAplicado() == null
                || linea.getMontoExoneracionAplicado().compareTo(BigDecimal.ZERO) == 0) {
            return;
        }

        ClienteExoneracion exoneracion = clienteExoneracionRepository.findById(linea.getExoneracionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Exoneración no encontrada para línea " + linea.getId() + ": " + linea.getExoneracionId()));

        xml.append("<Exoneracion>");
        xml.append("<TipoDocumentoEX1>").append(exoneracion.getTipoDocumento()).append("</TipoDocumentoEX1>");

        if ("99".equals(exoneracion.getTipoDocumento())
                && exoneracion.getNombreInstitucionOtros() != null
                && !exoneracion.getNombreInstitucionOtros().isBlank()) {
            xml.append("<TipoDocumentoOTRO>")
                    .append(escaparXml(exoneracion.getNombreInstitucionOtros()))
                    .append("</TipoDocumentoOTRO>");
        }

        xml.append("<NumeroDocumento>").append(escaparXml(exoneracion.getNumeroDocumento())).append("</NumeroDocumento>");

        if (esEnteroValido(exoneracion.getNumeroArticulo())) {
            xml.append("<Articulo>").append(exoneracion.getNumeroArticulo().trim()).append("</Articulo>");
        }
        if (esEnteroValido(exoneracion.getInciso())) {
            xml.append("<Inciso>").append(exoneracion.getInciso().trim()).append("</Inciso>");
        }

        // NombreInstitucion: ver el javadoc de la clase -- gap real de modelo de datos, se porta
        // el texto libre tal cual (Categoría A), no un código de catálogo.
        xml.append("<NombreInstitucion>").append(escaparXml(exoneracion.getNombreInstitucion())).append("</NombreInstitucion>");

        xml.append("<FechaEmisionEX>").append(formatearFecha(exoneracion.getFechaEmision())).append("</FechaEmisionEX>");

        BigDecimal tarifaExonerada = linea.getPorcentajeExoneracionAplicado() != null
                ? linea.getPorcentajeExoneracionAplicado() : BigDecimal.ZERO;
        xml.append("<TarifaExonerada>").append(fmt(tarifaExonerada, 2)).append("</TarifaExonerada>");
        xml.append("<MontoExoneracion>").append(fmt(linea.getMontoExoneracionAplicado(), 5)).append("</MontoExoneracion>");

        xml.append("</Exoneracion>");
    }

    // T-19: OtrosCargos block after </DetalleServicio>
    private void agregarOtrosCargos(StringBuilder xml, List<FacturaOtrosCargos> otrosCargos) {
        for (FacturaOtrosCargos oc : otrosCargos) {
            xml.append("<OtrosCargos>");
            xml.append("<TipoDocumentoOC>").append(oc.getTipoDocumentoOc()).append("</TipoDocumentoOC>");

            if ("99".equals(oc.getTipoDocumentoOc())
                    && oc.getTipoDocumentoOtros() != null && !oc.getTipoDocumentoOtros().isBlank()) {
                xml.append("<TipoDocumentoOTROS>")
                        .append(escaparXml(oc.getTipoDocumentoOtros()))
                        .append("</TipoDocumentoOTROS>");
            }

            if (oc.getIdentidadTipo() != null && oc.getIdentidadNumero() != null) {
                xml.append("<IdentificacionTercero>");
                xml.append("<Tipo>").append(oc.getIdentidadTipo()).append("</Tipo>");
                xml.append("<Numero>").append(limpiarNumero(oc.getIdentidadNumero())).append("</Numero>");
                xml.append("</IdentificacionTercero>");
            }

            if (oc.getNombreTercero() != null && !oc.getNombreTercero().isBlank()) {
                xml.append("<NombreTercero>").append(escaparXml(oc.getNombreTercero())).append("</NombreTercero>");
            }

            xml.append("<Detalle>").append(escaparXml(oc.getDetalle())).append("</Detalle>");

            if (oc.getPorcentajeOc() != null) {
                xml.append("<PorcentajeOC>").append(fmt(oc.getPorcentajeOc(), 2)).append("</PorcentajeOC>");
            }

            xml.append("<MontoCargo>").append(fmt(oc.getMontoCargo(), 5)).append("</MontoCargo>");
            xml.append("</OtrosCargos>");
        }
    }

    // T-20: Extended agregarResumen with multi-MedioPago and OtrosCargos
    private void agregarResumen(StringBuilder xml, Factura factura, List<LineaContexto> contextos,
            List<FacturaMedioPago> mediosPago, List<FacturaOtrosCargos> otrosCargos) {
        BigDecimal totalMercanciasGravadas = BigDecimal.ZERO;
        BigDecimal totalMercanciasExentas = BigDecimal.ZERO;
        BigDecimal totalServiciosGravados = BigDecimal.ZERO;
        BigDecimal totalServiciosExentos = BigDecimal.ZERO;
        // Suma de <Impuesto><Monto> de cada línea (bruto, ANTES de exoneración) -- así lo define
        // literalmente la documentación del XSD para TotalImpuesto/TotalDesgloseImpuesto ("suma
        // de todos campos monto del impuesto" / "suma del monto por código de impuesto").
        BigDecimal totalImpuestoBruto = BigDecimal.ZERO;
        BigDecimal totalExonerado = BigDecimal.ZERO;
        BigDecimal totalDescuentos = BigDecimal.ZERO;
        // Impuesto neto agrupado por CodigoTarifaIVA -- el XSD permite hasta 1000
        // <TotalDesgloseImpuesto>, uno por cada tarifa de IVA distinta presente en la factura
        // (LinkedHashMap para que, con una sola tarifa, el orden de salida sea determinístico).
        // Antes de este fix se emitía un único bloque con la tarifa de la ÚLTIMA línea gravada del
        // loop, lo que etiquetaba mal el desglose en facturas que mezclan tarifas (13% + 4%, etc.)
        // aunque el monto total (TotalImpuesto) ya fuera correcto.
        Map<String, BigDecimal> impuestoNetoPorTarifa = new LinkedHashMap<>();

        for (LineaContexto contexto : contextos) {
            LineaCalculo calculo = contexto.calculo();
            boolean servicio = esServicio(contexto.producto().getCodigoUnidadFe());
            if (calculo.gravado()) {
                if (servicio) {
                    totalServiciosGravados = totalServiciosGravados.add(calculo.subtotal());
                } else {
                    totalMercanciasGravadas = totalMercanciasGravadas.add(calculo.subtotal());
                }
            } else {
                if (servicio) {
                    totalServiciosExentos = totalServiciosExentos.add(calculo.subtotal());
                } else {
                    totalMercanciasExentas = totalMercanciasExentas.add(calculo.subtotal());
                }
            }
            impuestoNetoPorTarifa.merge(calculo.codigoTarifaIVA(), calculo.impuestoNeto(), BigDecimal::add);
            totalImpuestoBruto = totalImpuestoBruto.add(calculo.montoImpuesto());
            totalExonerado = totalExonerado.add(calculo.montoExonerado());
            for (LineaDescuento d : contexto.descuentos()) {
                totalDescuentos = totalDescuentos.add(d.getMontoDescuento());
            }
        }

        BigDecimal totalGravado = totalMercanciasGravadas.add(totalServiciosGravados);
        BigDecimal totalExento = totalMercanciasExentas.add(totalServiciosExentos);
        BigDecimal totalVenta = totalGravado.add(totalExento);
        BigDecimal totalVentaNeta = totalVenta.subtract(totalDescuentos);
        // Impuesto neto de la factura completa: bruto menos exonerado -- MISMA fórmula que
        // FacturaService#crear ya usó para persistir factura.totalImpuesto (sección 4.15.2), así
        // que este valor coincide con factura.getTotalImpuesto() por construcción, no por
        // casualidad (se recalcula aquí en vez de leer el campo persistido para que este
        // generador siga siendo verificable de forma autocontenida a partir de las líneas).
        BigDecimal totalImpuestoNeto = totalImpuestoBruto.subtract(totalExonerado);
        BigDecimal ivaDevuelto = factura.getTotalIvaDevuelto() != null
                ? factura.getTotalIvaDevuelto() : BigDecimal.ZERO;
        BigDecimal totalOtrosCargosValor = otrosCargos.stream()
                .map(FacturaOtrosCargos::getMontoCargo)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalComprobante = totalVentaNeta.add(totalImpuestoNeto)
                .subtract(ivaDevuelto).add(totalOtrosCargosValor);

        String codigoMoneda = factura.getMoneda() != null ? factura.getMoneda() : "CRC";
        BigDecimal tipoCambio = factura.getTipoCambio() != null ? factura.getTipoCambio() : BigDecimal.ONE;

        xml.append("<ResumenFactura>");
        xml.append("<CodigoTipoMoneda>");
        xml.append("<CodigoMoneda>").append(codigoMoneda).append("</CodigoMoneda>");
        xml.append("<TipoCambio>").append(fmt(tipoCambio, 5)).append("</TipoCambio>");
        xml.append("</CodigoTipoMoneda>");

        if (totalServiciosGravados.compareTo(BigDecimal.ZERO) > 0) {
            xml.append("<TotalServGravados>").append(fmt(totalServiciosGravados, 5)).append("</TotalServGravados>");
        }
        if (totalServiciosExentos.compareTo(BigDecimal.ZERO) > 0) {
            xml.append("<TotalServExentos>").append(fmt(totalServiciosExentos, 5)).append("</TotalServExentos>");
        }
        if (totalMercanciasGravadas.compareTo(BigDecimal.ZERO) > 0) {
            xml.append("<TotalMercanciasGravadas>").append(fmt(totalMercanciasGravadas, 5)).append("</TotalMercanciasGravadas>");
        }
        if (totalMercanciasExentas.compareTo(BigDecimal.ZERO) > 0) {
            xml.append("<TotalMercanciasExentas>").append(fmt(totalMercanciasExentas, 5)).append("</TotalMercanciasExentas>");
        }

        xml.append("<TotalGravado>").append(fmt(totalGravado, 5)).append("</TotalGravado>");
        xml.append("<TotalExento>").append(fmt(totalExento, 5)).append("</TotalExento>");
        xml.append("<TotalExonerado>").append(fmt(totalExonerado, 5)).append("</TotalExonerado>");
        xml.append("<TotalNoSujeto>0.00000</TotalNoSujeto>");
        xml.append("<TotalVenta>").append(fmt(totalVenta, 5)).append("</TotalVenta>");
        if (totalDescuentos.compareTo(BigDecimal.ZERO) > 0) {
            xml.append("<TotalDescuentos>").append(fmt(totalDescuentos, 5)).append("</TotalDescuentos>");
        }
        xml.append("<TotalVentaNeta>").append(fmt(totalVentaNeta, 5)).append("</TotalVentaNeta>");

        for (Map.Entry<String, BigDecimal> entry : impuestoNetoPorTarifa.entrySet()) {
            xml.append("<TotalDesgloseImpuesto>");
            xml.append("<Codigo>01</Codigo>");
            xml.append("<CodigoTarifaIVA>").append(entry.getKey()).append("</CodigoTarifaIVA>");
            xml.append("<TotalMontoImpuesto>").append(fmt(entry.getValue(), 5)).append("</TotalMontoImpuesto>");
            xml.append("</TotalDesgloseImpuesto>");
        }

        xml.append("<TotalImpuesto>").append(fmt(totalImpuestoNeto, 5)).append("</TotalImpuesto>");

        if (ivaDevuelto.compareTo(BigDecimal.ZERO) > 0) {
            xml.append("<TotalIVADevuelto>").append(fmt(ivaDevuelto, 5)).append("</TotalIVADevuelto>");
        }

        // XSD: TotalOtrosCargos before MedioPago
        if (totalOtrosCargosValor.compareTo(BigDecimal.ZERO) > 0) {
            xml.append("<TotalOtrosCargos>").append(fmt(totalOtrosCargosValor, 5)).append("</TotalOtrosCargos>");
        }

        // T-20: Multi-MedioPago from FacturaMedioPago child table
        if (!mediosPago.isEmpty()) {
            for (FacturaMedioPago mp : mediosPago) {
                xml.append("<MedioPago>");
                xml.append("<TipoMedioPago>").append(mp.getTipoMedioPago()).append("</TipoMedioPago>");
                if ("99".equals(mp.getTipoMedioPago())
                        && mp.getMedioPagoOtros() != null && !mp.getMedioPagoOtros().isBlank()) {
                    xml.append("<TipoMedioPagoOtros>")
                            .append(escaparXml(mp.getMedioPagoOtros()))
                            .append("</TipoMedioPagoOtros>");
                }
                xml.append("<TotalMedioPago>").append(fmt(mp.getTotalMedioPago(), 5)).append("</TotalMedioPago>");
                xml.append("</MedioPago>");
            }
        } else {
            // Legacy fallback: single MedioPago from factura.medioPago scalar
            String codigoMedioPago = factura.getMedioPago() != null ? factura.getMedioPago() : "01";
            xml.append("<MedioPago>");
            xml.append("<TipoMedioPago>").append(codigoMedioPago).append("</TipoMedioPago>");
            xml.append("<TotalMedioPago>").append(fmt(totalComprobante, 5)).append("</TotalMedioPago>");
            xml.append("</MedioPago>");
        }

        xml.append("<TotalComprobante>").append(fmt(totalComprobante, 5)).append("</TotalComprobante>");
        xml.append("</ResumenFactura>");
    }

    // T-21: InformacionReferencia block after </ResumenFactura>
    //
    // Release 2 / Fase B: el XSD real exige mínimo 1 ocurrencia para ND/NC (sin minOccurs="0"),
    // a diferencia de Factura/Tiquete donde es opcional -- ver perfil.getReferenciasMinimas() y
    // el diseño de Fase B, decisión D-C sitio #7. Se falla ruidosamente en Java (mensaje
    // diagnosticable) en vez de dejar que el XSD reporte una cardinalidad incumplida de forma
    // opaca cuando la validación corra más abajo.
    private void agregarInformacionReferencia(StringBuilder xml,
            List<FacturaInformacionReferencia> referencias, TipoComprobantePerfil perfil) {
        if (referencias.size() < perfil.getReferenciasMinimas()) {
            throw new ComprobanteSinReferenciaObligatoriaException(perfil, referencias.size());
        }
        for (FacturaInformacionReferencia ref : referencias) {
            xml.append("<InformacionReferencia>");
            xml.append("<TipoDocIR>").append(ref.getTipoDocIr()).append("</TipoDocIR>");

            if ("99".equals(ref.getTipoDocIr())
                    && ref.getTipoDocRefOtro() != null && !ref.getTipoDocRefOtro().isBlank()) {
                xml.append("<TipoDocRefOTRO>")
                        .append(escaparXml(ref.getTipoDocRefOtro()))
                        .append("</TipoDocRefOTRO>");
            }

            if (ref.getNumero() != null && !ref.getNumero().isBlank()) {
                xml.append("<Numero>").append(escaparXml(ref.getNumero())).append("</Numero>");
            }

            if (ref.getFechaEmisionIr() != null) {
                xml.append("<FechaEmisionIR>")
                        .append(formatearFecha(ref.getFechaEmisionIr()))
                        .append("</FechaEmisionIR>");
            }

            if (ref.getCodigo() != null && !ref.getCodigo().isBlank()) {
                xml.append("<Codigo>").append(ref.getCodigo()).append("</Codigo>");
            }

            if ("99".equals(ref.getCodigo())
                    && ref.getCodigoReferenciaOtro() != null && !ref.getCodigoReferenciaOtro().isBlank()) {
                xml.append("<CodigoReferenciaOTRO>")
                        .append(escaparXml(ref.getCodigoReferenciaOtro()))
                        .append("</CodigoReferenciaOTRO>");
            }

            if (ref.getRazon() != null && !ref.getRazon().isBlank()) {
                xml.append("<Razon>").append(escaparXml(ref.getRazon())).append("</Razon>");
            }

            xml.append("</InformacionReferencia>");
        }
    }

    // =====================================================================
    // Resolución de entidades relacionadas (sin relaciones JPA vivas -- ver el javadoc de la
    // clase)
    // =====================================================================

    private LineaContexto resolverContexto(LineaFactura linea) {
        Producto producto = productoRepository.findById(linea.getProductoId())
                .orElseThrow(() -> new IllegalStateException(
                        "Producto no encontrado para línea " + linea.getId() + ": " + linea.getProductoId()));
        List<LineaCodigoComercial> codigos = lineaCodigoComercialRepository
                .findByLineaIdOrderByOrden(linea.getId());
        List<LineaDescuento> descuentos = lineaDescuentoRepository
                .findByLineaIdOrderByOrden(linea.getId());
        ImpuestoLineaExoneracion exoneracionInline = impuestoLineaExoneracionRepository
                .findByLineaId(linea.getId())
                .orElse(null);
        return new LineaContexto(linea, producto, calcular(linea, exoneracionInline),
                codigos, descuentos, exoneracionInline);
    }

    /**
     * Cálculo de impuesto de una línea, en un solo lugar para que {@code agregarDetalleFactura} y
     * {@code agregarResumen} nunca puedan divergir en la fórmula.
     *
     * <p>Inline exoneracion: usa {@code montoExoneracion} del registro inline.
     * Legacy exoneracion: usa {@code linea.getMontoExoneracionAplicado()} (snapshot).
     */
    private LineaCalculo calcular(LineaFactura linea, ImpuestoLineaExoneracion exoneracionInline) {
        BigDecimal subtotal = linea.getSubtotal() != null
                ? linea.getSubtotal().setScale(ESCALA_MONETARIA, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        boolean gravado = linea.isGravadoAplicado();
        BigDecimal porcentajeIVA = gravado
                ? Optional.ofNullable(linea.getPorcentajeImpuestoAplicado()).orElse(BigDecimal.ZERO)
                : BigDecimal.ZERO;
        BigDecimal montoImpuesto = subtotal.multiply(porcentajeIVA).divide(CIEN, ESCALA_MONETARIA, RoundingMode.HALF_UP);

        BigDecimal montoExonerado;
        if (exoneracionInline != null) {
            montoExonerado = exoneracionInline.getMontoExoneracion() != null
                    ? exoneracionInline.getMontoExoneracion().setScale(ESCALA_MONETARIA, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
        } else {
            montoExonerado = linea.getMontoExoneracionAplicado() != null
                    ? linea.getMontoExoneracionAplicado().setScale(ESCALA_MONETARIA, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
        }

        BigDecimal impuestoNeto = montoImpuesto.subtract(montoExonerado);
        String codigoTarifaIVA = resolverCodigoTarifaIVA(porcentajeIVA);
        return new LineaCalculo(subtotal, gravado, porcentajeIVA, montoImpuesto, montoExonerado, impuestoNeto, codigoTarifaIVA);
    }

    // T-15: FacturaContexto record for factura-level children
    private record FacturaContexto(
            List<FacturaOtrosCargos> otrosCargos,
            List<FacturaInformacionReferencia> informacionReferencia,
            List<FacturaMedioPago> mediosPago) {
    }

    private record LineaContexto(
            LineaFactura linea,
            Producto producto,
            LineaCalculo calculo,
            List<LineaCodigoComercial> codigosComerciales,
            List<LineaDescuento> descuentos,
            ImpuestoLineaExoneracion exoneracionInline) {
    }

    private record LineaCalculo(
            BigDecimal subtotal,
            boolean gravado,
            BigDecimal porcentajeIVA,
            BigDecimal montoImpuesto,
            BigDecimal montoExonerado,
            BigDecimal impuestoNeto,
            String codigoTarifaIVA) {
    }

    // =====================================================================
    // Utilidades
    // =====================================================================

    private static boolean esServicio(String codigoUnidadFe) {
        return switch (codigoUnidadFe == null ? "" : codigoUnidadFe) {
            case "Sp", "Spe", "St", "Os", "Al", "Alc", "Cm", "I" -> true;
            default -> false;
        };
    }

    /**
     * Resuelve el CodigoTarifaIVA según el porcentaje (FE v4.4):
     * 08=13% general, 02=1%, 03=2%, 04=4%, 06=8%, 10=exento(0%), 01=no sujeto(0%)
     * Los códigos 05/07/09 son tarifas transitorias exclusivas de NC/ND -- nunca se usan en FE01.
     */
    private String resolverCodigoTarifaIVA(BigDecimal porcentaje) {
        if (porcentaje == null || porcentaje.compareTo(BigDecimal.ZERO) == 0) return "10";
        if (porcentaje.compareTo(new BigDecimal("1")) == 0) return "02";
        if (porcentaje.compareTo(new BigDecimal("2")) == 0) return "03";
        if (porcentaje.compareTo(new BigDecimal("4")) == 0) return "04";
        if (porcentaje.compareTo(new BigDecimal("8")) == 0) return "06";
        return "08"; // 13% general
    }

    private String fmt(BigDecimal value, int decimals) {
        if (value == null) return "0." + "0".repeat(decimals);
        return value.setScale(decimals, RoundingMode.HALF_UP).toPlainString();
    }

    private String formatearFecha(LocalDateTime fecha) {
        if (fecha == null) throw new IllegalStateException("Fecha no disponible para formatear en el XML");
        // fechaEmision se persiste en UTC (el servidor corre en UTC) — hay que convertir al
        // huso CR antes de formatear, no solo adjuntar -06:00 como si fuera local.
        return fecha.atZone(ZoneOffset.UTC).withZoneSameInstant(CR_ZONE).toOffsetDateTime().format(DATE_FORMATTER);
    }

    private String obtenerProveedorSistemas(Empresa empresa) {
        if (empresa.getNumeroIdentificacion() != null) {
            return limpiarNumero(empresa.getNumeroIdentificacion());
        }
        return "";
    }

    private String obtenerActividadEconomica(Empresa empresa) {
        if (empresa.getCodigoActividad() != null) {
            return empresa.getCodigoActividad();
        }
        log.warn("Empresa sin código de actividad económica. Usando genérico.");
        return "620100";
    }

    private String determinarTipoIdentificacion(String identificacion) {
        if (identificacion == null) return "01";
        String numerico = limpiarNumero(identificacion);
        if (numerico.length() == 10) return "02"; // Jurídica
        if (numerico.length() == 9) return "01"; // Física
        return "01";
    }

    private String limpiarNumero(String valor) {
        return valor != null ? valor.replaceAll("[^0-9]", "") : "";
    }

    private boolean esEnteroValido(String valor) {
        return valor != null && valor.trim().matches("\\d+");
    }

    private String escaparXml(String texto) {
        if (texto == null) return "";
        return texto.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
