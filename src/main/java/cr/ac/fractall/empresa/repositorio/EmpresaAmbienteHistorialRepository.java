package cr.ac.fractall.empresa.repositorio;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cr.ac.fractall.empresa.modelo.EmpresaAmbienteHistorial;

public interface EmpresaAmbienteHistorialRepository extends JpaRepository<EmpresaAmbienteHistorial, UUID> {
}
