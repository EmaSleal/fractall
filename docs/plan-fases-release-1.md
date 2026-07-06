# Plan de Implementación por Fases — Release 1 (Factura Electrónica de extremo a extremo)

> Documento complementario a `arquitectura-facturacion-electronica-cr.md`. Este archivo define el **orden de construcción**; el otro documento sigue siendo la fuente de verdad para el **diseño**. Las referencias de sección (ej. "sección 5") apuntan al documento de arquitectura.

---

## Principio rector del orden de fases

El orden no está definido por prioridad de negocio — está definido por **dependencia técnica estricta**. Ninguna fase se adelanta a otra que necesita como cimentación, aunque desde el punto de vista comercial pareciera más urgente. El mecanismo de aislamiento multi-tenant (Fase 1) es la fase más temprana posible, no por dogma de seguridad, sino porque cada fase posterior construye entidades y consultas que dependen de que ese mecanismo ya exista y esté probado.

**Advertencia de alcance, para no perderla de vista durante la implementación:** "Invitación de usuarios adicionales" está deliberadamente **fuera de alcance de Release 1** (sección 8.2 del documento de arquitectura). Lo único dentro de alcance es la asignación automática de `ADMIN_EMPRESA` al registrarse. Si alguna fase empieza a incluir una pantalla de invitación por correo, es una desviación del alcance acordado, no una mejora.

---

## Fase 0 — Cimentación de datos y entorno local

**Trabajo:**
- Traducir el SQL consolidado completo (secciones 4.0–4.15 del documento de arquitectura) a migraciones versionadas de Flyway (`V1__esquema_inicial.sql`, etc.).
- Levantar el `docker-compose.yml` (sección 9.2) en el entorno de desarrollo local, no solo en la VM de producción — nadie debería depender de tener PostgreSQL instalado directamente en su máquina.

**Criterio de salida:** `mvn spring-boot:run` arranca sin errores; Flyway aplica todas las migraciones sobre un esquema vacío sin intervención manual.

---

## Fase 1 — Entidades JPA y mecanismo de aislamiento multi-tenant

**Trabajo:**
- Mapear cada tabla a su entidad JPA, con `TenantAwareEntity` (sección 5.1) como superclase de toda entidad de negocio.
- Implementar `TenantContext` (5.2), `EmpresaTenantIdentifierResolver` (5.3), y la configuración de Hibernate correspondiente.

**Recomendación no negociable como práctica:** escribir, en esta fase y no al final, una prueba de integración con **dos empresas de prueba operando en paralelo**, y ejecutarla en cada build de CI a partir de este punto. El criterio de aceptación de la sección 8.3 del documento de arquitectura no debe ser una verificación manual de una sola vez al cerrar Release 1 — debe ser una prueba automatizada continua. Si el aislamiento se rompe en una fase posterior por una entidad nueva que alguien olvidó extender de `TenantAwareEntity`, se detecta en minutos, no en la revisión final.

**Criterio de salida:** la prueba de dos tenants pasa, incluso sin ninguna funcionalidad de negocio construida todavía — solo con operaciones CRUD triviales sobre entidades de prueba.

---

## Fase 2 — Seguridad base: contraseñas, JWT, y el filtro que conecta autenticación con tenant

**Trabajo:**
- `Argon2PasswordEncoder` configurado (requiere BouncyCastle en el classpath, sección 9.7).
- `JwtService` — firma y verificación de tokens con jjwt.
- `SecurityFilterChain` con rutas públicas (`/auth/**`) y protegidas.
- `JwtTenantFilter` (sección 5.4) poblando `TenantContext` desde el token validado, con el `finally` de limpieza obligatorio.

**Criterio de salida:** un endpoint protegido de prueba rechaza solicitudes sin token y acepta un JWT construido manualmente en un test de integración.

---

## Fase 3 — Cliente de Vault y cifrado en reposo

> **Corrección (2026-07-05):** esta fase estaba numerada como Fase 4, después del flujo de autenticación. Se detectó que la sección 3.3 del documento de arquitectura exige que el secreto TOTP de MFA se cifre "vía la misma KEK de Transit usada para el resto del cifrado en reposo" — es decir, el flujo de autenticación completo (MFA incluido) depende del cliente de Vault, no al revés. Invertido para resolver esa dependencia sin construir un cifrado provisional que luego haya que migrar (el mismo riesgo que esta fase ya rechazaba explícitamente para la Fase 5).

**Trabajo (sección 6 del documento de arquitectura):**
- Autenticación AppRole del backend contra Vault.
- Servicio envoltura para operaciones `datakey` (envelope encryption) y operaciones KV, siguiendo la convención de rutas ya definida (`secret/data/empresas/{empresa_id}/...`).

**Dependencia dura hacia adelante:** esta fase debe completarse *antes* de tocar el cifrado del secreto MFA en la Fase 4, y *antes* de tocar gestión de certificado o credenciales de Hacienda en la Fase 5. No hay forma de construir ninguno de esos flujos de forma responsable sin que el cliente de Vault ya funcione — construirlos a medias generaría un endpoint que aparenta funcionar pero guarda secretos en el lugar equivocado.

---

