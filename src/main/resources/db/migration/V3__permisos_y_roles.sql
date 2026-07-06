-- Sección 4.3: relación usuario-empresa
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

-- Sección 4.6: mapeo rol -> permiso (versionado)
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

-- Sección 4.7: excepciones puntuales por membresía (usuario_permiso)
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

-- Sección 4.8: vista de permisos efectivos (para congelar en el JWT al emitir el token)
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
