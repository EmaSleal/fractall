package cr.ac.fractall.hacienda.dto;

/**
 * Códigos de mensaje de respuesta de Hacienda para el estado de un comprobante enviado.
 *
 * <p>Portado tal cual (Categoría A) de
 * {@code docs/proyecto-referencia/erp_spring_manager/.../facturacion/electronica/enums/MensajeHacienda.java}
 * -- ver {@link cr.ac.fractall.hacienda.servicio.HaciendaComprobanteApiService}.
 */
public enum MensajeHacienda {

    // Mensajes de éxito (1xx)
    ACEPTADO("1", "Aceptado"),

    // Estado procesando
    PROCESANDO("0", "En procesamiento"),

    // Mensajes de rechazo (2xx - 3xx)
    RECHAZADO("2", "Rechazado"),
    RECHAZADO_PARCIAL("3", "Aceptación parcial"),

    // Errores de validación XML (100-199)
    ERROR_XML_MAL_FORMADO("100", "XML mal formado"),
    ERROR_CLAVE_NUMERICA("101", "Clave numérica inválida"),
    ERROR_CEDULA_EMISOR("102", "Cédula del emisor inválida"),
    ERROR_CEDULA_RECEPTOR("103", "Cédula del receptor inválida"),
    ERROR_FECHA("104", "Fecha del comprobante inválida"),
    ERROR_CONSECUTIVO("105", "Consecutivo duplicado"),
    ERROR_FIRMA("106", "Firma digital inválida"),
    ERROR_MONTO("107", "Montos no coinciden"),

    // Errores de autenticación (200-299)
    ERROR_AUTENTICACION("200", "Error de autenticación"),
    ERROR_TOKEN_INVALIDO("201", "Token inválido o expirado"),
    ERROR_CREDENCIALES("202", "Credenciales incorrectas"),

    // Errores de servicio (300-399)
    ERROR_SERVICIO("300", "Error en servicio de Hacienda"),
    ERROR_TIMEOUT("301", "Timeout en conexión"),
    ERROR_CONEXION("302", "Error de conexión"),

    // Otros
    ERROR("998", "Error general"),
    DESCONOCIDO("999", "Error desconocido");

    private final String codigo;
    private final String descripcion;

    MensajeHacienda(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getDescripcion() {
        return descripcion;
    }

    /**
     * Obtiene el mensaje desde su código.
     *
     * @param codigo Código del mensaje
     * @return Mensaje correspondiente o DESCONOCIDO
     */
    public static MensajeHacienda fromCodigo(String codigo) {
        for (MensajeHacienda mensaje : values()) {
            if (mensaje.codigo.equals(codigo)) {
                return mensaje;
            }
        }
        return DESCONOCIDO;
    }

    /**
     * Verifica si el mensaje indica éxito.
     */
    public boolean esExitoso() {
        return this == ACEPTADO;
    }

    /**
     * Verifica si el mensaje indica rechazo.
     */
    public boolean esRechazo() {
        return this == RECHAZADO || this == RECHAZADO_PARCIAL;
    }

    /**
     * Verifica si el mensaje indica error técnico.
     */
    public boolean esErrorTecnico() {
        return codigo.startsWith("2") || codigo.startsWith("3");
    }
}
