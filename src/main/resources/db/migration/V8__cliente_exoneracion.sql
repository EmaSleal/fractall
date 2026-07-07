-- Fase 6, sección 4.15: cliente_exoneracion -- autorizaciones de exoneración por cliente.
-- Solo la tabla en sí: los 3 campos de exoneración en linea_factura (exoneracion_id,
-- porcentaje_exoneracion_aplicado, monto_exoneracion_aplicado) y los triggers
-- fn_validar_exoneracion_vigente / la extensión de fn_validar_mismo_tenant (sección 4.15.2 y
-- 4.16) quedan fuera de alcance de esta migración -- son trabajo de la Fase 7, cuando
-- cliente_exoneracion se consuma por primera vez desde la creación de una línea de factura.
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
