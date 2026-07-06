package cr.ac.fractall.notificaciones;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ColaReintentoEmailRepository extends JpaRepository<ColaReintentoEmail, UUID> {

    List<ColaReintentoEmail> findByEstadoAndProximoIntentoLessThanEqual(String estado, LocalDateTime ahora);
}
