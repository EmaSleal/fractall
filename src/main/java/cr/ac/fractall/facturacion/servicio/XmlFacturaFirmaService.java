package cr.ac.fractall.facturacion.servicio;

import java.util.UUID;

/**
 * Firma digital XAdES-BES del XML de Factura Electrónica v4.4, requisito de Hacienda Costa Rica
 * para que un comprobante generado por {@link XmlFacturaGeneratorService} pueda enviarse (Fase 8,
 * sub-tarea "firma digital").
 *
 * <p>Portado (Categoría A) de
 * {@code docs/proyecto-referencia/erp_spring_manager/.../facturacion/electronica/service/FirmaDigitalService.java}
 * / su {@code impl} -- SOLO {@code firmarXml} y {@code verificarFirma}. Deliberadamente NO se porta
 * {@code obtenerInfoCertificado} (ni su auxiliar {@code extraerCedulaDeCertificado}): es un helper
 * de display de certificado, sin ningún consumidor en el alcance de esta sub-tarea -- la validación
 * del certificado en sí (PIN correcto, keystore abre) ya la cubre por completo
 * {@code EmpresaService#cargarCertificado}/{@code validarPin} desde la Fase 5.
 *
 * <p><b>Diferencia estructural principal frente al original:</b> el original recibe
 * {@code rutaCertificado} (ruta a un archivo {@code .p12} en disco) y {@code pinCertificado} como
 * parámetros de {@code firmarXml}. Este proyecto nunca persiste el {@code .p12} como archivo -- vive
 * cifrado en Postgres ({@code empresa.certificado_p12_cifrado} + {@code empresa.certificado_dek_cifrada},
 * envelope encryption vía la KEK de Transit, Fase 5) y el PIN vive en Vault KV (subruta
 * {@code certificado/pin}, ver {@code EmpresaService}). {@link #firmar(String, UUID)} reemplaza esos
 * dos parámetros de ruta+pin por una resolución interna a partir de {@code empresaId}: descifra el
 * {@code .p12} y lee el PIN antes de firmar -- ver el javadoc de la implementación para el detalle
 * exacto de ese proceso.
 */
public interface XmlFacturaFirmaService {

    /**
     * Firma digitalmente {@code xml} con el certificado {@code .p12} de la empresa indicada.
     *
     * @param xml XML de Factura Electrónica sin firmar (ya generado y validado contra el XSD por
     *     {@link XmlFacturaGeneratorService})
     * @param empresaId id de la {@code Empresa} cuyo certificado {@code .p12} se usa para firmar
     * @return el mismo XML con el bloque {@code <ds:Signature>} (envelope, XAdES-BES) insertado
     * @throws IllegalStateException si no existe una {@code Empresa} con ese id -- invariante
     *     interna, nunca una entrada de usuario inválida (ver el javadoc de
     *     {@code XmlFacturaGeneratorServiceImpl})
     * @throws XmlFacturaFirmaException si la empresa no tiene un certificado {@code .p12} cargado,
     *     el PIN no está en Vault, el keystore/PIN no son válidos, o la firma en sí falla
     */
    String firmar(String xml, UUID empresaId);

    /**
     * Verifica criptográficamente la firma XAdES-BES de un XML ya firmado -- usado en pruebas para
     * demostrar que {@link #firmar(String, UUID)} produce una firma real y verificable; ningún
     * consumidor productivo la invoca todavía dentro del alcance de esta sub-tarea (ver el javadoc
     * de {@code ComprobanteXmlPersistenceService}).
     *
     * @param xmlFirmado XML que debe contener un {@code <ds:Signature>}
     * @return {@code true} si la firma es criptográficamente válida y cubre el contenido real del
     *     documento; {@code false} en cualquier otro caso (sin firma, firma inválida, XML mal
     *     formado, contenido alterado después de firmar)
     */
    boolean verificarFirma(String xmlFirmado);
}
