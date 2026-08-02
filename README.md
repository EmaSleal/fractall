# Fractall

Backend de facturación electrónica para Costa Rica, integrado con Hacienda (Ministerio de Hacienda). Multi-tenant, con autenticación JWT + MFA, gestión de certificados `.p12` por empresa y ambiente (Sandbox/Producción), y firma/envío de comprobantes electrónicos.

## Stack

- **Java 21** / **Spring Boot 4**
- **PostgreSQL** (persistencia) + **Flyway** (migraciones)
- **HashiCorp Vault** (gestión de secretos y certificados)
- **Spring Security** + **JJWT** (autenticación JWT, MFA)
- **Resilience4j** (circuit breaker / retry para llamadas a Hacienda)
- **OCI Object Storage** (almacenamiento de archivos)
- **springdoc-openapi** (documentación OpenAPI/Swagger)
- **PDFBox**, **ZXing** (generación de PDF y códigos QR de comprobantes)
- **Testcontainers** (PostgreSQL, Vault) para pruebas de integración

## Estructura del proyecto

Paquete raíz: `cr.ac.fractall`

| Módulo | Responsabilidad |
|---|---|
| `facturacion` | Emisión y gestión de comprobantes electrónicos |
| `hacienda` | Integración con la API del Ministerio de Hacienda (autenticación, envío, consulta) |
| `empresa` | Datos de empresa emisora, certificados `.p12` por ambiente |
| `catalogo` | Catálogo de productos/servicios (CABYS) |
| `tenant` | Soporte multi-tenant |
| `seguridad` | Autenticación, JWT, MFA |
| `secretos` | Integración con Vault |
| `notificaciones` | Envío de correos (Resend) |
| `almacenamiento` | Integración con OCI Object Storage |
| `config` | Configuración transversal (caché, seguridad, clientes REST, etc.) |
| `shared` | Utilidades compartidas |

## Requisitos previos

- JDK 21
- Docker y Docker Compose (para PostgreSQL y Vault en desarrollo)
- Maven Wrapper incluido (`mvnw` / `mvnw.cmd`), no requiere Maven instalado

## Configuración

1. Copiar `.env.example` a `.env` y completar las variables (credenciales de base de datos, Vault, Resend, OCI, etc.).
2. Levantar los servicios de desarrollo:

   ```bash
   docker compose up -d
   ```

## Ejecutar la aplicación

```bash
./mvnw spring-boot:run
```

## Pruebas

```bash
./mvnw test
```

## Build

```bash
./mvnw clean package
```

## Documentación adicional

- [Arquitectura de facturación electrónica en CR](docs/arquitectura-facturacion-electronica-cr.md)
- [Plan de fases de release 1](docs/plan-fases-release-1.md)
- Documentación OpenAPI disponible en `/swagger-ui.html` (o `/v3/api-docs`) con la app corriendo.

Ver también [AGENTS.md](AGENTS.md) para lineamientos de trabajo en el repositorio.
