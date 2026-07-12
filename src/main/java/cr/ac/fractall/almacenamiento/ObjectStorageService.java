package cr.ac.fractall.almacenamiento;

/**
 * Acceso a Oracle Object Storage (Fase 8, sección 6.4 de
 * {@code arquitectura-facturacion-electronica-cr.md}) -- interfaz propia que aísla el SDK nativo
 * de OCI (mismo principio de aislamiento ya aplicado a {@link cr.ac.fractall.secretos.TransitService}
 * para Vault): el llamador nunca conoce {@code com.oracle.bmc.*} directamente.
 *
 * <p>Inicialmente solo exponía {@link #subir}: ningún flujo de las fases 0-8 necesitaba releer
 * objetos ya subidos, y agregar esa operación antes de que algo la necesitara habría sido
 * superficie de API especulativa. {@link #descargar} se agrega en Fase 9 como extensión
 * consciente del alcance: el flujo de entrega al cliente necesita releer los XMLs cifrados ya
 * almacenados en OCI para adjuntarlos (descifrados) al email del receptor.
 */
public interface ObjectStorageService {

    /**
     * Sube {@code contenido} (ya cifrado por el llamador -- este servicio nunca recibe ni retiene
     * contenido fiscal en claro) a la ruta indicada.
     *
     * @param contenido bytes ya cifrados a almacenar
     * @param rutaObjeto ruta/clave del objeto dentro del bucket configurado (ver
     *     {@code ComprobanteXmlPersistenceService} para la convención de nombres usada por
     *     comprobantes electrónicos)
     * @return la referencia (ruta/clave) del objeto ya almacenado -- normalmente igual a
     *     {@code rutaObjeto}, devuelta explícitamente para que el llamador nunca tenga que asumir
     *     que la subida terminó usando exactamente la ruta solicitada
     */
    String subir(byte[] contenido, String rutaObjeto);

    /**
     * Descarga el blob crudo almacenado en {@code rutaObjeto} y lo retorna tal cual -- sin
     * descifrar. El blob retornado es el mismo que fue subido por {@link #subir}: un objeto
     * cifrado según el layout de {@code ComprobanteXmlBlobFormat} (DEK envuelta + XML cifrado
     * con AES-GCM). El descifrado es responsabilidad exclusiva del llamador (simétrico con
     * {@link #subir}, que recibe contenido ya cifrado). Este servicio nunca recibe ni retorna
     * contenido fiscal en claro.
     *
     * <p>Agregado en Fase 9: extensión consciente del alcance de esta interfaz (ver javadoc de
     * la clase). El primer flujo que lo usa es {@code ComprobanteXmlCifradoDescargador}.
     *
     * @param rutaObjeto ruta/clave del objeto dentro del bucket configurado
     * @return los bytes crudos del objeto almacenado (blob cifrado)
     */
    byte[] descargar(String rutaObjeto);
}
