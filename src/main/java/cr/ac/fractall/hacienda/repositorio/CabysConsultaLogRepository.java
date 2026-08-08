package cr.ac.fractall.hacienda.repositorio;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cr.ac.fractall.hacienda.modelo.CabysConsultaLog;

public interface CabysConsultaLogRepository extends JpaRepository<CabysConsultaLog, UUID> {

    List<CabysConsultaLog> findByProcesadoFalse();
}
