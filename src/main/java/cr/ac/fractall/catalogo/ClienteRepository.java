package cr.ac.fractall.catalogo;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@code Cliente} extiende {@link cr.ac.fractall.tenant.TenantAwareEntity}: el filtro por
 * {@code empresa_id} (@{@code TenantId}) lo aplica Hibernate automáticamente a CUALQUIER
 * consulta emitida en esta sesión -- incluida {@link #findByNumeroIdentificacion(String)} --
 * mismo mecanismo ya confirmado empíricamente para {@code findById} en la Fase 5 (un id de otro
 * tenant resuelve "no encontrado", no una excepción de seguridad aparte). Por eso este finder
 * NO recibe {@code empresaId} como parámetro explícito, a diferencia de
 * {@code CredencialHaciendaRepository#findByEmpresaIdAndAmbiente} -- esa entidad extiende
 * {@code EntidadBase}, no {@code TenantAwareEntity}, y por lo tanto no tiene filtro automático.
 */
public interface ClienteRepository extends JpaRepository<Cliente, UUID> {

    Optional<Cliente> findByNumeroIdentificacion(String numeroIdentificacion);
}
