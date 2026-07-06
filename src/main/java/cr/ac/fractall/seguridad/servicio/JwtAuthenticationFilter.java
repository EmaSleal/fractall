package cr.ac.fractall.seguridad.servicio;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Puebla el {@code SecurityContextHolder} a partir de un JWT Bearer, sin exigir
 * permisos todavía (eso llega cuando {@code permisos_efectivos} se incorpore al token,
 * fuera de alcance de esta fase). Un token ausente o inválido no interrumpe la cadena:
 * deja la solicitud sin autenticar y es la regla de {@code authorizeHttpRequests} en
 * {@code SecurityConfig} la que la rechaza con 401 para rutas protegidas.
 *
 * <p>Deliberadamente no es un {@code @Component}: si lo fuera, Spring Boot lo
 * registraría automáticamente como filtro de servlet genérico (fuera de la cadena de
 * Spring Security) además de la instancia registrada manualmente en
 * {@code SecurityConfig#securityFilterChain}, ejecutándolo dos veces por solicitud.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIJO_BEARER = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String token = extraerToken(request);
        // CRÍTICO: cualquier token con un claim "proposito" no nulo (ver JwtService) es un
        // token de alcance mínimo -- selección de tenant o MFA pendiente, hoy; cualquier otro
        // que se agregue después -- y prueba únicamente un paso intermedio del flujo de
        // autenticación, nunca "sesión completa". NO debe autenticar aquí: otorgaría acceso
        // general a la API (cualquier ruta protegida) antes de completar ese paso, una
        // escalada de privilegios real. El chequeo es deliberadamente genérico
        // (esTokenDePropositoEspecial), no uno por tipo de token, para que un tercer token de
        // alcance mínimo futuro quede cubierto sin tener que volver a tocar este filtro. Este
        // chequeo es la contraparte, en este filtro, del fail-closed de resolución de tenant
        // de EmpresaTenantIdentifierResolver.
        if (token != null && jwtService.esValido(token) && !jwtService.esTokenDePropositoEspecial(token)) {
            UUID usuarioId = jwtService.extraerUsuarioId(token);
            var autenticacion = new UsernamePasswordAuthenticationToken(usuarioId, null, List.of());
            SecurityContextHolder.getContext().setAuthentication(autenticacion);
        }
        chain.doFilter(request, response);
    }

    private String extraerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIJO_BEARER)) {
            return header.substring(PREFIJO_BEARER.length());
        }
        return null;
    }
}
