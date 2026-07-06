package cr.ac.fractall.empresa;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CredencialHaciendaRepository extends JpaRepository<CredencialHacienda, UUID> {

    Optional<CredencialHacienda> findByEmpresaIdAndAmbiente(UUID empresaId, String ambiente);
}
