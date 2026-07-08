package cr.ac.fractall.almacenamiento;

/**
 * Acceso a Oracle Object Storage (Fase 8, sección 6.4 de
 * {@code arquitectura-facturacion-electronica-cr.md}) -- interfaz propia que aísla el SDK nativo
 * de OCI (mismo principio de aislamiento ya aplicado a {@link cr.ac.fractall.secretos.TransitService}
 * para Vault): el llamador nunca conoce {@code com.oracle.bmc.*} directamente.
 *
 * <p>Solo subida -- deliberadamente sin un método {@code descargar}/{@code leer}: ningún flujo de
 * este release necesita releer un objeto ya subido (ver el javadoc de
 * {@code cr.ac.fractall.facturacion.servicio.ComprobanteXmlPersistenceService} para el contenido
 * que sí se sube). Agregar esa operación antes de que algo la necesite sería superficie de API
 * especulativa.
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
}
