package cr.ac.fractall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling habilita EmailReintentoScheduledJob (Fase 4, sección 3.1 del
// documento de arquitectura): reintento automático del correo de verificación.
@SpringBootApplication
@EnableScheduling
public class FractallApplication {

	public static void main(String[] args) {
		SpringApplication.run(FractallApplication.class, args);
	}

}
