package cr.ac.fractall.seguridad.repositorio;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cr.ac.fractall.seguridad.modelo.Rol;

public interface RolRepository extends JpaRepository<Rol, UUID> {

    Optional<Rol> findByCodigo(String codigo);
}
