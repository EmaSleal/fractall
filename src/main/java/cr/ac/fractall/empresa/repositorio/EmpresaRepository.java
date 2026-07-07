package cr.ac.fractall.empresa.repositorio;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cr.ac.fractall.empresa.modelo.Empresa;

public interface EmpresaRepository extends JpaRepository<Empresa, UUID> {
}
