package cr.ac.fractall.facturacion.servicio;

import java.security.SecureRandom;
import java.time.LocalDateTime;

/**
 * Generador de la clave numérica de 50 dígitos de un comprobante electrónico, según la
 * especificación de Hacienda de Costa Rica -- Categoría A (se porta tal cual, sección 2 de
 * {@code arquitectura-facturacion-electronica-cr.md}), portado de
 * {@code docs/proyecto-referencia/erp_spring_manager/.../util/ClaveNumericaGenerator.java}.
 *
 * <p>Formato (50 dígitos): {@code [País(3)][Día(2)][Mes(2)][Año(2)][Cédula emisor(12)]
 * [Consecutivo(20)][Situación(1)][Código de seguridad(8)]}. A diferencia del original, que
 * recibía un {@code TipoComprobanteElectronico} (enum inexistente en este proyecto), aquí el
 * tipo de comprobante se acepta como {@code String} de 2 caracteres -- mismo patrón ya usado por
 * {@code Producto}/{@code ComprobanteElectronico}/{@code ContadorConsecutivo} para códigos
 * VARCHAR(2) de catálogos oficiales de Hacienda. El relleno de cédula vía
 * {@code String.format("%012d", ...)} es agnóstico al tipo de identificación (física, jurídica,
 * DIMEX, NITE): es puro relleno de ceros a la izquierda, sin ninguna rama por tipo.
 *
 * <p>{@link #formatearConsecutivo(long, String)} se expone como método público independiente
 * porque {@code FacturaService} necesita construir ese MISMO segmento de 20 dígitos para
 * persistirlo en {@code comprobante_electronico.consecutivo} -- ambos valores deben ser
 * byte-idénticos, nunca dos formateos independientes que puedan divergir.
 */
public final class ClaveNumericaGenerator {

    private static final String PAIS_COSTA_RICA = "506";

    /** Sucursal y terminal fijas: Release 1 no modela múltiples puntos de venta (sección 8.1). */
    private static final String SUCURSAL_DEFECTO = "001";
    private static final String TERMINAL_DEFECTO = "00001";

    /** Situación 1 = normal. 2 (contingencia) y 3 (sin internet) quedan fuera de Release 1. */
    private static final String SITUACION_NORMAL = "1";

    private static final int LONGITUD_CLAVE = 50;

    private static final SecureRandom RANDOM = new SecureRandom();

    private ClaveNumericaGenerator() {
    }

    /**
     * Genera la clave numérica completa de 50 dígitos para un comprobante.
     *
     * @param cedula        número de identificación del emisor (empresa), sin guiones ni otros
     *                       separadores -- se limpian aquí igual que en el original.
     * @param consecutivo    valor YA reclamado de {@code ConsecutivoService#siguienteConsecutivo}.
     * @param tipoComprobante código de 2 caracteres del catálogo de Hacienda (p. ej. {@code "01"}).
     * @param fechaEmision   fecha/hora de emisión del comprobante.
     */
    public static String generar(String cedula, long consecutivo, String tipoComprobante, LocalDateTime fechaEmision) {
        StringBuilder clave = new StringBuilder(LONGITUD_CLAVE);

        clave.append(PAIS_COSTA_RICA);
        clave.append(String.format("%02d", fechaEmision.getDayOfMonth()));
        clave.append(String.format("%02d", fechaEmision.getMonthValue()));
        clave.append(String.format("%02d", fechaEmision.getYear() % 100));

        String cedulaFormateada = cedula.replaceAll("[^0-9]", "");
        clave.append(String.format("%012d", Long.parseLong(cedulaFormateada)));

        clave.append(formatearConsecutivo(consecutivo, tipoComprobante));
        clave.append(SITUACION_NORMAL);
        clave.append(generarCodigoSeguridad());

        String claveGenerada = clave.toString();
        if (claveGenerada.length() != LONGITUD_CLAVE) {
            throw new IllegalStateException(
                    "Clave numérica generada con longitud incorrecta: " + claveGenerada.length() + " caracteres");
        }
        return claveGenerada;
    }

    /**
     * Segmento de 20 dígitos: {@code sucursal(3) + terminal(5) + tipoComprobante(2) + numero(10)}.
     * Ver el javadoc de la clase sobre por qué este método es público.
     */
    public static String formatearConsecutivo(long numero, String tipoComprobante) {
        return SUCURSAL_DEFECTO + TERMINAL_DEFECTO + tipoComprobante + String.format("%010d", numero);
    }

    /** Código de seguridad aleatorio de 8 dígitos (no criptográficamente vinculante, solo anti-colisión). */
    private static String generarCodigoSeguridad() {
        int codigo = 10_000_000 + RANDOM.nextInt(90_000_000);
        return String.valueOf(codigo);
    }

    /** Validación estructural básica -- longitud, solo dígitos, prefijo de país. */
    public static boolean validar(String claveNumerica) {
        return claveNumerica != null
                && claveNumerica.length() == LONGITUD_CLAVE
                && claveNumerica.matches("\\d{50}")
                && claveNumerica.startsWith(PAIS_COSTA_RICA);
    }
}
