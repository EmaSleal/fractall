package cr.ac.fractall.seguridad.repositorio;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cr.ac.fractall.seguridad.modelo.InvitacionUsuario;

public interface InvitacionUsuarioRepository extends JpaRepository<InvitacionUsuario, UUID> {

    /**
     * Lookup por hash del token crudo -- ruta caliente de aceptar/registrar-por-invitación.
     * Global (no filtrado por empresa): el token de 256 bits ES la credencial, y el llamante
     * todavía no conoce la empresa que invita en ninguno de los dos caminos de consumo.
     */
    Optional<InvitacionUsuario> findByTokenHash(String tokenHash);

    /**
     * Invitación viva (o en cualquier otro estado puntual) para un correo dentro de una empresa
     * -- usado para verificar duplicados antes de emitir y para resolver la fila pendiente al
     * aceptar. El índice parcial único (V22) ya impone la regla a nivel de motor; este método
     * permite comprobarla antes de intentar el insert.
     */
    Optional<InvitacionUsuario> findByEmpresaIdAndEmailAndEstado(UUID empresaId, String email, String estado);
}
