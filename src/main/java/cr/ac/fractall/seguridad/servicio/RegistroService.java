package cr.ac.fractall.seguridad.servicio;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.seguridad.modelo.Rol;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.modelo.UsuarioEmpresa;
import cr.ac.fractall.seguridad.modelo.UsuarioToken;
import cr.ac.fractall.seguridad.dto.RegistroRequest;
import cr.ac.fractall.seguridad.repositorio.RolRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioEmpresaRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioTokenRepository;

/**
 * Registro transaccional de {@code usuario} + {@code empresa} + {@code usuario_empresa}
 * (sección 3.1, punto 1). Atomicidad obligatoria: si cualquier paso falla, todo el método
 * hace rollback -- nunca debe quedar un {@code usuario} huérfano sin su {@code empresa}.
 *
 * <p>El llamador (ver {@code AuthController}) es responsable de fijar
 * {@link cr.ac.fractall.tenant.TenantContext} ANTES de invocar {@link #registrar}, vía
 * {@link cr.ac.fractall.tenant.TenantContextDescartable} -- este método corre detrás de un
 * endpoint público sin JWT, así que nunca hay un tenant real que resolver todavía.
 *
 * <p>El envío del correo de verificación queda deliberadamente FUERA de este método: el
 * llamador debe invocarlo solo después de que esta transacción ya hizo commit, para que un
 * fallo de Resend nunca revierta el registro (ver {@code AuthController#registrar}).
 */
@Service
public class RegistroService {

    /** 256 bits de entropía -- SecureRandom, no UUID (sección 3.1: "token aleatorio criptográficamente seguro"). */
    private static final int TOKEN_BYTES = 32;

    /**
     * 24h: el documento de arquitectura sugiere un rango de 24-48h para la expiración del
     * token de verificación de correo; se elige el extremo más corto para minimizar la
     * ventana en la que un enlace interceptado (ej. en un correo reenviado sin querer)
     * sigue siendo válido.
     */
    private static final long EXPIRACION_HORAS = 24;

    private static final String ROL_ADMIN_EMPRESA = "ADMIN_EMPRESA";

    private final UsuarioRepository usuarioRepository;
    private final EmpresaRepository empresaRepository;
    private final RolRepository rolRepository;
    private final UsuarioEmpresaRepository usuarioEmpresaRepository;
    private final UsuarioTokenRepository usuarioTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    public RegistroService(
            UsuarioRepository usuarioRepository,
            EmpresaRepository empresaRepository,
            RolRepository rolRepository,
            UsuarioEmpresaRepository usuarioEmpresaRepository,
            UsuarioTokenRepository usuarioTokenRepository,
            PasswordEncoder passwordEncoder) {
        this.usuarioRepository = usuarioRepository;
        this.empresaRepository = empresaRepository;
        this.rolRepository = rolRepository;
        this.usuarioEmpresaRepository = usuarioEmpresaRepository;
        this.usuarioTokenRepository = usuarioTokenRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public RegistroResultado registrar(RegistroRequest request) {
        // Normalizado aquí (no solo a nivel de consulta en UsuarioRepository) para que todo
        // registro nuevo quede almacenado en una única convención -- evita que
        // "Test@x.com" y "test@x.com" se traten como cuentas distintas.
        String email = request.email().trim().toLowerCase();

        if (usuarioRepository.findByEmail(email).isPresent()) {
            throw new RegistroDuplicadoException("Ya existe una cuenta registrada con ese correo");
        }

        Rol rolAdminEmpresa = rolRepository.findByCodigo(ROL_ADMIN_EMPRESA)
                .orElseThrow(() -> new IllegalStateException(
                        "Rol " + ROL_ADMIN_EMPRESA + " no encontrado -- revisar migraciones de Fase 0 (V1__esquema_base.sql)"));

        LocalDateTime ahora = LocalDateTime.now();

        Usuario usuario = new Usuario();
        usuario.setNombre(request.nombre());
        usuario.setEmail(email);
        usuario.setPasswordHash(passwordEncoder.encode(request.password()));
        usuario.setEmailVerificado(false);
        usuario.setEstado("PENDIENTE_VERIFICACION");
        usuario.setMfaHabilitado(false);
        usuario.setIntentosFallidos(0);
        usuario.setCreateDate(ahora);
        usuario.setUpdateDate(ahora);
        usuario = usuarioRepository.save(usuario);

        Empresa empresa = new Empresa();
        empresa.setRazonSocial(request.razonSocial());
        empresa.setAmbienteHacienda("SANDBOX");
        empresa.setStatus("REGISTRADA");
        empresa.setCreadoPor(usuario.getId());
        empresa.setCreateDate(ahora);
        empresa.setUpdateDate(ahora);
        empresa = empresaRepository.save(empresa);

        UsuarioEmpresa membresia = new UsuarioEmpresa();
        membresia.setUsuarioId(usuario.getId());
        membresia.setEmpresaId(empresa.getId());
        membresia.setRolId(rolAdminEmpresa.getId());
        membresia.setEstado("ACTIVO");
        membresia.setFechaIngreso(ahora);
        usuarioEmpresaRepository.save(membresia);

        String tokenCrudo = generarTokenCrudo();
        UsuarioToken tokenVerificacion = new UsuarioToken();
        tokenVerificacion.setUsuarioId(usuario.getId());
        tokenVerificacion.setTipo("VERIFICACION_EMAIL");
        tokenVerificacion.setTokenHash(TokenHasher.sha256Hex(tokenCrudo));
        tokenVerificacion.setExpiraEn(ahora.plusHours(EXPIRACION_HORAS));
        tokenVerificacion.setUsado(false);
        tokenVerificacion.setCreateDate(ahora);
        usuarioTokenRepository.save(tokenVerificacion);

        return new RegistroResultado(usuario.getId(), empresa.getId(), usuario.getEmail(), tokenCrudo);
    }

    private String generarTokenCrudo() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** {@code tokenCrudo} es de un solo uso por el llamador: enviarlo por correo y descartarlo. */
    public record RegistroResultado(UUID usuarioId, UUID empresaId, String email, String tokenCrudo) {
    }
}