## Fase 4 — Flujo de autenticación completo

**Trabajo (sección 3 del documento de arquitectura):**
- Registro transaccional: `usuario` + `empresa` + `usuario_empresa` con rol `ADMIN_EMPRESA` en una sola transacción.
- Verificación de correo vía Resend, con el mecanismo de reintento ante el tope diario del proveedor.
- Login con bifurcación (una empresa → JWT directo; 2+ empresas → selección de tenant), cambio de tenant en caliente, bloqueo por fuerza bruta.
- MFA obligatorio para `ADMIN_EMPRESA` — construir aquí, no posponer: el primer usuario que exista en el sistema es, por definición, un `ADMIN_EMPRESA`, así que el onboarding está incompleto sin esto desde el primer usuario real. Ya con el cliente de Vault de la Fase 3 disponible, el secreto TOTP se cifra correctamente desde el primer usuario, sin cifrado provisional.

**Criterio de salida:** una persona se registra, verifica su correo, inicia sesión, y completa el enrolamiento MFA de punta a punta vía un cliente HTTP de prueba (Postman o equivalente).

---

## Fase 5 — Módulo de empresa: máquina de estados y datos habilitantes

**Trabajo:**
- CRUD de configuración fiscal de empresa.
- Carga de certificado (`.p12` + PIN hacia Vault).
- Configuración de credenciales OAuth de Hacienda, ambiente `SANDBOX` únicamente.

**Nota de diseño:** el trigger de cálculo automático de `empresa.status` (sección 4.1) ya vive en la base de datos desde la Fase 0 — en esta fase solo se conecta la capa de servicio a esa máquina de estados, no se reimplementa su lógica en Java.

**Criterio de salida:** una empresa de prueba transita automáticamente de `REGISTRADA` hasta `HABILITADA` sin que ningún código de aplicación tenga que "marcar" el estado manualmente.

---

## Fase 6 — Catálogo: producto y cliente

**Trabajo:**
- CRUD de `producto` con búsqueda CABYS contra `api.hacienda.go.cr` (Categoría A) — sin fallback genérico; la restricción `NOT NULL` de la base de datos ya lo bloquea, aquí se construye la experiencia de captura sobre esa restricción.
- CRUD de `cliente` con validación de identificación por tipo (física, jurídica, DIMEX, NITE).

---

## Fase 7 — Núcleo de facturación: consecutivo, factura, líneas

**Trabajo:**
- Bloqueo pesimista de fila sobre `contador_consecutivo` (sección 4.9), dentro de la misma transacción que crea la `factura` y sus `linea_factura`.

**Criterio de salida:** una prueba de concurrencia real — dos hilos generando factura simultáneamente para la misma empresa — confirma que no hay consecutivos duplicados ni huecos ante un `ROLLBACK` forzado.

---

## Fase 8 — Integración con Hacienda

**Estado de la decisión de librería (resuelto):** la firma XML-DSig se construye sobre `javax.xml.crypto.dsig.*` (JSR 105), API estándar del JDK desde Java 6, sin dependencia externa de Maven. El armado de XAdES-BES se hace con DOM puro. No se agrega `org.apache.santuario:xmlsec` al `pom.xml`.

**Trabajo:**
- Cliente OAuth de Hacienda (Categoría A) y generador XML v4.4 (Categoría A) — sujetos al pendiente ya documentado de compatibilidad con Jakarta EE 11/Jackson 3 (sección 10 del documento de arquitectura).
- Firma digital del comprobante.
- Envío a Hacienda y consulta de estado hasta `ACEPTADO`/`RECHAZADO`, **únicamente en ambiente `SANDBOX`**, tal como fija la hoja de ruta de la sección 8.

---

## Fase 9 — Entrega al cliente final

**Trabajo:**
- Generación de PDF con Apache PDFBox, resolviendo la empresa emisora vía `factura.empresa_id` — nunca repitiendo el defecto de `getEmpresaPrincipal()` del proyecto original.
- Envío del PDF y ambos XML (factura y aceptación) por correo vía Resend.

---

## Fase 10 — Cierre de Release 1

No es una fase de construcción — es la ejecución formal y documentada del criterio de aceptación de la sección 8.3 del documento de arquitectura, con evidencia guardada (no solo "funcionó en mi máquina"). Si la prueba de dos tenants se construyó desde la Fase 1 como parte del pipeline de CI, esta fase es una formalidad de cierre, no un esfuerzo de última hora bajo presión.

---

## Resumen de dependencias entre fases

```
Fase 0 (datos/entorno)
   └─→ Fase 1 (tenant/JPA) ──────────────┐
          └─→ Fase 2 (seguridad base)     │  Prueba de dos tenants
                 └─→ Fase 3 (Vault)        │  corre en CI desde aquí
                        └─→ Fase 4 (auth completo, MFA vía Vault) │  en adelante
                               └─→ Fase 5 (empresa/status)
                                      └─→ Fase 6 (catálogo)
                                             └─→ Fase 7 (consecutivo/factura)
                                                    └─→ Fase 8 (Hacienda)
                                                           └─→ Fase 9 (entrega cliente)
                                                                  └─→ Fase 10 (cierre)
```
