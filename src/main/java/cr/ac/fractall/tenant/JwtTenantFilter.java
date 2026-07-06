package cr.ac.fractall.tenant;

import java.io.IOException;
import java.util.UUID;

import org.springframework.web.filter.OncePerRequestFilter;

import cr.ac.fractall.seguridad.servicio.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Puebla {@link TenantContext} a partir del mismo JWT Bearer ya validado por
 * {@code JwtAuthenticationFilter}, en cada request.
 *
 * <p>El {@code finally} que limpia el contexto es la línea más importante de esta clase
 * (sección 5.4 de {@code arquitectura-facturacion-electronica-cr.md}): el contenedor de
 * servlets reutiliza hilos entre solicitudes, así que sin esa limpieza, el hilo que
 * atendió a la Empresa A podría atender a la Empresa B en el siguiente request sobre el
 * mismo hilo.
 *
 * <p>Deliberadamente no es un {@code @Component}: ver la nota equivalente en
 * {@code JwtAuthenticationFilter} sobre el doble registro de filtros de servlet.
 */
public class JwtTenantFilter extends OncePerRequestFilter {

    private static final String PREFIJO_BEARER = "Bearer ";

    private final JwtService jwtService;

    public JwtTenantFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        try {
            String token = extraerToken(request);
            // Un token de selección de tenant (ver JwtService/JwtAuthenticationFilter) no
            // trae claim "empresaId" -- llamar extraerEmpresaId sobre él lanzaría una
            // excepción no controlada en CADA solicitud que lo presente, incluida la propia
            // solicitud a POST /auth/seleccionar-tenant. El token de MFA pendiente SÍ trae
            // empresaId (la empresa ya está resuelta en ese punto del flujo, ver JwtService),
            // pero tampoco debe resolver tenant aquí: MFA todavía no terminó, así que ninguna
            // operación de negocio sobre esa empresa debería ejecutarse todavía bajo este
            // token. Se usa el mismo chequeo genérico que JwtAuthenticationFilter
            // (esTokenDePropositoEspecial) para que ambos filtros traten cualquier token de
            // alcance mínimo, presente o futuro, de forma consistente.
            if (token != null && jwtService.esValido(token) && !jwtService.esTokenDePropositoEspecial(token)) {
                UUID empresaId = jwtService.extraerEmpresaId(token);
                TenantContext.set(empresaId);
            }
            chain.doFilter(request, response);
        } finally {
            // OBLIGATORIO: ver el javadoc de la clase.
            TenantContext.clear();
        }
    }

    private String extraerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIJO_BEARER)) {
            return header.substring(PREFIJO_BEARER.length());
        }
        return null;
    }
}
