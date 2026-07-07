package cr.ac.fractall.seguridad.controlador;

import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cr.ac.fractall.notificaciones.servicio.EmailNotificacionService;
import cr.ac.fractall.seguridad.dto.AccessTokenResponse;
import cr.ac.fractall.seguridad.dto.LoginRequest;
import cr.ac.fractall.seguridad.dto.MensajeResponse;
import cr.ac.fractall.seguridad.dto.MfaCodigoRequest;
import cr.ac.fractall.seguridad.dto.MfaEnrolamientoResponse;
import cr.ac.fractall.seguridad.dto.MfaPendienteResponse;
import cr.ac.fractall.seguridad.dto.ReenviarVerificacionRequest;
import cr.ac.fractall.seguridad.dto.RefrescarTokenRequest;
import cr.ac.fractall.seguridad.dto.RegistroRequest;
import cr.ac.fractall.seguridad.dto.RegistroResponse;
import cr.ac.fractall.seguridad.dto.SeleccionEmpresaRequest;
import cr.ac.fractall.seguridad.dto.SeleccionTenantRequeridaResponse;
import cr.ac.fractall.seguridad.servicio.CodigoMfaInvalidoException;
import cr.ac.fractall.seguridad.servicio.CredencialesInvalidasException;
import cr.ac.fractall.seguridad.servicio.CuentaBloqueadaException;
import cr.ac.fractall.seguridad.servicio.CuentaNoVerificadaException;
import cr.ac.fractall.seguridad.servicio.JwtService;
import cr.ac.fractall.seguridad.servicio.LoginService;
import cr.ac.fractall.seguridad.servicio.MembresiaInactivaException;
import cr.ac.fractall.seguridad.servicio.MfaNoEnroladoException;
import cr.ac.fractall.seguridad.servicio.MfaService;
import cr.ac.fractall.seguridad.servicio.MfaYaHabilitadoException;
import cr.ac.fractall.seguridad.servicio.RefreshTokenInvalidoException;
import cr.ac.fractall.seguridad.servicio.RegistroDuplicadoException;
import cr.ac.fractall.seguridad.servicio.RegistroService;
import cr.ac.fractall.seguridad.servicio.ReenvioVerificacionRateLimiter;
import cr.ac.fractall.seguridad.servicio.SesionResultado;
import cr.ac.fractall.seguridad.servicio.SesionService;
import cr.ac.fractall.seguridad.servicio.SinEmpresaActivaException;
import cr.ac.fractall.seguridad.servicio.TokensAcceso;
import cr.ac.fractall.seguridad.servicio.VerificacionEmailService;
import cr.ac.fractall.tenant.TenantContextDescartable;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * {@code /auth/registro}, {@code /auth/verificar-email}, {@code /auth/reenviar-verificacion}
 * (sección 3.1); {@code /auth/login}, {@code /auth/seleccionar-tenant},
 * {@code /auth/cambiar-tenant}, {@code /auth/refrescar} (sección 3.2 y 3.4); y
 * {@code /auth/mfa/enrolar}, {@code /auth/mfa/confirmar}, {@code /auth/mfa/verificar}
 * (sección 3.3, batch final de la Fase 4) de {@code arquitectura-facturacion-electronica-cr.md}.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final String PREFIJO_BEARER = "Bearer ";

    private static final String ASUNTO_VERIFICACION = "Verifica tu correo - Fractall";

    private static final MensajeResponse MENSAJE_VERIFICADO =
            new MensajeResponse("Correo verificado correctamente. Ya puedes iniciar sesión.");
    private static final MensajeResponse MENSAJE_TOKEN_INVALIDO =
            new MensajeResponse("El enlace de verificación no es válido o ya expiró.");
    // Misma respuesta sin importar el motivo real (rate-limit por IP, rate-limit por email,
    // email inexistente, o ya verificado) -- anti-enumeración, sección 3.1.
    private static final MensajeResponse MENSAJE_REENVIO_GENERICO =
            new MensajeResponse("Si el correo existe en nuestro sistema y aún no ha sido verificado, "
                    + "se enviará un nuevo enlace de verificación en unos minutos.");

    // Mensajes de login -- cada uno DELIBERADAMENTE distinto entre sí (secciones 3.1, 3.2, 3.4):
    // reutilizar el mismo texto para credenciales inválidas / cuenta no verificada / cuenta
    // bloqueada le ocultaría al usuario legítimo por qué no puede entrar.
    private static final MensajeResponse MENSAJE_CREDENCIALES_INVALIDAS =
            new MensajeResponse("Correo o contraseña incorrectos.");
    private static final MensajeResponse MENSAJE_CUENTA_NO_VERIFICADA =
            new MensajeResponse("Tu cuenta aún no ha sido verificada. Revisa tu correo para activarla.");
    private static final MensajeResponse MENSAJE_CUENTA_BLOQUEADA =
            new MensajeResponse("Tu cuenta está bloqueada temporalmente por múltiples intentos fallidos. "
                    + "Intenta de nuevo más tarde.");
    private static final MensajeResponse MENSAJE_SIN_EMPRESA_ACTIVA =
            new MensajeResponse("Tu cuenta no tiene ninguna empresa activa asociada.");
    private static final MensajeResponse MENSAJE_MEMBRESIA_INACTIVA =
            new MensajeResponse("No tienes acceso activo a la empresa solicitada.");
    private static final MensajeResponse MENSAJE_TOKEN_SELECCION_INVALIDO =
            new MensajeResponse("El token de selección de tenant no es válido o ya expiró.");
    private static final MensajeResponse MENSAJE_SIN_AUTENTICAR =
            new MensajeResponse("No autenticado.");
    private static final MensajeResponse MENSAJE_REFRESH_TOKEN_INVALIDO =
            new MensajeResponse("El refresh token no es válido, expiró o fue revocado.");

    // Mensajes de MFA (sección 3.3) -- mismo criterio que los de login: cada motivo de
    // rechazo con un mensaje distinto.
    private static final MensajeResponse MENSAJE_TOKEN_MFA_PENDIENTE_INVALIDO =
            new MensajeResponse("El token de MFA pendiente no es válido o ya expiró.");
    private static final MensajeResponse MENSAJE_MFA_YA_HABILITADO =
            new MensajeResponse("El MFA ya está habilitado para esta cuenta.");
    private static final MensajeResponse MENSAJE_MFA_NO_ENROLADO =
            new MensajeResponse("Debes completar el enrolamiento MFA antes de confirmarlo.");
    private static final MensajeResponse MENSAJE_CODIGO_MFA_INVALIDO =
            new MensajeResponse("El código MFA no es válido o ya expiró.");

    private final RegistroService registroService;
    private final VerificacionEmailService verificacionEmailService;
    private final EmailNotificacionService emailNotificacionService;
    private final ReenvioVerificacionRateLimiter reenvioVerificacionRateLimiter;
    private final LoginService loginService;
    private final SesionService sesionService;
    private final JwtService jwtService;
    private final MfaService mfaService;

    public AuthController(
            RegistroService registroService,
            VerificacionEmailService verificacionEmailService,
            EmailNotificacionService emailNotificacionService,
            ReenvioVerificacionRateLimiter reenvioVerificacionRateLimiter,
            LoginService loginService,
            SesionService sesionService,
            JwtService jwtService,
            MfaService mfaService) {
        this.registroService = registroService;
        this.verificacionEmailService = verificacionEmailService;
        this.emailNotificacionService = emailNotificacionService;
        this.reenvioVerificacionRateLimiter = reenvioVerificacionRateLimiter;
        this.loginService = loginService;
        this.sesionService = sesionService;
        this.jwtService = jwtService;
        this.mfaService = mfaService;
    }

    @PostMapping("/registro")
    public ResponseEntity<RegistroResponse> registrar(@Valid @RequestBody RegistroRequest request) {
        try {
            RegistroService.RegistroResultado resultado =
                    TenantContextDescartable.ejecutar(() -> registroService.registrar(request));

            // El envío ocurre DESPUÉS de que la transacción de registro ya hizo commit (la
            // llamada anterior ya retornó): un fallo de Resend nunca debe revertir el
            // registro. enviarConReintento absorbe ese fallo encolando un reintento.
            TenantContextDescartable.ejecutar((Runnable) () -> emailNotificacionService.enviarConReintento(
                    resultado.email(), ASUNTO_VERIFICACION, construirHtmlVerificacion(resultado.tokenCrudo())));

            RegistroResponse cuerpo = new RegistroResponse(resultado.usuarioId(), resultado.empresaId(),
                    "Registro exitoso. Revisa tu correo para verificar la cuenta.");
            return ResponseEntity.status(HttpStatus.CREATED).body(cuerpo);
        } catch (RegistroDuplicadoException excepcion) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new RegistroResponse(null, null, excepcion.getMessage()));
        }
    }

    @GetMapping("/verificar-email")
    public ResponseEntity<MensajeResponse> verificarEmail(@RequestParam String token) {
        boolean verificado = TenantContextDescartable.ejecutar(() -> verificacionEmailService.verificar(token));
        if (verificado) {
            return ResponseEntity.ok(MENSAJE_VERIFICADO);
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(MENSAJE_TOKEN_INVALIDO);
    }

    @PostMapping("/reenviar-verificacion")
    public ResponseEntity<MensajeResponse> reenviarVerificacion(
            @Valid @RequestBody ReenviarVerificacionRequest request,
            HttpServletRequest httpRequest) {
        String ip = resolverIp(httpRequest);

        if (reenvioVerificacionRateLimiter.permitido(ip)) {
            Optional<VerificacionEmailService.ReenvioResultado> resultado = TenantContextDescartable.ejecutar(
                    () -> verificacionEmailService.generarTokenReenvioSiElegible(request.email()));

            resultado.ifPresent(r -> TenantContextDescartable.ejecutar((Runnable) () ->
                    emailNotificacionService.enviarConReintento(
                            r.email(), ASUNTO_VERIFICACION, construirHtmlVerificacion(r.tokenCrudo()))));
        }

        return ResponseEntity.ok(MENSAJE_REENVIO_GENERICO);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        try {
            LoginService.LoginResultado resultado = TenantContextDescartable.ejecutar(
                    () -> loginService.login(request.email(), request.password()));

            if (resultado.requiereSeleccionTenant()) {
                return ResponseEntity.ok(new SeleccionTenantRequeridaResponse(resultado.tokenSeleccionTenant()));
            }
            if (resultado.requiereMfa()) {
                return ResponseEntity.ok(
                        new MfaPendienteResponse(resultado.tokenMfaPendiente(), resultado.mfaRequiereEnrolamiento()));
            }
            return ResponseEntity.ok(aRespuesta(resultado.tokens()));
        } catch (CuentaNoVerificadaException excepcion) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(MENSAJE_CUENTA_NO_VERIFICADA);
        } catch (CuentaBloqueadaException excepcion) {
            return ResponseEntity.status(HttpStatus.LOCKED).body(MENSAJE_CUENTA_BLOQUEADA);
        } catch (CredencialesInvalidasException excepcion) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(MENSAJE_CREDENCIALES_INVALIDAS);
        } catch (SinEmpresaActivaException excepcion) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(MENSAJE_SIN_EMPRESA_ACTIVA);
        }
    }

    @PostMapping("/seleccionar-tenant")
    public ResponseEntity<?> seleccionarTenant(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @Valid @RequestBody SeleccionEmpresaRequest request) {
        String token = extraerBearer(authorizationHeader);
        // Validado aquí, NO vía SecurityContextHolder: JwtAuthenticationFilter deja este
        // token deliberadamente sin autenticar (ver su javadoc) porque es de alcance
        // mínimo -- este endpoint es precisamente el único lugar autorizado a aceptarlo, así
        // que lo parsea y valida por su cuenta.
        if (token == null || !jwtService.esValido(token) || !jwtService.esTokenSeleccionTenant(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(MENSAJE_TOKEN_SELECCION_INVALIDO);
        }

        UUID usuarioId = jwtService.extraerUsuarioId(token);
        try {
            SesionResultado resultado = TenantContextDescartable.ejecutar(
                    () -> sesionService.seleccionarTenant(usuarioId, request.empresaId()));
            if (resultado.requiereMfa()) {
                return ResponseEntity.ok(
                        new MfaPendienteResponse(resultado.tokenMfaPendiente(), resultado.mfaRequiereEnrolamiento()));
            }
            return ResponseEntity.ok(aRespuesta(resultado.tokens()));
        } catch (MembresiaInactivaException excepcion) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(MENSAJE_MEMBRESIA_INACTIVA);
        }
    }

    @PostMapping("/cambiar-tenant")
    public ResponseEntity<?> cambiarTenant(@Valid @RequestBody SeleccionEmpresaRequest request) {
        Optional<UUID> usuarioId = usuarioIdAutenticado();
        if (usuarioId.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(MENSAJE_SIN_AUTENTICAR);
        }

        try {
            // "Invalida el token de la empresa actual" (sección 3.2 punto 3): con access
            // tokens de vida corta (15 min) y sin blacklist server-side en este diseño (solo
            // el refresh token es revocable), no hay nada que invalidar activamente del lado
            // del servidor para el token anterior -- la propia corta expiración ES la
            // mitigación aceptada. Inventar un blacklist aquí sería contradecir esa decisión
            // de arquitectura, no reforzarla.
            TokensAcceso resultado = TenantContextDescartable.ejecutar(
                    () -> sesionService.cambiarTenant(usuarioId.get(), request.empresaId()));
            return ResponseEntity.ok(aRespuesta(resultado));
        } catch (MembresiaInactivaException excepcion) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(MENSAJE_MEMBRESIA_INACTIVA);
        }
    }

    @PostMapping("/refrescar")
    public ResponseEntity<?> refrescar(@Valid @RequestBody RefrescarTokenRequest request) {
        try {
            TokensAcceso resultado = TenantContextDescartable.ejecutar(
                    () -> sesionService.refrescar(request.refreshToken(), request.empresaId()));
            return ResponseEntity.ok(aRespuesta(resultado));
        } catch (RefreshTokenInvalidoException excepcion) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(MENSAJE_REFRESH_TOKEN_INVALIDO);
        } catch (MembresiaInactivaException excepcion) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(MENSAJE_MEMBRESIA_INACTIVA);
        }
    }

    @PostMapping("/mfa/enrolar")
    public ResponseEntity<?> mfaEnrolar(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        String token = extraerBearer(authorizationHeader);
        if (!esTokenMfaPendienteValido(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(MENSAJE_TOKEN_MFA_PENDIENTE_INVALIDO);
        }

        UUID usuarioId = jwtService.extraerUsuarioId(token);
        try {
            MfaService.EnrolamientoResultado resultado =
                    TenantContextDescartable.ejecutar(() -> mfaService.enrolar(usuarioId));
            return ResponseEntity.ok(new MfaEnrolamientoResponse(resultado.qrCodeBase64Png(), resultado.secretoBase32()));
        } catch (MfaYaHabilitadoException excepcion) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(MENSAJE_MFA_YA_HABILITADO);
        }
    }

    @PostMapping("/mfa/confirmar")
    public ResponseEntity<?> mfaConfirmar(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @Valid @RequestBody MfaCodigoRequest request) {
        String token = extraerBearer(authorizationHeader);
        if (!esTokenMfaPendienteValido(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(MENSAJE_TOKEN_MFA_PENDIENTE_INVALIDO);
        }

        UUID usuarioId = jwtService.extraerUsuarioId(token);
        UUID empresaId = jwtService.extraerEmpresaId(token);
        try {
            TokensAcceso resultado = TenantContextDescartable.ejecutar(
                    () -> mfaService.confirmar(usuarioId, empresaId, request.codigo()));
            return ResponseEntity.ok(aRespuesta(resultado));
        } catch (MfaNoEnroladoException excepcion) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(MENSAJE_MFA_NO_ENROLADO);
        } catch (CodigoMfaInvalidoException excepcion) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(MENSAJE_CODIGO_MFA_INVALIDO);
        }
    }

    @PostMapping("/mfa/verificar")
    public ResponseEntity<?> mfaVerificar(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @Valid @RequestBody MfaCodigoRequest request) {
        String token = extraerBearer(authorizationHeader);
        if (!esTokenMfaPendienteValido(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(MENSAJE_TOKEN_MFA_PENDIENTE_INVALIDO);
        }

        UUID usuarioId = jwtService.extraerUsuarioId(token);
        UUID empresaId = jwtService.extraerEmpresaId(token);
        try {
            TokensAcceso resultado = TenantContextDescartable.ejecutar(
                    () -> mfaService.verificar(usuarioId, empresaId, request.codigo()));
            return ResponseEntity.ok(aRespuesta(resultado));
        } catch (MfaNoEnroladoException excepcion) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(MENSAJE_MFA_NO_ENROLADO);
        } catch (CodigoMfaInvalidoException excepcion) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(MENSAJE_CODIGO_MFA_INVALIDO);
        }
    }

    private AccessTokenResponse aRespuesta(TokensAcceso tokens) {
        return new AccessTokenResponse(tokens.accessToken(), tokens.refreshToken(), tokens.empresaId());
    }

    /**
     * Validado aquí, NO vía {@code SecurityContextHolder} -- mismo motivo que
     * {@link #seleccionarTenant}: el token "MFA pendiente" es de alcance mínimo y
     * {@code JwtAuthenticationFilter}/{@code JwtTenantFilter} lo dejan deliberadamente sin
     * autenticar (ver {@code JwtService#esTokenDePropositoEspecial}). Los 3 endpoints
     * {@code /auth/mfa/*} son los únicos autorizados a aceptarlo, y lo hacen chequeando el
     * propósito exacto -- un token de selección de tenant NO pasa este chequeo, ni viceversa,
     * a pesar de que ambos son tokens "de alcance mínimo".
     */
    private boolean esTokenMfaPendienteValido(String token) {
        return token != null && jwtService.esValido(token) && jwtService.esTokenMfaPendiente(token);
    }

    /**
     * Lee el {@code usuarioId} ya autenticado por {@code JwtAuthenticationFilter} para
     * {@code /auth/cambiar-tenant} -- sin volver a parsear el header, a propósito (sección
     * 3.2 punto 3 pide reusar la sesión ya vigente sin contraseña). {@link Optional#empty()}
     * cubre tanto "sin autenticar" como "autenticado con un token de selección de tenant"
     * (que JwtAuthenticationFilter deja pasar sin poblar el contexto de seguridad, ver su
     * javadoc) -- en ambos casos el principal no es un {@link UUID}.
     */
    private Optional<UUID> usuarioIdAutenticado() {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacion == null || !autenticacion.isAuthenticated()
                || !(autenticacion.getPrincipal() instanceof UUID usuarioId)) {
            return Optional.empty();
        }
        return Optional.of(usuarioId);
    }

    private String extraerBearer(String authorizationHeader) {
        if (authorizationHeader != null && authorizationHeader.startsWith(PREFIJO_BEARER)) {
            return authorizationHeader.substring(PREFIJO_BEARER.length());
        }
        return null;
    }

    private String construirHtmlVerificacion(String tokenCrudo) {
        String enlace = "https://app.fractall.cr/auth/verificar-email?token=" + tokenCrudo;
        return "<p>Gracias por registrarte en Fractall. Confirma tu correo con el siguiente enlace:</p>"
                + "<p><a href=\"" + enlace + "\">" + enlace + "</a></p>"
                + "<p>Este enlace expira en 24 horas.</p>";
    }

    private String resolverIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
