# Arquitectura y Esquema — Plataforma de Facturación Electrónica Multi-Tenant (Costa Rica)

> Documento vivo de trabajo. Consolida las decisiones de arquitectura y el SQL acordado hasta la fecha. Los puntos marcados como **PENDIENTE** no están resueltos todavía y no deben asumirse como decididos.

---

## 1. Contexto y alcance del producto

Aplicación nueva e independiente (no es una extensión del ERP original de una sola empresa). Historia de usuario base:

1. Una persona se registra y queda automáticamente como administradora (`ADMIN_EMPRESA`) de su propia empresa (tenant).
2. Puede agregar usuarios a su empresa, cada uno con permisos verificables por función.
3. Gestiona un catálogo de productos/servicios, con búsqueda de código CABYS integrada contra la API de Hacienda (`api.hacienda.go.cr`).
4. Usuarios y administradores con el permiso correspondiente pueden generar una factura de forma sencilla.
5. La factura se envía a Hacienda; al ser aceptada, se entrega al cliente final: PDF, XML de factura y XML de aceptación de Hacienda.

## 2. Decisiones de arquitectura ya cerradas

| Decisión | Resolución |
|---|---|
| Origen del código | Repositorio nuevo e independiente. El proyecto original (mono-tenant) queda como referencia de solo lectura. |
| Reutilización de código | **Categoría A (se porta tal cual):** firma digital XML-DSig, generador XML v4.4, cliente OAuth de Hacienda, búsqueda CABYS. **Categoría B (se rediseña):** modelo de datos, consecutivos, validaciones pre-envío. |
| Firma digital XML-DSig | Sin dependencia externa: se construye sobre `javax.xml.crypto.dsig.*` (JSR 105), API estándar del JDK desde Java 6 — la implementación de referencia (Apache Santuario embebido) viene incluida en el propio JDK, no como artefacto Maven gestionado. El armado de XAdES-BES se hace con DOM puro, sin librería XAdES de terceros. Fuera del alcance del pendiente de compatibilidad Jakarta EE 11/Jackson 3 (sección 10): `javax.xml.crypto` y `org.w3c.dom` son API de Java SE, no de Jakarta EE — nunca migraron de espacio de nombres. Contrapartida documentada: actualizar el algoritmo de firma queda atado al ciclo de versiones del JDK, no a una librería independiente actualizable por separado. |
| Estrategia multi-tenant | Esquema compartido (*shared schema*) con `empresa_id` como discriminador por fila. Descartado esquema-por-tenant por sobre-ingeniería para el volumen esperado. |
| Identificadores | UUID v7 en todas las tablas. Ordenable a nivel de índice B-tree, no adivinable entre tenants (a diferencia de IDs incrementales), sin la fragmentación de índice de UUID v4 puro. |
| Motor de base de datos | **PostgreSQL 18+** (requerido específicamente por soporte nativo de `uuidv7()`; versiones anteriores no lo tienen). Autoalojado en la misma VM de cómputo. |
| Cifrado en reposo | Envelope encryption: una KEK maestra + DEKs por tenant almacenadas como blobs cifrados en la propia base de datos. El costo no escala con el número de empresas. |
| Motor de cifrado/secretos | HashiCorp Vault Community Edition (autoalojado, gratuito), motor **Transit**. |
| Sellado operativo de Vault | Auto-unseal respaldado por GCP KMS (sin tarifa fija mensual, solo centavos por operación de arranque). Elimina dependencia de un operador humano para desbloquear Vault en cada reinicio. |
| Llaves de recuperación de Vault | 3 fragmentos Shamir (umbral 2), repartidos por **medio de almacenamiento** (gestor de contraseñas cifrado, USB cifrado en caja fuerte física, copia impresa en custodia bancaria/notarial) — diseño para operador único, no para resistencia a colusión entre varias personas. |
| Gobernanza de continuidad | Sobre de contingencia sellado en custodia de abogado de confianza, con instrucciones de a quién contactar y qué hacer ante indisponibilidad del operador único. **Sin acceso funcional al sistema mientras el operador esté disponible** — no es una cuarta persona con credenciales activas, es un protocolo de contingencia inerte. Consistente con el diseño de operador único de las llaves Shamir; otorgar acceso funcional permanente habría contradicho esa decisión. |
| Hosting | VM Oracle Cloud Ampere A1 Flex (4 OCPU / 24 GB RAM), cuenta Pay As You Go, confirmada exenta del recorte de junio 2026 mediante evidencia directa de consola. Alerta de presupuesto activa como mecanismo de detección temprana. |
| Requisito derivado de infraestructura | La aplicación no debe acoplarse rígidamente a esta VM: configuración por variables de entorno, contenedores en lugar de instalación directa sobre el sistema operativo. |
| Relación usuario–empresa | Muchos a muchos (un usuario puede pertenecer a N empresas, ej. un contador externo o un empleado a medio tiempo en dos negocios). |
| Catálogo de roles | **Global**, no personalizable por tenant (decisión alineada al mercado objetivo: personas individuales y pequeños negocios sin capacidad de administrar un constructor de roles). Flexibilidad fina se logra vía excepciones puntuales sobre `usuario_empresa`, no vía roles nuevos. |
| Consecutivos de comprobantes | Bloqueo pesimista de fila (`SELECT ... FOR UPDATE`) dentro de la misma transacción de la factura. Descartada secuencia nativa (`SERIAL`) por dejar huecos permanentes ante `ROLLBACK`, lo cual es inaceptable para numeración fiscal consecutiva. |
| Consecutivos por ambiente | Sandbox y producción llevan series de consecutivo **independientes** por empresa (ver corrección en sección 4.1). |
| Transición sandbox → producción | Requiere permiso crítico exclusivo de `ADMIN_EMPRESA` (`empresa.gestionar_ambiente_hacienda`), validación dura de estado `HABILITADA` + credenciales de producción configuradas, y fricción de confirmación por reescritura (no checkbox). |
| Transición producción → sandbox | Permitida por `ADMIN_EMPRESA` sin intervención de `SUPER_ADMIN`. Sin bloqueo técnico a nivel de trigger, pero con la misma fricción de confirmación y registro de auditoría — no exime del histórico fiscal ya generado en producción. |
| Autenticación | JWT con flujo de selección de tenant activo (ver sección 3). Access token de vida corta (10-15 min) + refresh token revocable en base de datos. |
| Autoescalamiento de permisos | Bloqueado a nivel de motor de base de datos (trigger), no solo en el backend — ningún usuario puede modificar sus propios permisos ni otorgarse permisos críticos por excepción puntual. |
| Resolución de tenant | `@TenantId` de Hibernate + `CurrentTenantIdentifierResolver` respaldado por un `ThreadLocal` propio (`TenantContext`), poblado por un filtro JWT en cada request. Fail-closed: sin `empresa_id` en contexto, la consulta se bloquea, no se ejecuta sin filtro. No cubre SQL nativo/procedimientos almacenados ni hilos de `@Async`/jobs programados — requiere propagación explícita en esos casos (ver sección 5). |
| Modelo de facturación | `factura` (orden comercial) separada de `comprobante_electronico` (envoltorio fiscal) — ciclos de vida independientes. `ambiente_hacienda` y los datos de impuesto en `linea_factura` son snapshots congelados al momento de la venta, nunca recalculados en vivo contra `empresa`/`producto`, para no corromper retroactivamente el historial fiscal ya aceptado por Hacienda. |
| Integridad cruzada entre tenants | Trigger de validación en `factura` y `linea_factura`: una FK por sí sola no impide que un recurso de un tenant sea referenciado desde otro; se bloquea a nivel de motor, no solo de aplicación. |
| Convención de secretos en Vault | Una sola llave maestra en Transit (envelope encryption vía `datakey`, no una llave por tenant) + namespacing explícito por empresa en KV v2 (`secret/data/empresas/{empresa_id}/...`). Control de acceso centralizado en una sola identidad de aplicación (AppRole), no una política de Vault por tenant — ver sección 6. |
| Versión de stack | Java 21 LTS (`eclipse-temurin`) + Spring Boot 4.1.x. Descartado continuar sobre Spring Boot 3.x: esa rama llegó a fin de soporte de código abierto el 30 de junio de 2026. Prioriza madurez de ecosistema sobre el horizonte de soporte más largo de Java 25 LTS, dado el manejo de obligaciones fiscales reales. |
| Contenerización | Docker Compose con `app` + `postgres` + `vault` + `Caddy` (proxy inverso con TLS automático vía Let's Encrypt) en la VM Ampere A1. Red interna aislada (`internal: true`) para PostgreSQL y Vault — inalcanzables desde internet. Backend de Vault: Raft integrado, no `file`. Versionado de esquema vía Flyway, no aplicación manual de SQL. Sin pgAdmin permanente — acceso administrativo vía túnel SSH o perfil bajo demanda — ver sección 9. |
| Dependencias Maven del backend | Apache PDFBox (Apache 2.0) en lugar de iText (AGPLv3 — exige divulgar el código fuente de la aplicación bajo despliegue en red, o licencia comercial de costo no publicado). TOTP implementado sobre `javax.crypto` (RFC 6238) en lugar de `dev.samstevens.totp` (sin publicaciones desde 2021); ZXing solo para la imagen QR de enrolamiento. `spring-vault-core` y los starters `-test` de Spring Boot 4.x verificados contra Maven Central antes de incluirlos — ver sección 9.7. |
| Almacenamiento de documentos | `.p12` como blob cifrado en PostgreSQL (mismo patrón que el secreto MFA). XML/PDF de comprobantes en Oracle Object Storage (Always Free, 20 GB, sin fecha de expiración) — no en la base de datos, dado su crecimiento sin límite. Cifrado propio vía Transit antes de subir, independiente del cifrado del proveedor. Acceso vía SDK nativo de OCI con autenticación por Instance Principal (sin credenciales estáticas adicionales), aislado det| Impuesto de producto y exoneraciones | `producto.porcentaje_impuesto` se deriva del campo `impuesto` de la API de CABYS de Hacienda, nunca tecleado a mano. Exoneraciones modeladas como autorización reutilizable por cliente (`cliente_exoneracion`), no como campo de producto/factura — snapshot congelado en `linea_factura` al aplicarse. Catálogo oficial de 12 tipos (Nota 10.1, Anexos v4.4); los códigos 01/05/06/07 quedan bloqueados a nivel de trigger para Factura Electrónica por ser exclusivos de Nota de Crédito/Débito — ver sección 4.15. |
| Autenticación de usuario | Argon2id para hash de contraseña (recomendación vigente de OWASP). Registro bloqueado hasta verificación de correo (`usuario.estado = PENDIENTE_VERIFICACION`) — sin acceso funcional parcial antes de verificar. MFA (TOTP) obligatorio para `ADMIN_EMPRESA`, opcional para los demás roles; secreto cifrado a nivel de columna vía Transit, no consultado a Vault en cada login. Bloqueo temporal automático tras intentos fallidos consecutivos. Ver sección 3. |
| Proveedor de correo transaccional | **Resend** (nivel gratuito: 3,000/mes, tope 100/día, costo cero verificado incluyendo tráfico de red saliente). Descartados: Amazon SES (nivel gratuito solo aplica desde EC2, no desde esta VM), SendGrid (nivel gratuito permanente retirado), Gmail SMTP (no apto para envío desde aplicación). Preferido sobre Brevo/Mailtrap (topes diarios más altos) por ser infraestructura 100% transaccional, sin riesgo de reputación de IP compartida con tráfico de marketing ajeno. Mailgun documentado como alternativa de reemplazo de mismo perfil de riesgo si el tope diario resulta insuficiente en la práctica. Exige subdominio dedicado + SPF/DKIM/DMARC y un mecanismo de reintento propio ante el tope diario — ver sección 3.1. |

## 3. Flujo de autenticación y registro (completo)

### 3.1 Registro e incorporación

1. `POST /auth/registro` crea, en una sola transacción: la fila en `usuario` (`estado = 'PENDIENTE_VERIFICACION'`, `email_verificado = false`), la fila en `empresa`, y la membresía en `usuario_empresa` con rol `ADMIN_EMPRESA`. Atomicidad obligatoria — evita una cuenta de usuario huérfana sin empresa si algún paso intermedio falla.
2. Se genera un token aleatorio criptográficamente seguro; se guarda su **hash** en `usuario_token` (`tipo = 'VERIFICACION_EMAIL'`, expiración 24-48h); el token **crudo** se envía por correo transaccional vía **Resend** (nivel gratuito: 3,000 correos/mes, tope de 100/día — ver justificación y reevaluación de alternativas en la bitácora de decisiones de proveedor, sección 2). Envío sobre subdominio dedicado (`correo.sudominio.com`), nunca el dominio principal, con SPF/DKIM/DMARC configurados antes del primer envío de producción — requisito no negociable para evitar que los correos de verificación caigan en spam. Si el envío falla por el tope diario del proveedor, se encola y reintenta automáticamente (mecanismo específico para este flujo, distinto de `ComprobanteReintentosJob`, que permanece fuera de alcance de Release 1 — ver sección 8.2).
3. `GET /auth/verificar-email?token=...`: calcula el hash del token recibido, busca coincidencia con `usado = false` y `expira_en > now()`. Si coincide: `usuario.email_verificado = true`, `usuario.estado = 'ACTIVA'`, `usuario_token.usado = true` — misma transacción.
4. `POST /auth/reenviar-verificacion`: límite de tasa obligatorio por email y por IP de origen (ej. un reenvío cada 5 minutos) — sin esto, es un vector gratuito de bombardeo de una casilla de correo ajena.

**Bloqueo de acceso hasta verificación:** `POST /auth/login` rechaza explícitamente cualquier intento con `usuario.estado = 'PENDIENTE_VERIFICACION'`, con mensaje distinto al de credenciales inválidas. No se permite acceso parcial ni funcional antes de verificar — el riesgo de que alguien opere una empresa (invitar usuarios, intentar facturar) bajo un correo no confirmado no se acepta a cambio de una fricción de onboarding menor.

### 3.2 Login y selección de tenant

1. **Login** (`POST /auth/login`): valida credenciales (Argon2id) contra `usuario`. Si tiene una sola empresa activa en `usuario_empresa`, emite JWT completo directamente.
2. **Selección de tenant** (si tiene 2+ empresas): se emite un token de alcance mínimo (solo `usuario_id`, sin `empresa_id` ni permisos) válido únicamente contra `POST /auth/seleccionar-tenant`, que emite el JWT completo tras verificar la membresía elegida.
3. **Cambio de tenant en caliente** (`POST /auth/cambiar-tenant`): invalida el token de la empresa actual y emite uno nuevo para la empresa destino, sin requerir contraseña de nuevo.
4. **SUPER_ADMIN**: flujo de autenticación elevado y separado, no vive dentro del esquema `usuario_empresa`.

### 3.3 MFA (TOTP)

**Obligatorio para cualquier membresía con rol `ADMIN_EMPRESA`** — consistente con la fricción deliberada ya exigida para acciones críticas de ese rol (confirmación por reescritura, permisos `critico = true`). Opcional, pero disponible, para `EMPLEADO_FACTURACION` y `CONSULTA`. El secreto TOTP se cifra a nivel de columna (`usuario.mfa_secret_cifrado`) vía la misma KEK de Transit usada para el resto del cifrado en reposo — no vía una consulta a Vault en cada login, dado que MFA se verifica en la ruta más transitada del sistema y una llamada de red adicional ahí no se justifica.

### 3.4 Bloqueo por fuerza bruta

`usuario.intentos_fallidos` se incrementa en cada fallo de login; al alcanzar el umbral (propuesto: 5 intentos), se establece `usuario.bloqueada_hasta` (propuesto: 15 minutos) y se rechazan intentos hasta esa marca de tiempo. El contador se reinicia en cada login exitoso. Esta lógica debe vivir en el servicio de autenticación, no repetirse si en el futuro se agrega un segundo punto de entrada (ej. API key para integraciones).

## 4. Esquema SQL consolidado

Orden de creación respetando dependencias de llaves foráneas. Requiere PostgreSQL 18+.

### 4.0 Tabla `usuario` y tablas de sesión/tokens

```sql
CREATE TABLE usuario (
    id                    UUID PRIMARY KEY DEFAULT uuidv7(),
    nombre                VARCHAR(255) NOT NULL,
    email                 VARCHAR(255) UNIQUE NOT NULL,
    password_hash         VARCHAR(255) NOT NULL,              -- Argon2id, nunca en texto plano
    email_verificado      BOOLEAN NOT NULL DEFAULT false,
    estado                VARCHAR(20) NOT NULL DEFAULT 'PENDIENTE_VERIFICACION'
                             CHECK (estado IN ('PENDIENTE_VERIFICACION', 'ACTIVA', 'BLOQUEADA', 'SUSPENDIDA')),
    mfa_habilitado        BOOLEAN NOT NULL DEFAULT false,
    mfa_secret_cifrado    BYTEA,        -- blob cifrado vía Vault Transit (datakey), NUNCA el secreto TOTP en claro
    intentos_fallidos     INT NOT NULL DEFAULT 0,
    bloqueada_hasta       TIMESTAMP,     -- bloqueo temporal automático tras N intentos fallidos
    ultimo_login          TIMESTAMP,
    create_date           TIMESTAMP NOT NULL DEFAULT now(),
    update_date           TIMESTAMP NOT NULL DEFAULT now()
);

-- Refresh tokens revocables, uno-a-muchos: permite sesiones simultáneas
-- en distintos dispositivos sin que una invalide a la otra.
CREATE TABLE sesion_refresh_token (
    id          UUID PRIMARY KEY DEFAULT uuidv7(),
    usuario_id  UUID NOT NULL REFERENCES usuario(id),
    token_hash  VARCHAR(255) NOT NULL UNIQUE,   -- hash del token, nunca el token crudo
    dispositivo VARCHAR(255),
    ip_origen   VARCHAR(45),
    emitido_en  TIMESTAMP NOT NULL DEFAULT now(),
    expira_en   TIMESTAMP NOT NULL,
    revocado    BOOLEAN NOT NULL DEFAULT false,
    revocado_en TIMESTAMP
);

-- Consolida verificación de email y recuperación de contraseña:
-- ambos son "token de un solo uso con expiración", no dos conceptos distintos.
CREATE TABLE usuario_token (
    id          UUID PRIMARY KEY DEFAULT uuidv7(),
    usuario_id  UUID NOT NULL REFERENCES usuario(id),
    tipo        VARCHAR(25) NOT NULL CHECK (tipo IN ('VERIFICACION_EMAIL', 'RECUPERACION_PASSWORD')),
    token_hash  VARCHAR(255) NOT NULL UNIQUE,
    expira_en   TIMESTAMP NOT NULL,
    usado       BOOLEAN NOT NULL DEFAULT false,
    create_date TIMESTAMP NOT NULL DEFAULT now()
);
```


### 4.1 `empresa` y su historial de estado

```sql
CREATE TABLE empresa (
    id                     UUID PRIMARY KEY DEFAULT uuidv7(),
    razon_social           VARCHAR(255) NOT NULL,
    nombre_comercial       VARCHAR(255),
    numero_identificacion  VARCHAR(20) UNIQUE,
    tipo_identificacion    VARCHAR(2),
    codigo_actividad       VARCHAR(6),
    codigo_provincia       VARCHAR(1),
    canton                 VARCHAR(2),
    distrito               VARCHAR(2),
    barrio                 VARCHAR(100),
    otras_senas            VARCHAR(300),
    telefono               VARCHAR(20),
    email                  VARCHAR(255),
    ambiente_hacienda      VARCHAR(10) NOT NULL DEFAULT 'SANDBOX'
                               CHECK (ambiente_hacienda IN ('SANDBOX', 'PRODUCCION')),
    certificado_referencia VARCHAR(255),  -- ruta del secreto en Vault; NUNCA el .p12 ni el PIN en esta tabla
    status                 VARCHAR(35) NOT NULL DEFAULT 'REGISTRADA',
    creado_por             UUID NOT NULL REFERENCES usuario(id),
    create_date            TIMESTAMP NOT NULL DEFAULT now(),
    update_date            TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE empresa_status_historial (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    empresa_id      UUID NOT NULL REFERENCES empresa(id),
    status_anterior VARCHAR(35),
    status_nuevo    VARCHAR(35) NOT NULL,
    tipo_cambio     VARCHAR(15) NOT NULL CHECK (tipo_cambio IN ('AUTOMATICO', 'ADMINISTRATIVO')),
    motivo          VARCHAR(255),
    ejecutado_por   UUID REFERENCES usuario(id),  -- NULL si es AUTOMATICO
    fecha           TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE empresa_ambiente_historial (
    id                UUID PRIMARY KEY DEFAULT uuidv7(),
    empresa_id        UUID NOT NULL REFERENCES empresa(id),
    ambiente_anterior VARCHAR(10) NOT NULL,
    ambiente_nuevo    VARCHAR(10) NOT NULL,
    activado_por      UUID NOT NULL REFERENCES usuario(id),
    fecha             TIMESTAMP NOT NULL DEFAULT now()
);
```

**Cálculo automático del estado derivado** (`REGISTRADA` → `DATOS_FISCALES_INCOMPLETOS` → `CERTIFICADO_PENDIENTE` → `CREDENCIALES_HACIENDA_PENDIENTES` → `HABILITADA`). Los estados administrativos (`SUSPENDIDA`, `BAJA`) nunca se sobrescriben automáticamente:

```sql
CREATE OR REPLACE FUNCTION fn_actualizar_status_empresa()
RETURNS TRIGGER AS $$
DECLARE
    v_nuevo_status VARCHAR(35);
BEGIN
    IF NEW.status IN ('SUSPENDIDA', 'BAJA') THEN
        RETURN NEW;
    END IF;

    IF NEW.numero_identificacion IS NULL OR NEW.codigo_actividad IS NULL
       OR NEW.codigo_provincia IS NULL OR NEW.canton IS NULL OR NEW.distrito IS NULL
       OR NEW.otras_senas IS NULL OR length(trim(NEW.otras_senas)) < 5 THEN
        v_nuevo_status := 'DATOS_FISCALES_INCOMPLETOS';
    ELSIF NEW.certificado_referencia IS NULL THEN
        v_nuevo_status := 'CERTIFICADO_PENDIENTE';
    ELSIF NOT EXISTS (
        SELECT 1 FROM credencial_hacienda
        WHERE empresa_id = NEW.id AND ambiente = 'SANDBOX'
    ) THEN
        v_nuevo_status := 'CREDENCIALES_HACIENDA_PENDIENTES';
    ELSE
        v_nuevo_status := 'HABILITADA';
    END IF;

    IF v_nuevo_status IS DISTINCT FROM OLD.status THEN
        INSERT INTO empresa_status_historial (empresa_id, status_anterior, status_nuevo, tipo_cambio)
        VALUES (NEW.id, OLD.status, v_nuevo_status, 'AUTOMATICO');
    END IF;

    NEW.status      := v_nuevo_status;
    NEW.update_date := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_actualizar_status_empresa
    BEFORE UPDATE ON empresa
    FOR EACH ROW EXECUTE FUNCTION fn_actualizar_status_empresa();
```

**Validación dura de la transición sandbox → producción** (la transición inversa queda sin bloqueo técnico, por decisión explícita, ver sección 2):

```sql
CREATE OR REPLACE FUNCTION fn_validar_transicion_ambiente()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.ambiente_hacienda = 'PRODUCCION' AND OLD.ambiente_hacienda = 'SANDBOX' THEN
        IF NEW.status <> 'HABILITADA' THEN
            RAISE EXCEPTION 'No se puede activar producción: la empresa no está en estado HABILITADA';
        END IF;

        IF NOT EXISTS (
            SELECT 1 FROM credencial_hacienda
            WHERE empresa_id = NEW.id AND ambiente = 'PRODUCCION'
        ) THEN
            RAISE EXCEPTION 'No se puede activar producción: faltan credenciales de Hacienda para ese ambiente';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_validar_transicion_ambiente
    BEFORE UPDATE ON empresa
    FOR EACH ROW EXECUTE FUNCTION fn_validar_transicion_ambiente();
```

### 4.2 Credenciales de Hacienda por ambiente

```sql
CREATE TABLE credencial_hacienda (
    id                    UUID PRIMARY KEY DEFAULT uuidv7(),
    empresa_id            UUID NOT NULL REFERENCES empresa(id),
    ambiente              VARCHAR(10) NOT NULL CHECK (ambiente IN ('SANDBOX', 'PRODUCCION')),
    usuario_hacienda      VARCHAR(255) NOT NULL,
    credencial_referencia VARCHAR(255) NOT NULL,  -- ruta del secreto en Vault, nunca la contraseña
    configurada_en        TIMESTAMP NOT NULL DEFAULT now(),
    configurada_por       UUID NOT NULL REFERENCES usuario(id),
    UNIQUE (empresa_id, ambiente)
);
```

### 4.3 Relación usuario–empresa

```sql
CREATE TABLE usuario_empresa (
    id            UUID PRIMARY KEY DEFAULT uuidv7(),
    usuario_id    UUID NOT NULL REFERENCES usuario(id),
    empresa_id    UUID NOT NULL REFERENCES empresa(id),
    rol_id        UUID NOT NULL REFERENCES rol(id),
    estado        VARCHAR(20) NOT NULL DEFAULT 'ACTIVO'
                     CHECK (estado IN ('ACTIVO', 'SUSPENDIDO', 'INVITACION_PENDIENTE')),
    invitado_por  UUID REFERENCES usuario(id),
    fecha_ingreso TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (usuario_id, empresa_id)
);
```

### 4.4 Catálogo de roles (global, no personalizable por tenant)

```sql
CREATE TABLE rol (
    id          UUID PRIMARY KEY DEFAULT uuidv7(),
    codigo      VARCHAR(30) UNIQUE NOT NULL,
    nombre      VARCHAR(100) NOT NULL,
    descripcion VARCHAR(255)
);

INSERT INTO rol (codigo, nombre, descripcion) VALUES
('SUPER_ADMIN',            'Super Administrador',       'Plataforma, cruza tenants. No vive en usuario_empresa.'),
('ADMIN_EMPRESA',          'Administrador de Empresa',  'Control total del tenant. Asignado automáticamente a quien registra la empresa.'),
('EMPLEADO_FACTURACION',   'Empleado de Facturación',   'Crear/enviar facturas, gestionar productos y clientes.'),
('CONSULTA',               'Consulta',                  'Solo lectura sobre facturas y reportes. Cubre el caso de contador externo.');
```

### 4.5 Permisos atómicos

```sql
CREATE TABLE permiso (
    id          UUID PRIMARY KEY DEFAULT uuidv7(),
    codigo      VARCHAR(50)  UNIQUE NOT NULL,
    modulo      VARCHAR(30)  NOT NULL,
    descripcion VARCHAR(255) NOT NULL,
    critico     BOOLEAN      NOT NULL DEFAULT false,
    activo      BOOLEAN      NOT NULL DEFAULT true,
    create_date TIMESTAMP    NOT NULL DEFAULT now()
);

INSERT INTO permiso (codigo, modulo, descripcion, critico) VALUES
('empresa.ver_configuracion',        'Empresa',      'Ver datos fiscales, ubicación, actividad económica', false),
('empresa.editar_configuracion',     'Empresa',      'Modificar datos fiscales, ubicación, actividad económica', false),
('empresa.gestionar_certificado',    'Empresa',      'Subir/reemplazar certificado .p12 y PIN (nunca lectura en texto plano)', true),
('empresa.gestionar_ambiente_hacienda','Empresa',    'Autoriza el cambio de ambiente sandbox/producción en ambos sentidos', true),
('usuario.invitar',                  'Usuarios',     'Crear invitación de nuevo miembro a la empresa', false),
('usuario.ver',                      'Usuarios',     'Listar miembros y sus roles dentro de la empresa', false),
('usuario.editar_rol',               'Usuarios',     'Cambiar el rol asignado a una membresía', true),
('usuario.suspender',                'Usuarios',     'Revocar acceso de un miembro sin eliminarlo', true),
('permiso.personalizar',             'Usuarios',     'Otorgar/revocar permisos individuales sobre una membresía específica', true),
('producto.crear',                   'Catálogo',     'Registrar producto/servicio, incluye búsqueda CABYS', false),
('producto.editar',                  'Catálogo',     'Modificar producto existente', false),
('producto.ver',                     'Catálogo',     'Consultar catálogo', false),
('producto.desactivar',              'Catálogo',     'Retirar producto de circulación sin eliminarlo del historial', false),
('cliente.crear',                    'Clientes',     'Registrar cliente/receptor', false),
('cliente.editar',                   'Clientes',     'Modificar datos de cliente existente', false),
('cliente.ver',                      'Clientes',     'Consultar cartera de clientes', false),
('factura.crear',                    'Facturación',  'Generar y enviar factura a Hacienda', false),
('factura.ver',                      'Facturación',  'Consultar facturas emitidas y su estado', false),
('factura.anular',                   'Facturación',  'Emitir nota de crédito de anulación', true),
('factura.reenviar',                 'Facturación',  'Reintentar envío ante rechazo o error de Hacienda', false),
('reporte.ver',                      'Reportes',     'Consultar reportes de ventas/contables', false);
```

### 4.6 Mapeo rol → permiso (versionado)

```sql
CREATE TABLE rol_permiso (
    id            UUID PRIMARY KEY DEFAULT uuidv7(),
    rol_id        UUID      NOT NULL REFERENCES rol(id),
    permiso_id    UUID      NOT NULL REFERENCES permiso(id),
    vigente_desde TIMESTAMP NOT NULL DEFAULT now(),
    vigente_hasta TIMESTAMP,
    asignado_por  UUID      REFERENCES usuario(id)
);

-- Garantiza a nivel de motor que nunca exista más de una fila vigente
-- para el mismo par rol-permiso.
CREATE UNIQUE INDEX ux_rol_permiso_vigente
    ON rol_permiso (rol_id, permiso_id)
    WHERE vigente_hasta IS NULL;
```

### 4.7 Excepciones puntuales por membresía (usuario_permiso)

```sql
CREATE TABLE usuario_permiso (
    id                  UUID PRIMARY KEY DEFAULT uuidv7(),
    usuario_empresa_id  UUID NOT NULL REFERENCES usuario_empresa(id),
    permiso_id          UUID NOT NULL REFERENCES permiso(id),
    tipo                VARCHAR(10) NOT NULL CHECK (tipo IN ('CONCEDIDO', 'REVOCADO')),
    otorgado_por        UUID NOT NULL REFERENCES usuario(id),
    fecha_otorgado      TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (usuario_empresa_id, permiso_id)
);

-- Bloqueo de autoescalamiento de privilegios, a nivel de motor.
CREATE OR REPLACE FUNCTION fn_bloquear_autoescalamiento()
RETURNS TRIGGER AS $$
DECLARE
    v_usuario_objetivo UUID;
    v_es_critico BOOLEAN;
BEGIN
    SELECT usuario_id INTO v_usuario_objetivo
    FROM usuario_empresa WHERE id = NEW.usuario_empresa_id;

    SELECT critico INTO v_es_critico
    FROM permiso WHERE id = NEW.permiso_id;

    IF NEW.otorgado_por = v_usuario_objetivo THEN
        RAISE EXCEPTION 'Un usuario no puede modificar sus propios permisos';
    END IF;

    IF v_es_critico AND NEW.tipo = 'CONCEDIDO' THEN
        RAISE EXCEPTION 'Los permisos críticos no se otorgan por excepción puntual, solo por rol';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_bloquear_autoescalamiento
    BEFORE INSERT ON usuario_permiso
    FOR EACH ROW EXECUTE FUNCTION fn_bloquear_autoescalamiento();
```

### 4.8 Vista de permisos efectivos (para congelar en el JWT al emitir el token)

```sql
CREATE VIEW permisos_efectivos AS
SELECT
    ue.id AS usuario_empresa_id,
    p.codigo AS permiso_codigo
FROM usuario_empresa ue
JOIN rol_permiso rp ON rp.rol_id = ue.rol_id AND rp.vigente_hasta IS NULL
JOIN permiso p ON p.id = rp.permiso_id AND p.activo = true
WHERE NOT EXISTS (
    SELECT 1 FROM usuario_permiso up
    WHERE up.usuario_empresa_id = ue.id
      AND up.permiso_id = rp.permiso_id
      AND up.tipo = 'REVOCADO'
)
UNION
SELECT
    up.usuario_empresa_id,
    p.codigo
FROM usuario_permiso up
JOIN permiso p ON p.id = up.permiso_id AND p.activo = true
WHERE up.tipo = 'CONCEDIDO';
```

### 4.9 Consecutivos de comprobantes (corregido: separado por ambiente)

```sql
CREATE TABLE contador_consecutivo (
    empresa_id       UUID NOT NULL REFERENCES empresa(id),
    ambiente         VARCHAR(10) NOT NULL CHECK (ambiente IN ('SANDBOX', 'PRODUCCION')),
    tipo_comprobante VARCHAR(2) NOT NULL,
    valor_actual     BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (empresa_id, ambiente, tipo_comprobante)
);
```

Uso dentro de la transacción de generación de factura:

```sql
-- 1. Bloquea la fila; un segundo proceso concurrente espera, no obtiene otro número.
SELECT valor_actual FROM contador_consecutivo
WHERE empresa_id = :empresa_id AND ambiente = :ambiente AND tipo_comprobante = :tipo
FOR UPDATE;

-- 2. Se usa el valor para construir la clave y el XML.
-- 3. Se incrementa y se guarda, en la MISMA transacción que la factura.
UPDATE contador_consecutivo SET valor_actual = valor_actual + 1
WHERE empresa_id = :empresa_id AND ambiente = :ambiente AND tipo_comprobante = :tipo;

-- Si algo falla después, el ROLLBACK revierte también el incremento — sin huecos.
```

### 4.10 `producto`

```sql
CREATE TABLE producto (
    id                  UUID PRIMARY KEY DEFAULT uuidv7(),
    empresa_id          UUID NOT NULL REFERENCES empresa(id),  -- @TenantId
    codigo              VARCHAR(50) NOT NULL,
    descripcion         VARCHAR(255) NOT NULL,
    codigo_cabys        VARCHAR(13) NOT NULL,          -- SIN DEFAULT: elimina el fallback genérico del proyecto original
    descripcion_cabys   VARCHAR(255),                   -- cacheado desde la respuesta de la API al momento de validar
    cabys_validado_en   TIMESTAMP NOT NULL,              -- prueba de que se validó contra api.hacienda.go.cr, no tecleado a mano
    codigo_unidad_fe    VARCHAR(20) NOT NULL DEFAULT 'Unid',
    precio_venta        NUMERIC(14,5) NOT NULL CHECK (precio_venta >= 0),
    gravado             BOOLEAN NOT NULL DEFAULT true,
    porcentaje_impuesto NUMERIC(5,2) NOT NULL DEFAULT 13.00,
    activo              BOOLEAN NOT NULL DEFAULT true,
    create_date         TIMESTAMP NOT NULL DEFAULT now(),
    update_date         TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (empresa_id, codigo)
);
```

Simplificado frente al proyecto original: se elimina la distinción `precioInstitucional`/`precioMayorista` (propia de distribución al por mayor, sin valor para el mercado objetivo de persona individual/pequeño negocio) en favor de un único `precio_venta`. `codigo_cabys` sin `DEFAULT` hace imposible, a nivel de motor, insertar un producto sin CABYS explícito — corrige de raíz el fallback genérico (`8522000000000`) documentado en la bitácora de riesgos.

**`porcentaje_impuesto` se deriva del campo `impuesto` que devuelve la propia API de Hacienda al validar el CABYS** — nunca tecleado a mano, mismo principio que ya rige `codigo_cabys`/`descripcion_cabys`. El servicio de alta de producto debe rechazar la creación si ese campo llega vacío o nulo en la respuesta de Hacienda, en lugar de asumir un porcentaje por defecto silencioso. Este valor es la tarifa *por defecto* del producto — no necesariamente la tarifa final de cada venta: una exoneración autorizada aplicada a una línea de factura específica (sección 4.15) la sobrescribe puntualmente, sin alterar este campo del catálogo.

### 4.11 `cliente`

```sql
CREATE TABLE cliente (
    id                        UUID PRIMARY KEY DEFAULT uuidv7(),
    empresa_id                UUID NOT NULL REFERENCES empresa(id),  -- @TenantId
    nombre                    VARCHAR(255) NOT NULL,
    tipo_identificacion       VARCHAR(2) NOT NULL,   -- '01' física, '02' jurídica, '03' DIMEX, '04' NITE
    numero_identificacion     VARCHAR(20) NOT NULL,
    codigo_actividad          VARCHAR(6),             -- opcional; solo relevante para créditos/gastos
    codigo_provincia          VARCHAR(1),
    canton                    VARCHAR(2),
    distrito                  VARCHAR(2),
    otras_senas               VARCHAR(300),
    telefono                  VARCHAR(20),
    email                     VARCHAR(255),
    requiere_factura_electronica BOOLEAN NOT NULL DEFAULT true,
    create_date               TIMESTAMP NOT NULL DEFAULT now(),
    update_date               TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (empresa_id, numero_identificacion),
    CHECK (
        (codigo_provincia IS NULL AND canton IS NULL AND distrito IS NULL AND otras_senas IS NULL)
        OR
        (codigo_provincia IS NOT NULL AND canton IS NOT NULL AND distrito IS NOT NULL
         AND otras_senas IS NOT NULL AND length(trim(otras_senas)) >= 5)
    )
);
```

El `CHECK` de ubicación convierte en regla de motor una lógica que en el proyecto original solo vivía en Java (omitir el bloque `Ubicacion` completo si algún campo faltaba): o los cuatro campos están completos y válidos, o ninguno está presente — un estado parcial ya no puede existir ni por un `UPDATE` directo. `tipo_identificacion` es explícito y obligatorio; se elimina la heurística de inferencia por longitud de dígitos del proyecto original, que no distinguía correctamente DIMEX de NITE.

### 4.12 `factura` — la orden comercial

```sql
CREATE TABLE factura (
    id               UUID PRIMARY KEY DEFAULT uuidv7(),
    empresa_id       UUID NOT NULL REFERENCES empresa(id),  -- @TenantId
    cliente_id       UUID NOT NULL REFERENCES cliente(id),
    condicion_venta  VARCHAR(2) NOT NULL DEFAULT '01',
    plazo_credito    INT,
    medio_pago       VARCHAR(2) NOT NULL DEFAULT '01',
    moneda           VARCHAR(3) NOT NULL DEFAULT 'CRC',
    tipo_cambio      NUMERIC(10,5) NOT NULL DEFAULT 1.00000,
    subtotal         NUMERIC(14,5) NOT NULL,
    total_impuesto   NUMERIC(14,5) NOT NULL,
    total            NUMERIC(14,5) NOT NULL,
    creado_por       UUID NOT NULL REFERENCES usuario(id),
    create_date      TIMESTAMP NOT NULL DEFAULT now(),
    update_date      TIMESTAMP NOT NULL DEFAULT now(),
    CHECK (condicion_venta <> '02' OR plazo_credito IS NOT NULL)
);
```

Deliberadamente separada de `comprobante_electronico` (4.13): la orden comercial cambia por reglas de negocio, el comprobante fiscal cambia por reglas de protocolo ante Hacienda. Fusionarlas acopla dos ciclos de vida independientes. El `CHECK` final cierra un vacío del generador XML original: nada impedía una factura marcada como crédito sin plazo definido.

### 4.13 `comprobante_electronico` — el envoltorio fiscal

```sql
CREATE TABLE comprobante_electronico (
    id                UUID PRIMARY KEY DEFAULT uuidv7(),
    empresa_id        UUID NOT NULL REFERENCES empresa(id),  -- @TenantId
    factura_id        UUID NOT NULL UNIQUE REFERENCES factura(id),
    ambiente_hacienda VARCHAR(10) NOT NULL,   -- SNAPSHOT: no se lee de empresa.ambiente_hacienda en el momento de la consulta
    tipo_comprobante  VARCHAR(2) NOT NULL DEFAULT '01',
    consecutivo       VARCHAR(20) NOT NULL,
    clave_numerica    VARCHAR(50) NOT NULL UNIQUE,
    estado            VARCHAR(20) NOT NULL DEFAULT 'GENERADO',
    xml_comprobante_referencia VARCHAR(255),  -- ruta en Oracle Object Storage; el XML viaja cifrado, nunca en esta columna
    xml_respuesta_referencia  VARCHAR(255),
    pdf_referencia            VARCHAR(255),
    codigo_respuesta  VARCHAR(10),
    mensaje_respuesta VARCHAR(500),
    intentos_envio    INT NOT NULL DEFAULT 0,
    fecha_emision     TIMESTAMP NOT NULL DEFAULT now(),
    fecha_respuesta   TIMESTAMP,
    UNIQUE (empresa_id, ambiente_hacienda, tipo_comprobante, consecutivo)
);
```

`ambiente_hacienda` como snapshot, no como lectura en vivo de `empresa.ambiente_hacienda`, es obligatorio dado que ya permitimos que una empresa retroceda de producción a sandbox sin intervención de `SUPER_ADMIN` (sección 2): sin este snapshot, alternar el ambiente reescribiría retroactivamente el ambiente de todo el historial de facturación ya aceptado por Hacienda. `xml_comprobante_referencia`, `xml_respuesta_referencia` y `pdf_referencia` apuntan a Oracle Object Storage, no a contenido embebido — resolución de la estrategia de almacenamiento documentada en la sección 6.4.

### 4.14 `linea_factura`

```sql
CREATE TABLE linea_factura (
    id                    UUID PRIMARY KEY DEFAULT uuidv7(),
    empresa_id            UUID NOT NULL REFERENCES empresa(id),  -- @TenantId
    factura_id            UUID NOT NULL REFERENCES factura(id),
    producto_id           UUID NOT NULL REFERENCES producto(id),
    numero_linea          INT NOT NULL,
    cantidad              NUMERIC(10,3) NOT NULL CHECK (cantidad > 0),
    precio_unitario       NUMERIC(14,5) NOT NULL CHECK (precio_unitario >= 0),
    subtotal              NUMERIC(14,5) NOT NULL,
    codigo_cabys_aplicado  VARCHAR(13) NOT NULL,   -- snapshot del producto al momento de la venta
    gravado_aplicado       BOOLEAN NOT NULL,        -- snapshot
    porcentaje_impuesto_aplicado NUMERIC(5,2) NOT NULL,  -- snapshot
    exoneracion_id                    UUID REFERENCES cliente_exoneracion(id),  -- sección 4.15
    porcentaje_exoneracion_aplicado   NUMERIC(5,2),  -- snapshot de la autorización al momento de la venta
    monto_exoneracion_aplicado        NUMERIC(14,5),
    UNIQUE (factura_id, numero_linea),
    CHECK (
        (exoneracion_id IS NULL AND porcentaje_exoneracion_aplicado IS NULL AND monto_exoneracion_aplicado IS NULL)
        OR
        (exoneracion_id IS NOT NULL AND porcentaje_exoneracion_aplicado IS NOT NULL AND monto_exoneracion_aplicado IS NOT NULL)
    )
);
```

Los campos `_aplicado` son copias congeladas del producto al momento de la venta, no referencias recalculadas contra `producto` en vivo — una corrección posterior al catálogo de productos no puede alterar retroactivamente el impuesto ya declarado ante Hacienda en una factura pasada. `empresa_id` presente directamente en esta tabla, aunque derivable por join a través de `factura_id`, es un requisito técnico del mecanismo de la sección 5: `@TenantId` filtra por columna propia de la entidad, no a través de relaciones.

Los tres campos de exoneración siguen el mismo principio de "todo o nada" que ya aplicamos al bloque de ubicación de `cliente`: o los tres están presentes, o ninguno lo está — un estado parcial (una exoneración referenciada sin su porcentaje aplicado) no puede existir a nivel de motor. `porcentaje_exoneracion_aplicado` y `monto_exoneracion_aplicado` son snapshots, no referencias en vivo a `cliente_exoneracion`, por la misma razón de integridad histórica que ya rige el resto de los campos `_aplicado`: si la autorización se revoca o cambia después, las facturas ya emitidas bajo ella no se reescriben retroactivamente.

### 4.15 `cliente_exoneracion` — autorizaciones de exoneración por cliente

La exoneración no es una propiedad del producto ni de la factura — es una autorización que Hacienda le otorga a un cliente específico, con vigencia y respaldo documental propios, potencialmente reutilizable en múltiples facturas durante su periodo de validez.

```sql
CREATE TABLE cliente_exoneracion (
    id                       UUID PRIMARY KEY DEFAULT uuidv7(),
    empresa_id               UUID NOT NULL REFERENCES empresa(id),  -- @TenantId
    cliente_id               UUID NOT NULL REFERENCES cliente(id),
    tipo_documento           VARCHAR(2) NOT NULL,   -- catálogo oficial de Hacienda, sección 4.15.1
    numero_documento         VARCHAR(40) NOT NULL,
    nombre_institucion       VARCHAR(160) NOT NULL,
    numero_articulo          VARCHAR(10) NOT NULL,
    inciso                   VARCHAR(10),
    nombre_institucion_otros VARCHAR(160),           -- obligatorio únicamente si tipo_documento = '99'
    fecha_emision            TIMESTAMP NOT NULL,
    fecha_vencimiento        TIMESTAMP,              -- NULL si la autorización no vence
    porcentaje_exoneracion   NUMERIC(5,2) NOT NULL CHECK (porcentaje_exoneracion > 0 AND porcentaje_exoneracion <= 100),
    activo                   BOOLEAN NOT NULL DEFAULT true,
    create_date              TIMESTAMP NOT NULL DEFAULT now(),
    update_date              TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (cliente_id, numero_documento),
    CHECK (tipo_documento IN ('01','02','03','04','05','06','07','08','09','10','11','99')),
    CHECK (tipo_documento <> '99' OR nombre_institucion_otros IS NOT NULL)
);
```

#### 4.15.1 Catálogo oficial (Nota 10.1, Anexos y Estructuras v4.4)

| Código | Tipo de documento | Aplicable a Factura Electrónica |
|---|---|---|
| 01 | Compras autorizadas por la Dirección General de Tributación | **No** — exclusivo de Nota de Crédito/Débito |
| 02 | Ventas exentas a diplomáticos | Sí |
| 03 | Autorizado por Ley especial | Sí |
| 04 | Exenciones DGH — Autorización Local Genérica | Sí |
| 05 | Exenciones DGH — Transitorio V (ingeniería, arquitectura, topografía, obra civil) | **No** — exclusivo de Nota de Crédito/Débito |
| 06 | Servicios turísticos inscritos ante el ICT | **No** — exclusivo de Nota de Crédito/Débito |
| 07 | Transitorio XVII (reciclaje/reutilizable) | **No** — exclusivo de Nota de Crédito/Débito |
| 08 | Exoneración a Zona Franca | Sí |
| 09 | Servicios complementarios para exportación (art. 11 RLIVA) | Sí |
| 10 | Órgano de las corporaciones municipales | Sí |
| 11 | Exenciones DGH — Autorización de Impuesto Local Concreta | Sí |
| 99 | Otros (exige `nombre_institucion_otros`) | Sí |

Los códigos 01, 05, 06 y 07 están marcados como no aplicables a Factura Electrónica **por regla explícita de Hacienda** (notas 38-41 del propio documento oficial), no por relevancia estimada para el mercado objetivo — la restricción se aplica en el trigger de la sección 4.15.2, no en el `CHECK` de captura, porque el documento de exoneración en sí puede ser de cualquiera de los doce tipos; lo que Hacienda restringe es su aplicación a un tipo de comprobante específico.

**Nota de producto, no de motor:** dado el mercado objetivo de persona individual/pequeño negocio, los códigos 08 (Zona Franca), 10 (municipalidades) y 99 (Otros) son los de mayor probabilidad de uso real — la interfaz de captura puede priorizarlos visualmente sin que eso implique restringirlos a nivel de base de datos.

#### 4.15.2 Validaciones a nivel de motor

```sql
CREATE OR REPLACE FUNCTION fn_validar_exoneracion_vigente()
RETURNS TRIGGER AS $$
DECLARE
    v_vencimiento TIMESTAMP;
    v_activo BOOLEAN;
    v_tipo_documento VARCHAR(2);
BEGIN
    IF NEW.exoneracion_id IS NOT NULL THEN
        SELECT fecha_vencimiento, activo, tipo_documento
        INTO v_vencimiento, v_activo, v_tipo_documento
        FROM cliente_exoneracion WHERE id = NEW.exoneracion_id;

        IF NOT v_activo THEN
            RAISE EXCEPTION 'La exoneración referenciada está inactiva';
        END IF;

        IF v_vencimiento IS NOT NULL AND v_vencimiento < now() THEN
            RAISE EXCEPTION 'La exoneración referenciada venció el %', v_vencimiento;
        END IF;

        IF v_tipo_documento IN ('01', '05', '06', '07') THEN
            RAISE EXCEPTION 'El tipo de exoneración % es exclusivo de Nota de Crédito/Débito, no aplica a Factura Electrónica', v_tipo_documento;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_validar_exoneracion_vigente
    BEFORE INSERT OR UPDATE ON linea_factura
    FOR EACH ROW EXECUTE FUNCTION fn_validar_exoneracion_vigente();
```

### 4.16 Integridad cruzada entre tenants

Ninguna llave foránea, por sí sola, impide que una `factura` de la Empresa A referencie un `cliente_id` que en realidad pertenece a la Empresa B — la FK solo garantiza que el registro existe, no que existe dentro del mismo tenant. Se cierra a nivel de motor, no solo de aplicación:

```sql
CREATE OR REPLACE FUNCTION fn_validar_mismo_tenant()
RETURNS TRIGGER AS $$
DECLARE
    v_empresa_referencia UUID;
    v_cliente_exoneracion UUID;
    v_cliente_factura UUID;
BEGIN
    IF TG_TABLE_NAME = 'factura' THEN
        SELECT empresa_id INTO v_empresa_referencia FROM cliente WHERE id = NEW.cliente_id;
    ELSIF TG_TABLE_NAME = 'linea_factura' THEN
        SELECT empresa_id INTO v_empresa_referencia FROM producto WHERE id = NEW.producto_id;

        IF NEW.exoneracion_id IS NOT NULL THEN
            SELECT ce.cliente_id, f.cliente_id INTO v_cliente_exoneracion, v_cliente_factura
            FROM cliente_exoneracion ce, factura f
            WHERE ce.id = NEW.exoneracion_id AND f.id = NEW.factura_id;

            IF v_cliente_exoneracion IS DISTINCT FROM v_cliente_factura THEN
                RAISE EXCEPTION 'La exoneración referenciada no pertenece al cliente de esta factura';
            END IF;
        END IF;
    END IF;

    IF v_empresa_referencia IS DISTINCT FROM NEW.empresa_id THEN
        RAISE EXCEPTION 'Referencia cruzada entre tenants no permitida (empresa % intentando usar recurso de empresa %)',
            NEW.empresa_id, v_empresa_referencia;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_validar_tenant_factura
    BEFORE INSERT OR UPDATE ON factura
    FOR EACH ROW EXECUTE FUNCTION fn_validar_mismo_tenant();

CREATE TRIGGER trg_validar_tenant_linea_factura
    BEFORE INSERT OR UPDATE ON linea_factura
    FOR EACH ROW EXECUTE FUNCTION fn_validar_mismo_tenant();
```

## 5. Mecanismo de resolución de tenant (Spring Boot + Hibernate)

Convierte el discriminador `empresa_id` de una convención que un desarrollador debe recordar en una regla que el motor de persistencia aplica automáticamente. Tres componentes obligatorios, en este orden de dependencia:

### 5.1 Marcador de tenant vía superclase

```java
@MappedSuperclass
public abstract class TenantAwareEntity {
    @TenantId
    @Column(name = "empresa_id", nullable = false, updatable = false)
    private UUID empresaId;
}
```

Toda entidad de negocio (`Cliente`, `Producto`, `Factura`, `LineaFactura`) extiende esta clase en lugar de declarar `empresaId` por su cuenta — evita el olvido de anotación tabla por tabla.

### 5.2 Contexto de tenant — `ThreadLocal` propio, independiente de Spring Security

```java
public final class TenantContext {
    private static final ThreadLocal<UUID> EMPRESA_ID = new ThreadLocal<>();
    private TenantContext() {}
    public static void set(UUID empresaId) { EMPRESA_ID.set(empresaId); }
    public static UUID get() { return EMPRESA_ID.get(); }
    public static void clear() { EMPRESA_ID.remove(); }
}
```

Deliberadamente desacoplado de `SecurityContextHolder`: acoplarlo funciona en el flujo HTTP normal, pero se rompe en procesamiento asíncrono y jobs programados (ver riesgos 5.5).

### 5.3 Resolutor que conecta el contexto con Hibernate

```java
@Component
public class EmpresaTenantIdentifierResolver
        implements CurrentTenantIdentifierResolver<String>, HibernatePropertiesCustomizer {

    @Override
    public String resolveCurrentTenantIdentifier() {
        UUID empresaId = TenantContext.get();
        if (empresaId == null) {
            // Fail-closed, no fail-open: sin contexto de tenant, la consulta se bloquea.
            throw new TenantNoResueltoException(
                "Operación bloqueada: no hay empresa_id en contexto de ejecución");
        }
        return empresaId.toString();
    }

    @Override
    public boolean validateExistingCurrentSessions() { return true; }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
    }
}
```

El `throw` en lugar de un valor por defecto es la decisión de diseño más importante del mecanismo: preferible que el sistema falle de forma ruidosa a que filtre de forma incorrecta.

### 5.4 Filtro que puebla el contexto por request

```java
@Component
public class JwtTenantFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        try {
            String token = extraerToken(request);
            if (token != null && jwtService.esValido(token)) {
                UUID empresaId = jwtService.extraerEmpresaId(token);
                TenantContext.set(empresaId);
            }
            chain.doFilter(request, response);
        } finally {
            // OBLIGATORIO: el contenedor reutiliza hilos entre requests.
            // Sin este finally, el hilo que atendió a la Empresa A puede
            // atender a la Empresa B en el siguiente request.
            TenantContext.clear();
        }
    }
}
```

### 5.5 Riesgos que este mecanismo NO resuelve por sí solo

- **Consultas nativas y procedimientos almacenados quedan fuera del alcance de `@TenantId`.** El filtro automático opera sobre JPQL/Criteria, no sobre SQL nativo. El proyecto original usa un procedimiento almacenado (`SP_COMPROBANTES_ELECTRONICOS.sql`) y métodos que devuelven `Object[]` crudos (`findAllByEmpresaIdAsDTO`) — si se portan tal cual, quedan desprotegidos por diseño y exigen filtrado manual de `empresa_id` en cada uno. **Recomendación: evitar procedimientos almacenados y SQL nativo para acceso a datos de negocio en el proyecto nuevo, salvo necesidad de rendimiento medida, no anticipada.**
- **Procesamiento asíncrono y jobs programados no heredan el `ThreadLocal` del request original.** El proyecto original tiene `procesarFacturaAsync()` (`@Async`) y `ComprobanteReintentosJob` (programado) — ninguno propagaría `TenantContext` sin corrección explícita.
  - `@Async` de un solo tenant conocido: capturar `empresa_id` en el hilo original antes de despachar, restablecerlo en la primera línea del método asíncrono, limpiar en `finally`.
  - Jobs que iteran sobre *todas* las empresas (como `ComprobanteReintentosJob`): establecer y limpiar `TenantContext` en **cada iteración** del bucle, nunca de forma global para todo el job — de lo contrario, el job procesaría solo la última empresa que dejó el `ThreadLocal` puesto.
- **`SUPER_ADMIN` requiere un escape controlado, no un bypass accidental.** El acceso entre tenants de la plataforma no debe pasar por `@TenantId` — debe ser un conjunto de repositorios/consultas explícitamente marcadas como de alcance de plataforma (`session.disableFilter()` o SQL nativo deliberado), restringido al flujo de autenticación elevado de `SUPER_ADMIN`.

## 6. Convención de secretos en Vault

Resuelve dónde vive físicamente cada referencia (`certificado_referencia`, `credencial_referencia`) que ya aparece en el esquema SQL como puntero, nunca como el secreto mismo.

### 6.1 Motor Transit — una sola llave maestra, no una por tenant

```
transit/keys/empresa-datos-kek
```

Consistente con la decisión ya cerrada de que el costo de cifrado no escala con el número de empresas: se usa la función de generación de llave de datos de Transit (`transit/datakey/plaintext/empresa-datos-kek`) para emitir una DEK por fila/tenant en el momento de cifrar. El backend descarta la DEK en texto plano inmediatamente después de usarla y almacena solo su versión cifrada como blob en la base de datos.

### 6.2 Motor KV v2 — namespacing explícito por empresa

```
secret/data/empresas/{empresa_id}/certificado/pin
secret/data/empresas/{empresa_id}/hacienda/sandbox/password
secret/data/empresas/{empresa_id}/hacienda/produccion/password
```

Estas rutas son literalmente el valor que se guarda en `empresa.certificado_referencia` y `credencial_hacienda.credencial_referencia`.

### 6.3 Control de acceso — centralizado en el backend, no una política de Vault por tenant

Una política de Vault distinta por cada empresa fue considerada y descartada: administrar cientos de políticas/tokens, uno por tenant, es complejidad operativa real para un operador único sin beneficio de seguridad proporcional a esta escala. El aislamiento ya lo garantizan dos capas independientes — `empresa_id` como discriminador de fila más `TenantContext` en la capa de aplicación (sección 5), y el propio namespacing de rutas de esta sección, que da trazabilidad clara ante auditoría aunque el control de acceso esté centralizado.

Diseño: una sola identidad de aplicación autenticada contra Vault (AppRole), con política amplia sobre `secret/data/empresas/*` y `transit/*`. El propio código, anclado al `TenantContext` activo, es responsable de nunca construir una ruta fuera del `empresa_id` de la sesión — nunca se expone un token de Vault directamente a un usuario final ni a un `ADMIN_EMPRESA`.

### 6.4 Almacenamiento de documentos — `.p12` en PostgreSQL, XML/PDF en Object Storage

Resuelve el primer pendiente histórico de este documento: dónde vive físicamente cada archivo, no solo su puntero.

**Certificado `.p12`:** blob cifrado en PostgreSQL, mismo patrón de envelope encryption vía `datakey` ya usado para el secreto MFA (sección 3.3). Es un archivo pequeño, por empresa, que rara vez cambia — no justifica un componente de almacenamiento de objetos adicional.

**XML de comprobante, XML de respuesta y PDF de cada factura:** en **Oracle Object Storage** (capa Always Free, 20 GB, sin fecha de expiración mientras la cuenta permanezca en ese estado), no en PostgreSQL. A diferencia del `.p12`, este contenido crece sin límite mientras el negocio opere — mantenerlo en la base de datos transaccional degradaría con el tiempo el rendimiento OLTP y complicaría los respaldos. Cada documento se cifra con la misma KEK de Transit **antes** de subirse — Object Storage nunca recibe ni retiene contenido fiscal en texto plano, independientemente del cifrado por defecto que Oracle aplique por debajo.

**Cliente de acceso:** SDK nativo de OCI Java (`oci-java-sdk-objectstorage`), no el SDK de AWS contra el endpoint compatible con S3. Dos razones concretas, no preferencia: (1) versiones recientes del SDK de AWS activan por defecto una codificación (`aws-chunked`) que rompe las subidas contra el endpoint de Oracle sin configuración manual adicional; (2) el SDK nativo soporta autenticación por **Instance Principal** — la VM se autentica con su propia identidad, sin generar ni custodiar un par adicional de credenciales estáticas tipo Access/Secret Key.

**Aislamiento del acoplamiento:** el acceso al SDK nativo queda contenido detrás de una interfaz propia (`DocumentoStorageClient`, con `subir()`/`descargar()`), nunca invocado directamente desde la lógica de negocio — mismo patrón de aislamiento ya aplicado a `TenantContext` y al cliente de Vault. Si en el futuro cambia de proveedor de almacenamiento, el cambio queda contenido en una sola implementación, no disperso por el código.

## 7. Riesgos y correcciones registradas durante el diseño (bitácora)

- El `synchronized` sobre `siguienteConsecutivo()` del proyecto original protegía un objeto en memoria, no una fila de base de datos — no ofrecía protección real de concurrencia. Corregido con bloqueo pesimista de fila.
- `findFirstByActivaTrue()` del proyecto original no filtraba por empresa — en un esquema multi-tenant real, tomaría la configuración de cualquier tenant al azar. Corregido exigiendo `empresa_id` como discriminador obligatorio en cada consulta, resuelto vía `@TenantId` de Hibernate.
- El proyecto original permitía que `UsuarioPermiso` colgara del usuario global en lugar de la membresía específica — en un escenario de usuario en múltiples empresas, un permiso otorgado en una empresa se filtraría a otra. Corregido: `usuario_permiso.usuario_empresa_id`.
- CABYS genérico hardcodeado (`8522000000000`) en el generador XML original, sin bloqueo — riesgo de rechazo de Hacienda sin aviso al usuario. Debe corregirse en el rediseño exigiendo CABYS validado contra la API real antes de permitir facturar.
- `FacturaPdfServiceImpl.generarPdfFactura()` del proyecto original resuelve la empresa emisora vía `empresaService.getEmpresaPrincipal()` — mismo defecto mono-tenant que `findFirstByActivaTrue()`, aplicado a la generación de PDF. La plantilla/maquetación (`addHeader`, `addClienteBlock`, `addLineasTable`, `addFooter`) se porta sin cambios; la resolución de la entidad `Empresa` se rediseña para depender de `factura.empresa_id`, nunca de un "principal" implícito. El vínculo con `comprobante_electronico` (clave numérica, consecutivo) sí está correctamente resuelto en el original y se preserva tal cual.
- Catálogo oficial de tipo de documento de exoneración (Nota 10.1, Anexos y Estructuras v4.4) verificado directamente contra el PDF oficial de Hacienda, no contra fuentes secundarias que solo estimaban los códigos. Hallazgo relevante: los códigos 01, 05, 06 y 07 son de uso exclusivo para Nota de Crédito/Débito por regla explícita de Hacienda (notas 38-41 del documento oficial) — no una decisión de alcance de producto, sino una restricción de motor aplicada en `fn_validar_exoneracion_vigente` (sección 4.15.2).

## 8. Hoja de ruta — Release 1: Factura Electrónica de extremo a extremo

Estrategia de entrega por releases, comenzando por el tipo de comprobante `01` (Factura Electrónica) de punta a punta antes de expandir cobertura a los demás tipos. La base de aislamiento multi-tenant (secciones 4 y 5) no es un componente adicional que se agregue después — es constitutiva de Release 1, no una fase posterior.

### 8.1 Dentro de alcance de Release 1

- Registro de usuario → creación automática de empresa, asignación automática de `ADMIN_EMPRESA`.
- Máquina de estados `empresa.status` completa, incluyendo el trigger de cálculo automático (sección 4.1).
- Carga de certificado `.p12` + PIN hacia Vault, y credenciales OAuth de Hacienda — **únicamente ambiente `SANDBOX`**.
- Alta de producto con CABYS validado contra `api.hacienda.go.cr` (sin fallback genérico, sección 4.10).
- Alta de cliente con identificación válida por tipo (sección 4.11).
- Generación, firma y envío del comprobante tipo `01` (Factura Electrónica), con consulta de estado hasta `ACEPTADO` o `RECHAZADO`.
- Entrega al cliente final: PDF + XML de factura + XML de aceptación.
- Mecanismo completo de resolución de tenant (`@TenantId`, `TenantContext`, filtro JWT, sección 5) y bloqueo de autoescalamiento de permisos (sección 4.7) — parte constitutiva del flujo, no un extra de seguridad.
- Consecutivo con bloqueo pesimista de fila (sección 4.9), específico para `SANDBOX`.

### 8.2 Deliberadamente fuera de alcance de Release 1

| Diferido | Justificación de por qué puede esperar sin comprometer la base |
|---|---|
| Nota de Crédito, Nota de Débito, Tiquete Electrónico | Extensión del mismo patrón ya construido (`generarXml`/`firmar`/`enviarAHacienda` ya parametrizados por tipo) — trabajo incremental, no rediseño. |
| Invitación de usuarios adicionales y cambio de tenant en caliente | El esquema `usuario_empresa` (N:N) ya soporta esto estructuralmente desde el diseño; se aplaza la interfaz de invitación, no la capacidad del modelo de datos. |
| Activación de ambiente `PRODUCCION` | Release 1 opera exclusivamente en `SANDBOX`. Activar producción real debe esperar a que el flujo completo esté validado de punta a punta contra el ambiente de pruebas de Hacienda. |
| Reintentos asíncronos (`ComprobanteReintentosJob`) y notificaciones por correo | Resiliencia operativa, no la ruta feliz del primer flujo. |

### 8.3 Criterio de aceptación de Release 1

Una persona se registra, completa los datos de su empresa, sube su certificado, configura credenciales sandbox, da de alta un producto y un cliente, genera una factura, la ve pasar por `GENERADO → FIRMADO → ENVIADO → ACEPTADO`, y descarga el PDF junto con ambos XML — **sin que en ningún punto del proceso una segunda empresa registrada en la misma base de datos pueda ver, tocar, o interferir con ese flujo.**

Release 1 se considera terminado únicamente si ese escenario funciona de punta a punta con **dos empresas de prueba operando en paralelo**. Una factura individual saliendo bien no es, por sí sola, evidencia suficiente de que la base es sólida.

## 9. Contenerización (Spring Boot + PostgreSQL + Vault en la VM Ampere A1)

### 9.1 Versión de stack confirmada

**Java 21 LTS** (vía `eclipse-temurin`, no la distribución de Oracle — sin las restricciones de licenciamiento "No-Fee Terms" propias de Oracle JDK) + **Spring Boot 4.1.x**. Spring Boot 3.5 llegó a fin de soporte de código abierto el 30 de junio de 2026; construir un proyecto nuevo sobre esa rama no era viable. Se prioriza madurez de ecosistema (herramientas de monitoreo/APM) sobre el horizonte de soporte más largo de Java 25 LTS, dado que el sistema maneja obligaciones fiscales reales.

**PostgreSQL fijado a una versión de parche explícita** (`postgres:18.1`, no la etiqueta flotante `postgres:18`) — evita que una reconstrucción rutinaria del contenedor actualice de versión sin decisión deliberada.

### 9.2 `docker-compose.yml`

```yaml
services:
  reverse-proxy:
    image: caddy:2-alpine
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data
    networks:
      - red_publica
      - red_interna
    depends_on:
      - app

  app:
    build: ./app
    restart: unless-stopped
    environment:
      - SPRING_PROFILES_ACTIVE=produccion
      - DB_HOST=postgres
      - VAULT_ADDR=http://vault:8200
      - JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70.0
    networks:
      - red_interna
    depends_on:
      postgres:
        condition: service_healthy
      vault:
        condition: service_healthy
    deploy:
      resources:
        limits:
          cpus: "1.5"
          memory: 6G

  postgres:
    image: postgres:18.1
    restart: unless-stopped
    environment:
      - POSTGRES_DB=facturacion
      - POSTGRES_USER_FILE=/run/secrets/db_user
      - POSTGRES_PASSWORD_FILE=/run/secrets/db_password
    volumes:
      - postgres_data:/var/lib/postgresql   # PG 18+: se monta el padre, no /data (ver nota debajo)
    secrets:
      - db_user
      - db_password
    networks:
      - red_interna
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 10s
      retries: 5
    deploy:
      resources:
        limits:
          cpus: "1.5"
          memory: 5G

  vault:
    image: hashicorp/vault:latest
    restart: unless-stopped
    cap_add:
      - IPC_LOCK
    volumes:
      - vault_data:/vault/data
      - ./vault-config.hcl:/vault/config/vault-config.hcl:ro
      - ./gcp-kms-credenciales.json:/vault/gcp-credenciales.json:ro
    environment:
      - GOOGLE_APPLICATION_CREDENTIALS=/vault/gcp-credenciales.json
    command: server -config=/vault/config/vault-config.hcl
    networks:
      - red_interna
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://127.0.0.1:8200/v1/sys/health?standbyok=true"]
      interval: 10s
      retries: 5
    deploy:
      resources:
        limits:
          cpus: "0.5"
          memory: 1G

  # pgAdmin NO se levanta con "docker compose up" normal — solo bajo demanda explícita
  # mediante "docker compose --profile admin up pgadmin". Nunca expuesto vía Caddy.
  pgadmin:
    image: dpage/pgadmin4:latest
    profiles: ["admin"]
    environment:
      - PGADMIN_DEFAULT_EMAIL=usted@dominio.com
      - PGADMIN_DEFAULT_PASSWORD_FILE=/run/secrets/pgadmin_password
    networks:
      - red_interna
    secrets:
      - pgadmin_password

secrets:
  db_user:
    file: ./secrets/db_user.txt
  db_password:
    file: ./secrets/db_password.txt
  pgadmin_password:
    file: ./secrets/pgadmin_password.txt

networks:
  red_publica:
  red_interna:
    internal: true    # PostgreSQL y Vault nunca son alcanzables desde fuera de la VM

volumes:
  postgres_data:
  vault_data:
  caddy_data:
```

La red `red_interna` está marcada `internal: true` deliberadamente: PostgreSQL y Vault quedan inalcanzables desde internet incluso ante un error de configuración de firewall en la VM — la única puerta de entrada real es Caddy, en 80/443.

**Corrección (detectada en Fase 0, verificación local):** las imágenes oficiales de PostgreSQL 18+ cambiaron la convención de montaje de volumen — ya no organizan los datos directamente en la raíz del punto de montaje, sino en un subdirectorio específico de la versión mayor (compatibilidad con `pg_ctlcluster`). Montar en `/var/lib/postgresql/data` como en versiones anteriores hace que el contenedor detecte "datos en un volumen no usado" y quede en bucle de reinicio. El punto de montaje correcto para PG 18+ es el directorio padre, `/var/lib/postgresql` (ya corregido arriba). Aplica igual al compose de desarrollo local.

### 9.3 `vault-config.hcl` — auto-unseal vía GCP KMS

```hcl
storage "raft" {
  path    = "/vault/data"
  node_id = "vault-nodo-1"
}

seal "gcpckms" {
  project    = "su-proyecto-gcp"
  region     = "us-central1"
  key_ring   = "vault-unseal"
  crypto_key = "vault-unseal-key"
}

listener "tcp" {
  address     = "0.0.0.0:8200"
  tls_disable = true   # correcto: Vault no está expuesto fuera de red_interna; TLS externo lo cubre Caddy
}

api_addr     = "http://vault:8200"
cluster_addr = "http://vault:8200"
```

Backend de almacenamiento **Raft integrado**, no el backend `file` heredado — es la recomendación vigente de HashiCorp para nodo único y permite escalar a clúster sin migrar de backend si el negocio crece. La inicialización (`vault operator init`, que genera las 3 llaves de recuperación Shamir ya diseñadas) es un paso manual único ejecutado por el operador — no se automatiza. A partir de esa inicialización, cada reinicio posterior se autodesbloquea vía GCP KMS sin intervención humana.

### 9.4 `Dockerfile` de la aplicación

```dockerfile
# ── Etapa 1: compilación ──────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN ./mvnw dependency:go-offline -B

COPY src src
RUN ./mvnw clean package -DskipTests -B

RUN mkdir -p target/extracted && \
    java -Djarmode=layertools -jar target/*.jar extract --destination target/extracted

# ── Etapa 2: runtime — imagen mínima, sin herramientas de compilación ──
FROM eclipse-temurin:21-jre-jammy

RUN groupadd -r appgroup && useradd -r -g appgroup appuser

WORKDIR /app

COPY --from=build --chown=appuser:appgroup /workspace/target/extracted/dependencies/ ./
COPY --from=build --chown=appuser:appgroup /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build --chown=appuser:appgroup /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=appuser:appgroup /workspace/target/extracted/application/ ./

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

Requiere agregar la dependencia `spring-boot-starter-actuator` (necesaria para que `/actuator/health` exista y para que los `depends_on: condition: service_healthy` del `docker-compose.yml` funcionen). Construir directamente sobre la propia VM Ampere A1 (arquitectura `arm64` nativa) — construir desde una máquina `amd64` sin `docker buildx --platform linux/arm64` produce una imagen incompatible que falla al arrancar.

### 9.5 Versionado de esquema — Flyway, no aplicación manual de SQL

Todo el SQL de la sección 4 se traduce a archivos de migración versionados (`V1__esquema_inicial.sql`, `V2__separar_credenciales_por_ambiente.sql`, etc.) ejecutados por Flyway en el arranque de la aplicación. El documento consolidado sigue siendo la fuente de verdad para el diseño; los archivos de Flyway son la fuente de verdad para la ejecución — complementarios, no duplicados.

### 9.6 Acceso administrativo a PostgreSQL — sin servicio permanente expuesto

Se descarta pgAdmin como servicio siempre encendido: es superficie de ataque adicional con acceso potencialmente total a datos de todos los tenants, y evade por completo `@TenantId`/`TenantContext` al conversar directamente con PostgreSQL. Dos vías aceptadas, ninguna expuesta a internet:

1. **Túnel SSH** (`ssh -L 5432:localhost:5432 usuario@vm`) + cliente PostgreSQL local — opción preferida para inspección esporádica.
2. **pgAdmin bajo `profiles: ["admin"]`** en el `docker-compose.yml` (sección 9.2) — nunca se levanta por defecto, solo con `docker compose --profile admin up pgadmin`, y solo dentro de `red_interna`.

En ambos casos, usar un rol de PostgreSQL de solo lectura (`soporte_lectura`, `GRANT SELECT` únicamente) para inspección manual — nunca las credenciales de superusuario de la aplicación.

### 9.7 `pom.xml` — dependencias Maven del proyecto Spring Boot

Corrige dos errores reales detectados en un borrador previo (coordenadas de Maven inexistentes: `spring-boot-starter-webmvc` y cinco variantes `-test` mal formadas) y sustituye la Categoría A del generador de PDF (iText, licencia AGPLv3 — obligaría a divulgar el código fuente completo de la aplicación bajo un despliegue en red, o a negociar una licencia comercial de costo no publicado) por Apache PDFBox (licencia Apache 2.0, sin esa obligación). También retira `dev.samstevens.totp` (sin publicaciones desde 2021, con una dependencia declarada hacia `spring-boot-autoconfigure` en versión 2.2.5.RELEASE) en favor de una implementación propia de TOTP (RFC 6238) sobre `javax.crypto`, usando ZXing —activamente mantenido— únicamente para la generación de la imagen QR de enrolamiento.

**Hallazgo relevante de Spring Boot 4.x:** cada starter principal ahora publica un starter de prueba compañero (`spring-boot-starter-*-test`), un cambio de convención frente a Spring Boot 3.x y anteriores, donde existía un único `spring-boot-starter-test` genérico. Verificado contra Maven Central antes de incluirlo aquí.

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>

    <dependency>
        <groupId>org.springframework.vault</groupId>
        <artifactId>spring-vault-core</artifactId>
        <version>4.1.0</version>
    </dependency>

    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>

    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-database-postgresql</artifactId>
    </dependency>

    <!-- Generación de PDF — reemplaza a iText (AGPLv3) -->
    <dependency>
        <groupId>org.apache.pdfbox</groupId>
        <artifactId>pdfbox</artifactId>
        <version>3.0.7</version>
    </dependency>

    <!-- Requerido por Argon2PasswordEncoder de Spring Security; no lo implementa internamente -->
    <dependency>
        <groupId>org.bouncycastle</groupId>
        <artifactId>bcprov-jdk18on</artifactId>
        <version>1.84</version>
    </dependency>

    <!-- TOTP: implementación propia sobre javax.crypto (RFC 6238) — reemplaza a dev.samstevens.totp -->
    <dependency>
        <groupId>com.google.zxing</groupId>
        <artifactId>core</artifactId>
        <version>3.5.3</version>
    </dependency>
    <dependency>
        <groupId>com.google.zxing</groupId>
        <artifactId>javase</artifactId>
        <version>3.5.3</version>
    </dependency>

    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-api</artifactId>
        <version>0.13.0</version>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-impl</artifactId>
        <version>0.13.0</version>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-jackson</artifactId>
        <version>0.13.0</version>
        <scope>runtime</scope>
    </dependency>

    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>

    <!-- Spring Boot 4.x: starters de prueba compañeros, no un único starter genérico -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

Se descarta el import de `spring-cloud-dependencies` como BOM: ninguna dependencia del proyecto pertenece al ecosistema Spring Cloud (`spring-vault-core` es un proyecto independiente, `org.springframework.vault`, no `org.springframework.cloud`) — incluirlo sería peso muerto sin propósito.

## 10. Pendientes explícitos (no resueltos todavía)

- [ ] Lógica de nota de crédito (anulación de factura) y su relación con `comprobante_electronico` — diferida a Release 2 (ver sección 8.2), quedó explícitamente fuera de alcance al definir el modelo de facturación (sección 4.12-4.14).
- [x] Validar compatibilidad de la Categoría A con Jakarta EE 11 y Jackson 3: **resuelto para el cliente CABYS/consulta de contribuyente** (Fase 6) — verificado con `dependency:tree` que `tools.jackson.core:jackson-databind` (Jackson 3, el que trae Spring Boot 4.1 por defecto) depende del mismo `com.fasterxml.jackson.core:jackson-annotations` que ya usan los DTOs originales (`@JsonIgnoreProperties`) — el módulo de anotaciones nunca se renombró entre Jackson 2 y 3, así que no hizo falta ningún shim. **Sigue pendiente de verificar** para los otros 3 archivos de Categoría A reservados para Fase 8/9 (firma XML-DSig/XAdES-BES, generador XML v4.4, cliente OAuth de Hacienda) al llegar a esa fase.
- [ ] Confirmar en el servicio de alta de producto que el campo `impuesto` de la respuesta de la API de CABYS no llegue vacío o nulo antes de permitir guardar el producto — mismo principio de `NOT NULL` que ya aplica a `codigo_cabys`.
- [ ] Mecanismo de revalidación periódica de productos ante una eventual reclasificación de tarifa de un código CABYS por parte de Hacienda (`cabys_validado_en` registra la fecha de validación, pero hoy nada dispara una revalidación futura). No bloquea Release 1; queda documentado como riesgo conocido, no como sorpresa futura.
asterxml.jackson.core:jackson-annotations` que ya usan los DTOs originales (`@JsonIgnoreProperties`) — el módulo de anotaciones nunca se renombró entre Jackson 2 y 3, así que no hizo falta ningún shim. **Sigue pendiente de verificar** para los otros 3 archivos de Categoría A reservados para Fase 8/9 (firma XML-DSig/XAdES-BES, generador XML v4.4, cliente OAuth de Hacienda) al llegar a esa fase.
- [ ] **Códigos CABYS no se revalidan tras su alta** (detectado en Fase 6): `producto.cabys_validado_en` prueba que el código y su `porcentaje_impuesto` se validaron contra `api.hacienda.go.cr` en el momento de crear el producto, pero nada dispara una revalidación posterior. Si Hacienda reclasifica un código CABYS (p. ej. un cambio de tarifa de IVA por reforma fiscal), un producto dado de alta antes de ese cambio sigue facturando con la tarifa vieja indefinidamente sin que el sistema lo detecte. **Deliberadamente no resuelto en Release 1** (no anticipar sin evidencia real de que ocurra) — pero documentado aquí para que la brecha se conozca de antemano, no se descubra por sorpresa cuando Hacienda reclasifique algo.
