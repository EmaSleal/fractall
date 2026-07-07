package cr.ac.fractall.catalogo;

import lombok.Getter;

/**
 * Tipos de identificación según el Ministerio de Hacienda de Costa Rica.
 * Utilizados para clasificar personas físicas, jurídicas, DIMEX y NITE.
 *
 * <p>Portado de
 * {@code docs/proyecto-referencia/erp_spring_manager/.../configuracion/enums/TipoIdentificacion.java}
 * (Categoría A) sin cambios de comportamiento -- {@code tipoIdentificacion} es siempre entrada
 * explícita en este proyecto (nunca inferido por longitud de dígitos), así que
 * {@link #validarNumero(String)} dado un tipo ya conocido es la regla correcta aquí (ver sección
 * 4.11 de {@code arquitectura-facturacion-electronica-cr.md}).
 *
 * <p>Referencia: Anexo 1 del documento de Facturación Electrónica CR v4.4.
 */
@Getter
public enum TipoIdentificacion {

    /**
     * 01 - Cédula Física
     * Identificación para personas físicas costarricenses
     * Formato: 9 dígitos (ej: 107890123)
     */
    CEDULA_FISICA("01", "Cédula Física", 9),

    /**
     * 02 - Cédula Jurídica
     * Identificación para empresas y personas jurídicas
     * Formato: 10 dígitos (ej: 3101234567)
     */
    CEDULA_JURIDICA("02", "Cédula Jurídica", 10),

    /**
     * 03 - DIMEX (Documento de Identidad Migratorio para Extranjeros)
     * Identificación para residentes extranjeros
     * Formato: 11 o 12 dígitos
     */
    DIMEX("03", "DIMEX", 12),

    /**
     * 04 - NITE (Número de Identificación Tributario Especial)
     * Identificación para extranjeros no residentes que realizan actividades económicas
     * Formato: 10 dígitos
     */
    NITE("04", "NITE", 10);

    private final String codigo;
    private final String descripcion;
    private final Integer longitudMaxima;

    TipoIdentificacion(String codigo, String descripcion, Integer longitudMaxima) {
        this.codigo = codigo;
        this.descripcion = descripcion;
        this.longitudMaxima = longitudMaxima;
    }

    /**
     * Obtiene el tipo de identificación desde su código.
     *
     * @param codigo Código del tipo (01-04)
     * @return Tipo de identificación correspondiente
     * @throws IllegalArgumentException si el código no es válido
     */
    public static TipoIdentificacion fromCodigo(String codigo) {
        for (TipoIdentificacion tipo : values()) {
            if (tipo.codigo.equals(codigo)) {
                return tipo;
            }
        }
        throw new IllegalArgumentException("Código de tipo de identificación inválido: " + codigo);
    }

    /**
     * Valida si un número de identificación es válido para este tipo.
     *
     * @param numero Número de identificación a validar
     * @return true si es válido, false en caso contrario
     */
    public boolean validarNumero(String numero) {
        if (numero == null || numero.isEmpty()) {
            return false;
        }

        // Remover guiones y espacios
        String numeroLimpio = numero.replaceAll("[\\s-]", "");

        // Validar que solo contenga dígitos
        if (!numeroLimpio.matches("\\d+")) {
            return false;
        }

        // Validar longitud según el tipo
        switch (this) {
            case CEDULA_FISICA:
                return numeroLimpio.length() == 9;
            case CEDULA_JURIDICA:
            case NITE:
                return numeroLimpio.length() == 10;
            case DIMEX:
                return numeroLimpio.length() == 11 || numeroLimpio.length() == 12;
            default:
                return false;
        }
    }

    /**
     * Verifica si el tipo corresponde a una persona física.
     *
     * @return true si es persona física (cédula física o DIMEX)
     */
    public boolean esPersonaFisica() {
        return this == CEDULA_FISICA || this == DIMEX;
    }

    /**
     * Verifica si el tipo corresponde a una persona jurídica.
     *
     * @return true si es persona jurídica (cédula jurídica o NITE)
     */
    public boolean esPersonaJuridica() {
        return this == CEDULA_JURIDICA || this == NITE;
    }
}
