package cr.ac.fractall.facturacion.servicio.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.modelo.LineaFactura;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.facturacion.repositorio.LineaFacturaRepository;
import cr.ac.fractall.facturacion.servicio.ComprobanteElectronicoNoEncontradoException;
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
 * <p><b>Bloque {@code <Exoneracion>} (requisito nuevo, el original no lo tiene):</b> el XSD v4.4
 * anida {@code ExoneracionType} dentro de cada {@code <Impuesto>} de línea, justo después de
 * {@code <Monto>}, con {@code minOccurs="0"}. Se emite solo cuando la línea tiene una exoneración
 * realmente aplicada ({@code LineaFactura#getExoneracionId()} no nulo Y
 * {@code montoExoneracionAplicado} no nulo/no cero -- ambos van "todo o nada" por el CHECK
 * {@code chk_exoneracion_todo_o_nada} de la Fase 7). El resto de los campos de
 * {@code ExoneracionType} se resuelven consultando el {@link ClienteExoneracion} apuntado por
 * {@code LineaFactura#getExoneracionId()} (la FK que {@code FacturaService#aplicarExoneracion}
 * ya deja grabada en la línea, ver su javadoc):
 *
 * <ul>
 *   <li>{@code TipoDocumentoEX1} = {@code ClienteExoneracion#getTipoDocumento()} -- catálogo de
 *       12 códigos idéntico entre {@code ClienteExoneracionService#CODIGOS_TIPO_DOCUMENTO} y el
 *       {@code TipoExoneracionType} del XSD (01-11, 99), confirmado campo a campo.
 *   <li>{@code TipoDocumentoOTRO} (obligatorio en el XSD cuando {@code TipoDocumentoEX1='99'}) se
 *       llena con {@code ClienteExoneracion#getNombreInstitucionOtros()} -- ese es, en este
 *       proyecto, el campo que {@code ClienteExoneracionService#validarTipoDocumento} exige
 *       precisamente en ese caso ("nombreInstitucionOtros es obligatorio cuando tipoDocumento =
 *       '99'"), pese a que su nombre coincide textualmente con otro elemento del XSD
 *       ({@code NombreInstitucionOtros}, ver el punto siguiente). Es el campo semánticamente
 *       correcto para este rol, no uno reutilizado por casualidad de nombre.
 *   <li><b>Gap real de modelo de datos, hallado en esta sub-tarea:</b> {@code NombreInstitucion}
 *       en el XSD es un catálogo cerrado de 2 dígitos (01-12 institución, 99 otros), pero
 *       {@code ClienteExoneracion#getNombreInstitucion()} en este proyecto es texto libre (nombre
 *       de la institución, hasta 160 caracteres) -- no hay campo de catálogo equivalente. Se
 *       porta tal cual (Categoría A, sin inventar un catálogo que no existe todavía); como
 *       {@code nombreInstitucion} nunca vale literalmente {@code "99"}, el elemento
 *       {@code NombreInstitucionOtros} del XSD (obligatorio solo cuando
 *       {@code NombreInstitucion='99'}) nunca se emite. Este gap seguramente lo va a evidenciar
 *       la sub-tarea 3 (validación XSD) si Hacienda rechaza el valor -- documentado aquí para que
 *       esa sub-tarea no lo redescubra desde cero.
 * </ul>
 */
@Service
@Slf4j
public class XmlFacturaGeneratorServiceImpl implements XmlFacturaGeneratorService {

    private static final String NAMESPACE =
            "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/facturaElectronica";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final ZoneOffset CR_ZONE_OFFSET = ZoneOffset.of("-06:00");
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

    public XmlFacturaGeneratorServiceImpl(
            ComprobanteElectronicoRepository comprobanteElectronicoRepository,
            FacturaRepository facturaRepository,
            LineaFacturaRepository lineaFacturaRepository,
            EmpresaRepository empresaRepository,
            ClienteRepository clienteRepository,
            ProductoRepository productoRepository,
            ClienteExoneracionRepository clienteExoneracionRepository,
            XmlFacturaXsdValidator xmlFacturaXsdValidator) {
        this.comprobanteElectronicoRepository = comprobanteElectronicoRepository;
        this.facturaRepository = facturaRepository;
        this.lineaFacturaRepository = lineaFacturaRepository;
        this.empresaRepository = empresaRepository;
        this.clienteRepository = clienteRepository;
        this.productoRepository = productoRepository;
        this.clienteExoneracionRepository = clienteExoneracionRepository;
        this.xmlFacturaXsdValidator = xmlFacturaXsdValidator;
    }

    @Override
    public String generarXmlFactura(UUID comprobanteId) {
        log.info("Generando XML de factura electrónica para comprobante: {}", comprobanteId);

        // findById ya filtra por @TenantId -- un id de otro tenant resuelve vacío, tratado igual
        // que "no encontrado" (mismo principio que el resto de los servicios de este proyecto,
        // ver el javadoc de FacturaService#crear).
        ComprobanteElectronico comprobante = comprobanteElectronicoRepository.findById(comprobanteId)
                .orElseThrow(() -> new ComprobanteElectronicoNoEncontradoException(comprobanteId));

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

        Cliente cliente = clienteRepository.findById(factura.getClienteId())
                .orElseThrow(() -> new IllegalStateException(
                        "Cliente no encontrado para factura " + factura.getId() + ": " + factura.getClienteId()));

        List<LineaFactura> lineas = lineaFacturaRepository.findByFacturaIdOrderByNumeroLinea(factura.getId());
        List<LineaContexto> contextos = lineas.stream()
                .map(linea -> new LineaContexto(linea, resolverProducto(linea), calcular(linea)))
                .toList();

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        xml.append("<FacturaElectronica");
        xml.append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"");
        xml.append(" xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"");
        xml.append(" xmlns=\"").append(NAMESPACE).append("\"");
        xml.append(" xsi:schemaLocation=\"").append(NAMESPACE).append(" ").append(NAMESPACE).append(".xsd\"");
        xml.append(">");

        xml.append("<Clave>").append(comprobante.getClaveNumerica()).append("</Clave>");
        xml.append("<ProveedorSistemas>").append(obtenerProveedorSistemas(empresa)).append("</ProveedorSistemas>");
        xml.append("<CodigoActividadEmisor>").append(obtenerActividadEconomica(empresa)).append("</CodigoActividadEmisor>");
        // NumeroConsecutivo ya vive como campo propio (Fase 7) -- ver el javadoc de la clase.
        xml.append("<NumeroConsecutivo>").append(comprobante.getConsecutivo()).append("</NumeroConsecutivo>");
        xml.append("<FechaEmision>").append(formatearFecha(comprobante.getFechaEmision())).append("</FechaEmision>");

        agregarEmisor(xml, empresa);
        agregarReceptor(xml, cliente);

        String condicionVenta = factura.getCondicionVenta() != null ? factura.getCondicionVenta() : "01";
        xml.append("<CondicionVenta>").append(condicionVenta).append("</CondicionVenta>");

        if ("02".equals(condicionVenta) && factura.getPlazoCredito() != null) {
            xml.append("<PlazoCredito>").append(factura.getPlazoCredito()).append("</PlazoCredito>");
        }

        agregarDetalleFactura(xml, contextos);
        agregarResumen(xml, factura, contextos);

        xml.append("</FacturaElectronica>");

        // Validación contra el XSD real (equivalente a XmlValidator.validarXml en el original) --
        // ver el javadoc de XmlFacturaXsdValidator. Lanza XmlFacturaInvalidoException si el XML
        // no cumple el esquema; nunca debería pasar con datos ya validados por FacturaService,
        // así que es un IllegalStateException-como-familia (bug interno), no un 404 de dominio.
        String xmlGenerado = xml.toString();
        xmlFacturaXsdValidator.validar(xmlGenerado);
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

        xml.append("<CorreoElectronico>").append(empresa.getEmail() != null ? empresa.getEmail() : "").append("</CorreoElectronico>");
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

    private void agregarDetalleFactura(StringBuilder xml, List<LineaContexto> contextos) {
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

            BigDecimal cantidad = linea.getCantidad() != null ? linea.getCantidad() : BigDecimal.ONE;
            xml.append("<Cantidad>").append(fmt(cantidad, 3)).append("</Cantidad>");

            String unidadMedida = producto.getCodigoUnidadFe() != null && !producto.getCodigoUnidadFe().isBlank()
                    ? producto.getCodigoUnidadFe() : "Unid";
            xml.append("<UnidadMedida>").append(unidadMedida).append("</UnidadMedida>");
            xml.append("<Detalle>").append(escaparXml(producto.getDescripcion())).append("</Detalle>");

            BigDecimal precioUnitario = linea.getPrecioUnitario() != null ? linea.getPrecioUnitario() : BigDecimal.ZERO;
            BigDecimal montoTotal = precioUnitario.multiply(cantidad).setScale(ESCALA_MONETARIA, RoundingMode.HALF_UP);

            xml.append("<PrecioUnitario>").append(fmt(precioUnitario, 5)).append("</PrecioUnitario>");
            xml.append("<MontoTotal>").append(fmt(montoTotal, 5)).append("</MontoTotal>");
            xml.append("<SubTotal>").append(fmt(calculo.subtotal(), 5)).append("</SubTotal>");
            xml.append("<BaseImponible>").append(fmt(calculo.subtotal(), 5)).append("</BaseImponible>");

            xml.append("<Impuesto>");
            xml.append("<Codigo>01</Codigo>");
            xml.append("<CodigoTarifaIVA>").append(calculo.codigoTarifaIVA()).append("</CodigoTarifaIVA>");
            xml.append("<Tarifa>").append(calculo.porcentajeIVA().toPlainString()).append("</Tarifa>");
            xml.append("<Monto>").append(fmt(calculo.montoImpuesto(), 5)).append("</Monto>");
            agregarExoneracion(xml, linea);
            xml.append("</Impuesto>");

            xml.append("<ImpuestoAsumidoEmisorFabrica>0.00000</ImpuestoAsumidoEmisorFabrica>");
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

    /**
     * Bloque {@code <Exoneracion>} de una línea -- ver el javadoc de la clase para el detalle
     * campo a campo de dónde sale cada valor y los dos gaps de modelo de datos hallados
     * ({@code NombreInstitucion} y el reuso de {@code nombreInstitucionOtros}).
     */
    private void agregarExoneracion(StringBuilder xml, LineaFactura linea) {
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

    private void agregarResumen(StringBuilder xml, Factura factura, List<LineaContexto> contextos) {
        BigDecimal totalMercanciasGravadas = BigDecimal.ZERO;
        BigDecimal totalMercanciasExentas = BigDecimal.ZERO;
        // Suma de <Impuesto><Monto> de cada línea (bruto, ANTES de exoneración) -- así lo define
        // literalmente la documentación del XSD para TotalImpuesto/TotalDesgloseImpuesto ("suma
        // de todos campos monto del impuesto" / "suma del monto por código de impuesto").
        BigDecimal totalImpuestoBruto = BigDecimal.ZERO;
        BigDecimal totalExonerado = BigDecimal.ZERO;
        String codigoTarifaPrincipal = "10"; // exento por defecto

        for (LineaContexto contexto : contextos) {
            LineaCalculo calculo = contexto.calculo();
            if (calculo.gravado()) {
                totalMercanciasGravadas = totalMercanciasGravadas.add(calculo.subtotal());
                codigoTarifaPrincipal = calculo.codigoTarifaIVA();
            } else {
                totalMercanciasExentas = totalMercanciasExentas.add(calculo.subtotal());
            }
            totalImpuestoBruto = totalImpuestoBruto.add(calculo.montoImpuesto());
            totalExonerado = totalExonerado.add(calculo.montoExonerado());
        }

        BigDecimal totalGravado = totalMercanciasGravadas;
        BigDecimal totalExento = totalMercanciasExentas;
        BigDecimal totalVenta = totalGravado.add(totalExento);
        BigDecimal totalVentaNeta = totalVenta;
        // Impuesto neto de la factura completa: bruto menos exonerado -- MISMA fórmula que
        // FacturaService#crear ya usó para persistir factura.totalImpuesto (sección 4.15.2), así
        // que este valor coincide con factura.getTotalImpuesto() por construcción, no por
        // casualidad (se recalcula aquí en vez de leer el campo persistido para que este
        // generador siga siendo verificable de forma autocontenida a partir de las líneas).
        BigDecimal totalImpuestoNeto = totalImpuestoBruto.subtract(totalExonerado);
        BigDecimal totalComprobante = totalVentaNeta.add(totalImpuestoNeto);

        String codigoMoneda = factura.getMoneda() != null ? factura.getMoneda() : "CRC";
        BigDecimal tipoCambio = factura.getTipoCambio() != null ? factura.getTipoCambio() : BigDecimal.ONE;

        xml.append("<ResumenFactura>");
        xml.append("<CodigoTipoMoneda>");
        xml.append("<CodigoMoneda>").append(codigoMoneda).append("</CodigoMoneda>");
        xml.append("<TipoCambio>").append(fmt(tipoCambio, 5)).append("</TipoCambio>");
        xml.append("</CodigoTipoMoneda>");

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
        xml.append("<TotalVentaNeta>").append(fmt(totalVentaNeta, 5)).append("</TotalVentaNeta>");

        xml.append("<TotalDesgloseImpuesto>");
        xml.append("<Codigo>01</Codigo>");
        xml.append("<CodigoTarifaIVA>").append(codigoTarifaPrincipal).append("</CodigoTarifaIVA>");
        xml.append("<TotalMontoImpuesto>").append(fmt(totalImpuestoNeto, 5)).append("</TotalMontoImpuesto>");
        xml.append("</TotalDesgloseImpuesto>");

        xml.append("<TotalImpuesto>").append(fmt(totalImpuestoNeto, 5)).append("</TotalImpuesto>");

        String codigoMedioPago = factura.getMedioPago() != null ? factura.getMedioPago() : "01";
        xml.append("<MedioPago>");
        xml.append("<TipoMedioPago>").append(codigoMedioPago).append("</TipoMedioPago>");
        xml.append("<TotalMedioPago>").append(fmt(totalComprobante, 5)).append("</TotalMedioPago>");
        xml.append("</MedioPago>");

        xml.append("<TotalComprobante>").append(fmt(totalComprobante, 5)).append("</TotalComprobante>");
        xml.append("</ResumenFactura>");
    }

    // =====================================================================
    // Resolución de entidades relacionadas (sin relaciones JPA vivas -- ver el javadoc de la
    // clase)
    // =====================================================================

    private Producto resolverProducto(LineaFactura linea) {
        return productoRepository.findById(linea.getProductoId())
                .orElseThrow(() -> new IllegalStateException(
                        "Producto no encontrado para línea " + linea.getId() + ": " + linea.getProductoId()));
    }

    /**
     * Cálculo de impuesto de una línea, en un solo lugar para que {@code agregarDetalleFactura} y
     * {@code agregarResumen} nunca puedan divergir en la fórmula.
     */
    private LineaCalculo calcular(LineaFactura linea) {
        BigDecimal subtotal = linea.getSubtotal() != null
                ? linea.getSubtotal().setScale(ESCALA_MONETARIA, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        boolean gravado = linea.isGravadoAplicado();
        BigDecimal porcentajeIVA = gravado
                ? Optional.ofNullable(linea.getPorcentajeImpuestoAplicado()).orElse(BigDecimal.ZERO)
                : BigDecimal.ZERO;
        BigDecimal montoImpuesto = subtotal.multiply(porcentajeIVA).divide(CIEN, ESCALA_MONETARIA, RoundingMode.HALF_UP);
        BigDecimal montoExonerado = linea.getMontoExoneracionAplicado() != null
                ? linea.getMontoExoneracionAplicado().setScale(ESCALA_MONETARIA, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal impuestoNeto = montoImpuesto.subtract(montoExonerado);
        String codigoTarifaIVA = resolverCodigoTarifaIVA(porcentajeIVA);
        return new LineaCalculo(subtotal, gravado, porcentajeIVA, montoImpuesto, montoExonerado, impuestoNeto, codigoTarifaIVA);
    }

    private record LineaContexto(LineaFactura linea, Producto producto, LineaCalculo calculo) {
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

    /**
     * Resuelve el CodigoTarifaIVA según el porcentaje:
     * 10=exento(0%), 03=1%, 04=2%, 05=4%, 06=8%, 07=13%
     */
    private String resolverCodigoTarifaIVA(BigDecimal porcentaje) {
        if (porcentaje == null || porcentaje.compareTo(BigDecimal.ZERO) == 0) return "10";
        if (porcentaje.compareTo(new BigDecimal("1")) == 0) return "03";
        if (porcentaje.compareTo(new BigDecimal("2")) == 0) return "04";
        if (porcentaje.compareTo(new BigDecimal("4")) == 0) return "05";
        if (porcentaje.compareTo(new BigDecimal("8")) == 0) return "06";
        return "07"; // 13% general
    }

    private String fmt(BigDecimal value, int decimals) {
        if (value == null) return "0." + "0".repeat(decimals);
        return value.setScale(decimals, RoundingMode.HALF_UP).toPlainString();
    }

    private String formatearFecha(LocalDateTime fecha) {
        if (fecha == null) throw new IllegalStateException("Fecha no disponible para formatear en el XML");
        return fecha.atOffset(CR_ZONE_OFFSET).format(DATE_FORMATTER);
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
