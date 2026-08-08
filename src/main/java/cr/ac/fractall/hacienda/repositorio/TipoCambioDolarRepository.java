package cr.ac.fractall.hacienda.repositorio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import cr.ac.fractall.hacienda.modelo.TipoCambioDolar;

public interface TipoCambioDolarRepository extends JpaRepository<TipoCambioDolar, LocalDate> {

    /**
     * Upsert idempotente ("insertar si no existe todavía"): dos peticiones concurrentes que
     * ambas caen en cache-miss para el mismo día (ver
     * {@code HaciendaConsultaServiceImpl#consultarTipoCambioDolar}) pueden ambas intentar
     * cachear la fila de hoy casi al mismo tiempo. Un {@code save()} normal (vía
     * {@code entityManager.persist}) haría que la segunda choque contra la PK natural
     * ({@code fecha}) y lance {@code DataIntegrityViolationException} -- una excepción que no
     * tiene nada que ver con la operación de negocio que disparó la consulta (p. ej. crear una
     * factura), y que además ya tiene un manejador GENÉRICO en {@code GlobalExceptionHandler}
     * (409 "recurso duplicado") pensado para el caso real de un cliente duplicando un recurso de
     * negocio -- no para una carrera contra un cache interno.
     *
     * <p>{@code ON CONFLICT (fecha) DO NOTHING} expresa la intención real directamente en SQL
     * (mismo idioma que ya usa {@code V15__catalogo_ubicacion_cr.sql} para su seed idempotente)
     * en vez de intentar-e-interpretar una excepción de integridad como control de flujo: quien
     * pierda la carrera simplemente no escribe nada y no falla -- el llamador ya tiene en mano la
     * respuesta válida de Hacienda (sea la suya o, de hecho, el mismo valor que el otro
     * hilo obtuvo, porque Hacienda publica un único valor por día) y la devuelve igual.
     *
     * <p>{@code @Transactional} explícito porque, a diferencia de los métodos heredados de
     * {@code SimpleJpaRepository} (que Spring Data ya envuelve en transacción internamente), un
     * método {@code @Query} propio NO la recibe gratis -- sin esta anotación, invocarlo fuera de
     * un {@code @Transactional} ambiente (p. ej. desde {@code TipoCambioController} o
     * {@code TipoCambioScheduledJob}, que no abren transacción propia) lanzaría
     * {@code TransactionRequiredException}.
     */
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO tipo_cambio_dolar (fecha, venta, compra, consultado_en)
            VALUES (:fecha, :venta, :compra, :consultadoEn)
            ON CONFLICT (fecha) DO NOTHING
            """, nativeQuery = true)
    void guardarSiNoExiste(
            @Param("fecha") LocalDate fecha,
            @Param("venta") BigDecimal venta,
            @Param("compra") BigDecimal compra,
            @Param("consultadoEn") LocalDateTime consultadoEn);
}
