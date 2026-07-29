package cr.ac.fractall.empresa.repositorio;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cr.ac.fractall.empresa.modelo.CertificadoHacienda;

public interface CertificadoHaciendaRepository extends JpaRepository<CertificadoHacienda, UUID> {

    Optional<CertificadoHacienda> findByEmpresaIdAndAmbiente(UUID empresaId, String ambiente);
}
