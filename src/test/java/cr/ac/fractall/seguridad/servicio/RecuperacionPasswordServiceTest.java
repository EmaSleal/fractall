package cr.ac.fractall.seguridad.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import cr.ac.fractall.seguridad.modelo.SesionRefreshToken;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.modelo.UsuarioToken;
import cr.ac.fractall.seguridad.repositorio.SesionRefreshTokenRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioTokenRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecuperacionPasswordServiceTest {

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private UsuarioTokenRepository usuarioTokenRepository;

    @Mock
    private SesionRefreshTokenRepository sesionRefreshTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private RecuperacionPasswordService recuperacionPasswordService;

    // --- generarTokenSiElegible ---

    @Test
    void generarTokenSiElegibleRetornaResultadoParaUsuarioVerificadoFueraDeVentana() {
        String email = "usuario@test.com";
        UUID usuarioId = UUID.randomUUID();

        Usuario usuario = new Usuario();
        usuario.setEmail(email);
        usuario.setEmailVerificado(true);

        when(usuarioRepository.findByEmail(email)).thenReturn(Optional.of(usuario));

        // Ningún token previo de recuperación — fuera de ventana.
        // usuario.getId() == null (no persistido en test unitario), so matcher with isNull() or any()
        when(usuarioTokenRepository.findFirstByUsuarioIdAndTipoOrderByCreateDateDesc(
                any(), anyString()))
                .thenReturn(Optional.empty());

        Optional<RecuperacionPasswordService.RecuperacionResultado> resultado =
                recuperacionPasswordService.generarTokenSiElegible(email);

        assertThat(resultado).isPresent();
        assertThat(resultado.get().email()).isEqualTo(email);
        assertThat(resultado.get().tokenCrudo()).isNotBlank();

        verify(usuarioTokenRepository).save(any(UsuarioToken.class));
    }

    @Test
    void generarTokenSiElegibleRetornaVacioParaEmailDesconocido() {
        String email = "noexiste@test.com";

        when(usuarioRepository.findByEmail(email)).thenReturn(Optional.empty());

        Optional<RecuperacionPasswordService.RecuperacionResultado> resultado =
                recuperacionPasswordService.generarTokenSiElegible(email);

        assertThat(resultado).isEmpty();
        verify(usuarioTokenRepository, never()).save(any());
    }

    @Test
    void generarTokenSiElegibleRetornaVacioCuandoEmailNoEstaVerificado() {
        String email = "noverificado@test.com";

        Usuario usuario = new Usuario();
        usuario.setEmail(email);
        usuario.setEmailVerificado(false);

        when(usuarioRepository.findByEmail(email)).thenReturn(Optional.of(usuario));

        Optional<RecuperacionPasswordService.RecuperacionResultado> resultado =
                recuperacionPasswordService.generarTokenSiElegible(email);

        assertThat(resultado).isEmpty();
        verify(usuarioTokenRepository, never()).save(any());
    }

    @Test
    void generarTokenSiElegibleRetornaVacioCuandoTokenFueCreadoEnVentanaDeThrottle() {
        String email = "throttled@test.com";

        Usuario usuario = new Usuario();
        usuario.setEmail(email);
        usuario.setEmailVerificado(true);

        // Token creado hace 2 minutos — dentro de la ventana de 5 minutos
        UsuarioToken tokenReciente = new UsuarioToken();
        tokenReciente.setCreateDate(LocalDateTime.now().minusMinutes(2));

        when(usuarioRepository.findByEmail(email)).thenReturn(Optional.of(usuario));
        when(usuarioTokenRepository.findFirstByUsuarioIdAndTipoOrderByCreateDateDesc(
                any(), anyString()))
                .thenReturn(Optional.of(tokenReciente));

        Optional<RecuperacionPasswordService.RecuperacionResultado> resultado =
                recuperacionPasswordService.generarTokenSiElegible(email);

        assertThat(resultado).isEmpty();
        verify(usuarioTokenRepository, never()).save(any());
    }

    // --- restablecer ---

    @Test
    void restablecerEjecutaCuatroPassosAtomicosConTokenValido() {
        String tokenCrudo = "token-valido-para-reset";
        String nuevaPassword = "nuevaPass123";
        String nuevaPasswordHash = "$2a$10$hashBcrypt";
        UUID usuarioId = UUID.randomUUID();

        UsuarioToken token = new UsuarioToken();
        token.setUsado(false);
        token.setTipo("RECUPERACION_PASSWORD");
        token.setUsuarioId(usuarioId);
        token.setExpiraEn(LocalDateTime.now().plusHours(1));

        Usuario usuario = new Usuario();
        usuario.setPasswordHash("hashViejo");
        usuario.setIntentosFallidos(3);
        usuario.setBloqueadaHasta(LocalDateTime.now().plusMinutes(10));
        usuario.setUpdateDate(LocalDateTime.now().minusDays(1));

        SesionRefreshToken sesion1 = new SesionRefreshToken();
        sesion1.setRevocado(false);
        SesionRefreshToken sesion2 = new SesionRefreshToken();
        sesion2.setRevocado(false);

        when(usuarioTokenRepository.findByTokenHashAndUsadoFalseAndExpiraEnAfter(
                anyString(), any(LocalDateTime.class)))
                .thenReturn(Optional.of(token));
        when(usuarioRepository.findById(usuarioId)).thenReturn(Optional.of(usuario));
        when(passwordEncoder.encode(nuevaPassword)).thenReturn(nuevaPasswordHash);
        when(sesionRefreshTokenRepository.findByUsuarioIdAndRevocadoFalse(usuarioId))
                .thenReturn(List.of(sesion1, sesion2));

        boolean resultado = recuperacionPasswordService.restablecer(tokenCrudo, nuevaPassword);

        assertThat(resultado).isTrue();

        // Paso 2: password actualizada
        assertThat(usuario.getPasswordHash()).isEqualTo(nuevaPasswordHash);
        // Paso 3: lockout limpiado
        assertThat(usuario.getIntentosFallidos()).isZero();
        assertThat(usuario.getBloqueadaHasta()).isNull();
        // Paso 1: token marcado como usado
        assertThat(token.isUsado()).isTrue();
        // Paso 4: tokens revocados
        assertThat(sesion1.isRevocado()).isTrue();
        assertThat(sesion1.getRevocadoEn()).isNotNull();
        assertThat(sesion2.isRevocado()).isTrue();
        assertThat(sesion2.getRevocadoEn()).isNotNull();

        verify(usuarioRepository).save(usuario);
        verify(usuarioTokenRepository).save(token);
        verify(sesionRefreshTokenRepository).saveAll(anyList());
    }

    @Test
    void restablecerRetornaFalseConTokenInvalidoOExpirado() {
        String tokenCrudo = "token-inexistente-o-expirado";

        when(usuarioTokenRepository.findByTokenHashAndUsadoFalseAndExpiraEnAfter(
                anyString(), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        boolean resultado = recuperacionPasswordService.restablecer(tokenCrudo, "cualquierPass123");

        assertThat(resultado).isFalse();
        verify(usuarioRepository, never()).save(any());
        verify(sesionRefreshTokenRepository, never()).saveAll(anyList());
    }

    @Test
    void restablecerGuardaTokenHasheadoNuevoAlCrear() {
        String email = "hash-check@test.com";

        Usuario usuario = new Usuario();
        usuario.setEmail(email);
        usuario.setEmailVerificado(true);

        when(usuarioRepository.findByEmail(email)).thenReturn(Optional.of(usuario));
        when(usuarioTokenRepository.findFirstByUsuarioIdAndTipoOrderByCreateDateDesc(
                any(UUID.class), anyString()))
                .thenReturn(Optional.empty());

        recuperacionPasswordService.generarTokenSiElegible(email);

        ArgumentCaptor<UsuarioToken> captor = ArgumentCaptor.forClass(UsuarioToken.class);
        verify(usuarioTokenRepository).save(captor.capture());

        UsuarioToken tokenGuardado = captor.getValue();
        // El token_hash debe ser un SHA256 hex (64 chars), nunca el token crudo
        assertThat(tokenGuardado.getTokenHash()).hasSize(64);
        assertThat(tokenGuardado.getTipo()).isEqualTo("RECUPERACION_PASSWORD");
        assertThat(tokenGuardado.isUsado()).isFalse();
        assertThat(tokenGuardado.getExpiraEn()).isAfter(LocalDateTime.now());
    }
}
