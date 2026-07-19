package cr.ac.fractall.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import cr.ac.fractall.seguridad.servicio.JwtAuthenticationFilter;
import cr.ac.fractall.seguridad.servicio.JwtService;
import cr.ac.fractall.tenant.JwtTenantFilter;

/**
 * Cadena de filtros de Spring Security y hashing de contraseñas (Fase 2 — infraestructura
 * de seguridad base, sin endpoints de registro/login todavía, eso es Fase 3).
 *
 * <p>Sesión sin estado y sin CSRF: la API es JWT puro, sin cookies de sesión que un CSRF
 * pudiera explotar. {@code /auth/**} queda público a propósito para que Fase 3 pueda
 * agregar registro/login sin tocar esta configuración; todo lo demás exige autenticación.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService) throws Exception {
        JwtAuthenticationFilter jwtAuthenticationFilter = new JwtAuthenticationFilter(jwtService);
        JwtTenantFilter jwtTenantFilter = new JwtTenantFilter(jwtService);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(autorizacion -> autorizacion
                        .requestMatchers("/auth/**", "/actuator/health").permitAll()
                        .anyRequest().authenticated())
                // Sin esto, Spring Security no tiene ningún mecanismo de autenticación
                // (no hay httpBasic/formLogin) y cae de vuelta a Http403ForbiddenEntryPoint
                // por defecto -- 403 en lugar del 401 correcto para "no autenticado" en
                // una API JWT sin sesión.
                .exceptionHandling(manejo -> manejo.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(jwtTenantFilter, JwtAuthenticationFilter.class);

        return http.build();
    }
}
