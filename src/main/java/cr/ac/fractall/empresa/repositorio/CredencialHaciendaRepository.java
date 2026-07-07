package cr.ac.fractall.empresa.repositorio;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cr.ac.fractall.empresa.modelo.CredencialHacienda;

public interface CredencialHaciendaRepository extends JpaRepository<CredencialHacienda, UUID> {

    Optional<CredencialHacienda> findByEmpresaIdAndAmbiente(UUID empresaId, String ambiente);
}
