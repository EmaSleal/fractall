package cr.ac.fractall.facturacion.repositorio;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.transaction.annotation.Transactional;

import cr.ac.fractall.facturacion.modelo.ContadorConsecutivo;
import cr.ac.fractall.facturacion.modelo.ContadorConsecutivoId;
import jakarta.persistence.LockModeType;

/**
 * {@code ContadorConsecutivo} tiene llave compuesta ({@code ContadorConsecutivoId}) donde
 * {@code empresaId} es a la vez componente de esa llave Y el discriminador {@code @TenantId} --
 * a diferencia de {@code ClienteRepository#findByNumeroIdentificacion}, aquí SÍ se pasa
 * {@code empresaId} explícitamente (dentro del {@code id}) en la misma consulta donde Hibernate
 * también aplica su filtro automático de tenant. {@code ContadorConsecutivoAislamientoTest}
 * prueba que ambas restricciones conviven sin sobre- ni sub-restringir la consulta.
 *
 * <p>{@code findById} se sobrescribe para forzar {@code PESSIMISTIC_WRITE}: debe usarse dentro
 * de una transacción activa, nunca fuera de {@code ConsecutivoService#siguienteConsecutivo}.
 * {@code @Transactional(readOnly = false)} explícito es OBLIGATORIO aquí -- sin él,
 * {@code SimpleJpaRepository#findById} aporta su propio {@code @Transactional(readOnly = true)}
 * de fábrica (Spring Data resuelve los atributos transaccionales de los métodos CRUD base desde
 * la implementación, no desde el simple {@code @Lock} en la interfaz), y Postgres rechaza
 * {@code SELECT ... FOR UPDATE} dentro de una transacción de solo lectura.
 */
public interface ContadorConsecutivoRepository extends JpaRepository<ContadorConsecutivo, ContadorConsecutivoId> {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Transactional(readOnly = false)
    Optional<ContadorConsecutivo> findById(ContadorConsecutivoId id);
}
