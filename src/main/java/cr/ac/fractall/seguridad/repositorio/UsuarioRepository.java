package cr.ac.fractall.seguridad.repositorio;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cr.ac.fractall.seguridad.modelo.Usuario;

public interface UsuarioRepository extends JpaRepository<Usuario, UUID> {

    // Insensible a mayúsculas a nivel de consulta: aunque el registro ya normaliza el email
    // a minúsculas antes de guardar (ver RegistroService), esta comparación es defensa en
    // profundidad para cualquier fila que exista con otra convención de mayúsculas.
    @Query("SELECT u FROM Usuario u WHERE LOWER(u.email) = LOWER(:email)")
    Optional<Usuario> findByEmail(@Param("email") String email);
}
