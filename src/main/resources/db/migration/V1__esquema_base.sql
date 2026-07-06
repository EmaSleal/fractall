-- Sección 4.0: usuario y tablas de sesión/tokens
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

-- Sección 4.4: catálogo de roles (global, no personalizable por tenant)
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

-- Sección 4.5: permisos atómicos
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
