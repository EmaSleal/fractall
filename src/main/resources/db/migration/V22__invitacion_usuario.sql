-- Tabla dedicada, NO una extension de usuario_token (ver D2 del proposal): esas dos filas
-- (VERIFICACION_EMAIL / RECUPERACION_PASSWORD) tienen usuario_id NOT NULL como invariante real,
-- y una invitacion a un email SIN cuenta no puede satisfacerlo.
--
-- empresa_id es una columna ordinaria, NO @TenantId: tanto POST /auth/registro/invitacion
-- (corre bajo TenantContextDescartable) como POST /usuarios/invitacion/{token}/aceptar (corre
-- bajo el tenant ACTUAL del invitado, que por definicion no es el que invita) deben resolver la
-- fila ANTES de conocer la empresa. Mismo criterio que usuario_empresa (V3) y credencial_hacienda.
--
-- fn_validar_mismo_tenant (V4, extendida en V10/V18/V19) NO se toca: solo esta enganchada a los
-- triggers de factura y linea_factura, y las dos FK de esta tabla apuntan a rol y usuario, ambas
-- tablas GLOBALES sin empresa_id. No hay nada cruzado que validar.
CREATE TABLE invitacion_usuario (
    id           UUID PRIMARY KEY DEFAULT uuidv7(),
    empresa_id   UUID         NOT NULL REFERENCES empresa(id),
    email        VARCHAR(255) NOT NULL,
    rol_id       UUID         NOT NULL REFERENCES rol(id),
    token_hash   VARCHAR(255) NOT NULL UNIQUE,   -- SHA-256 hex (TokenHasher), nunca el token crudo
    invitado_por UUID         NOT NULL REFERENCES usuario(id),
    expira_en    TIMESTAMP    NOT NULL,
    estado       VARCHAR(20)  NOT NULL DEFAULT 'PENDIENTE'
                     CHECK (estado IN ('PENDIENTE', 'ACEPTADA', 'REVOCADA', 'EXPIRADA')),
    create_date  TIMESTAMP    NOT NULL DEFAULT now()
);

-- Bloquea a nivel de motor una segunda invitacion viva al mismo correo en la misma empresa.
-- lower(email) porque RegistroService normaliza a minusculas al guardar (RegistroService.java:82)
-- pero la columna es case-sensitive; sin lower(), "A@x.com" y "a@x.com" serian dos invitaciones.
CREATE UNIQUE INDEX ux_invitacion_usuario_pendiente
    ON invitacion_usuario (empresa_id, lower(email))
    WHERE estado = 'PENDIENTE';

-- Ruta caliente de aceptar/registrar-por-invitacion: lookup por hash. UNIQUE ya crea el indice,
-- asi que no se agrega uno redundante. Este indice cubre el listado por empresa.
CREATE INDEX ix_invitacion_usuario_empresa ON invitacion_usuario (empresa_id);
