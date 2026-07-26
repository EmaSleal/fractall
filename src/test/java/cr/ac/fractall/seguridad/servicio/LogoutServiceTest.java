package cr.ac.fractall.seguridad.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyList;
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

import cr.ac.fractall.seguridad.modelo.SesionRefreshToken;
import cr.ac.fractall.seguridad.repositorio.SesionRefreshTokenRepository;

@ExtendWith(MockitoExtension.class)
class LogoutServiceTest {

    @Mock
    private SesionRefreshTokenRepository sesionRefreshTokenRepository;

    @InjectMocks
    private LogoutService logoutService;

    @Test
    void logoutSingleRevocaUnicoToken() {
        UUID usuarioId = UUID.randomUUID();
        String tokenCrudo = "token-crudo-de-prueba-1234567890abcdef";
        String hash = TokenHasher.sha256Hex(tokenCrudo);

        SesionRefreshToken token = new SesionRefreshToken();
        token.setUsuarioId(usuarioId);
        token.setTokenHash(hash);
        token.setRevocado(false);
        token.setEmitidoEn(LocalDateTime.now().minusDays(1));
        token.setExpiraEn(LocalDateTime.now().plusDays(6));

        when(sesionRefreshTokenRepository.findByTokenHashAndRevocadoFalseAndExpiraEnAfter(
                org.mockito.ArgumentMatchers.eq(hash), org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(Optional.of(token));

        logoutService.logout(tokenCrudo, false, usuarioId);

        assertThat(token.isRevocado()).isTrue();
        assertThat(token.getRevocadoEn()).isNotNull();
        verify(sesionRefreshTokenRepository).save(token);
    }

    @Test
    void logoutTodasSesionesRevocaTodosLosTokensDelUsuario() {
        UUID usuarioId = UUID.randomUUID();
        String tokenCrudo = "cualquier-token-crudo";

        SesionRefreshToken token1 = new SesionRefreshToken();
        token1.setUsuarioId(usuarioId);
        token1.setRevocado(false);
        token1.setEmitidoEn(LocalDateTime.now().minusDays(2));
        token1.setExpiraEn(LocalDateTime.now().plusDays(5));

        SesionRefreshToken token2 = new SesionRefreshToken();
        token2.setUsuarioId(usuarioId);
        token2.setRevocado(false);
        token2.setEmitidoEn(LocalDateTime.now().minusDays(1));
        token2.setExpiraEn(LocalDateTime.now().plusDays(6));

        when(sesionRefreshTokenRepository.findByUsuarioIdAndRevocadoFalse(usuarioId))
                .thenReturn(List.of(token1, token2));

        logoutService.logout(tokenCrudo, true, usuarioId);

        assertThat(token1.isRevocado()).isTrue();
        assertThat(token1.getRevocadoEn()).isNotNull();
        assertThat(token2.isRevocado()).isTrue();
        assertThat(token2.getRevocadoEn()).isNotNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SesionRefreshToken>> captor = ArgumentCaptor.forClass(List.class);
        verify(sesionRefreshTokenRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    void logoutSingleEsIdempotenteCuandoTokenNoExiste() {
        UUID usuarioId = UUID.randomUUID();
        String tokenCrudo = "token-que-no-existe-o-ya-revocado";
        String hash = TokenHasher.sha256Hex(tokenCrudo);

        when(sesionRefreshTokenRepository.findByTokenHashAndRevocadoFalseAndExpiraEnAfter(
                org.mockito.ArgumentMatchers.eq(hash), org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        // Debe completar sin error — idempotente
        assertThatNoException().isThrownBy(() -> logoutService.logout(tokenCrudo, false, usuarioId));
        verify(sesionRefreshTokenRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void logoutTodasSesionesNoLanzaErrorCuandoNoHayTokensActivos() {
        UUID usuarioId = UUID.randomUUID();

        when(sesionRefreshTokenRepository.findByUsuarioIdAndRevocadoFalse(usuarioId))
                .thenReturn(List.of());

        assertThatNoException().isThrownBy(() -> logoutService.logout("cualquier-token", true, usuarioId));
        verify(sesionRefreshTokenRepository, never()).saveAll(anyList());
    }
}
