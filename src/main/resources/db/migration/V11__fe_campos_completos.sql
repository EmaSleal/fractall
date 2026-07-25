-- FE v4.4 Complete Fields — V11 migration
-- Adds scalar columns to factura/linea_factura and 6 new child tables required
-- for full FacturaElectronica support. All alterations use nullable columns or
-- NOT NULL DEFAULT so existing rows migrate without backfill.

-- =========================================================================
-- 1. New scalar columns on factura
-- =========================================================================
ALTER TABLE factura
    ADD COLUMN condicion_venta_otros     VARCHAR(100),
    ADD COLUMN codigo_actividad_receptor VARCHAR(6),
    ADD COLUMN total_iva_devuelto        NUMERIC(18,5) NOT NULL DEFAULT 0;

-- =========================================================================
-- 2. New scalar columns on linea_factura
-- =========================================================================
ALTER TABLE linea_factura
    ADD COLUMN tipo_transaccion          VARCHAR(2)  NOT NULL DEFAULT '01',
    ADD COLUMN unidad_medida_comercial   VARCHAR(20),
    ADD COLUMN iva_cobrado_fabrica       NUMERIC(18,5) NOT NULL DEFAULT 0,
    ADD COLUMN factor_calculo_iva        NUMERIC(5,4);

-- =========================================================================
-- 3. Child table: linea_codigo_comercial (max 5 per linea_factura)
-- =========================================================================
CREATE TABLE linea_codigo_comercial (
    id         UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    empresa_id UUID    NOT NULL,
    linea_id   UUID    NOT NULL REFERENCES linea_factura(id) ON DELETE CASCADE,
    orden      SMALLINT NOT NULL CHECK (orden BETWEEN 1 AND 5),
    tipo       VARCHAR(2)   NOT NULL,
    codigo     VARCHAR(255) NOT NULL,
    UNIQUE (linea_id, orden)
);

-- =========================================================================
-- 4. Child table: linea_descuento (max 5 per linea_factura)
-- =========================================================================
CREATE TABLE linea_descuento (
    id                    UUID     PRIMARY KEY DEFAULT gen_random_uuid(),
    empresa_id            UUID     NOT NULL,
    linea_id              UUID     NOT NULL REFERENCES linea_factura(id) ON DELETE CASCADE,
    orden                 SMALLINT NOT NULL CHECK (orden BETWEEN 1 AND 5),
    monto_descuento       NUMERIC(18,5) NOT NULL CHECK (monto_descuento >= 0),
    codigo_descuento      VARCHAR(2),
    codigo_descuento_otro VARCHAR(100),
    naturaleza_descuento  VARCHAR(80),
    UNIQUE (linea_id, orden),
    CHECK (codigo_descuento IS NULL OR codigo_descuento <> '99'
           OR (codigo_descuento_otro IS NOT NULL AND codigo_descuento_otro <> ''))
);

-- =========================================================================
-- 5. Child table: impuesto_linea_exoneracion (0..1 per linea_factura)
-- =========================================================================
CREATE TABLE impuesto_linea_exoneracion (
    id                       UUID     PRIMARY KEY DEFAULT gen_random_uuid(),
    empresa_id               UUID     NOT NULL,
    linea_id                 UUID     NOT NULL UNIQUE REFERENCES linea_factura(id) ON DELETE CASCADE,
    tipo_documento_ex1       VARCHAR(2)   NOT NULL,
    tipo_documento_otro      VARCHAR(100),
    numero_documento         VARCHAR(40)  NOT NULL,
    articulo                 INTEGER,
    inciso                   INTEGER,
    nombre_institucion       VARCHAR(2)   NOT NULL,
    nombre_institucion_otros VARCHAR(100),
    fecha_emision_ex         TIMESTAMP    NOT NULL,
    tarifa_exonerada         NUMERIC(4,2) NOT NULL,
    monto_exoneracion        NUMERIC(18,5) NOT NULL CHECK (monto_exoneracion >= 0),
    CHECK (tipo_documento_ex1 <> '99'
           OR (tipo_documento_otro IS NOT NULL AND tipo_documento_otro <> '')),
    CHECK (nombre_institucion <> '99'
           OR (nombre_institucion_otros IS NOT NULL AND nombre_institucion_otros <> ''))
);

-- =========================================================================
-- 6. Child table: factura_otros_cargos (max 15 per factura)
-- =========================================================================
CREATE TABLE factura_otros_cargos (
    id                       UUID     PRIMARY KEY DEFAULT gen_random_uuid(),
    empresa_id               UUID     NOT NULL,
    factura_id               UUID     NOT NULL REFERENCES factura(id) ON DELETE CASCADE,
    orden                    SMALLINT NOT NULL CHECK (orden BETWEEN 1 AND 15),
    tipo_documento_oc        VARCHAR(2)   NOT NULL,
    tipo_documento_otros     VARCHAR(100),
    identidad_tipo           VARCHAR(2),
    identidad_numero         VARCHAR(20),
    nombre_tercero           VARCHAR(100),
    detalle                  VARCHAR(200) NOT NULL,
    porcentaje_oc            NUMERIC(4,2),
    monto_cargo              NUMERIC(18,5) NOT NULL CHECK (monto_cargo > 0),
    UNIQUE (factura_id, orden),
    CHECK (tipo_documento_oc <> '99'
           OR (tipo_documento_otros IS NOT NULL AND tipo_documento_otros <> '')),
    CHECK ((identidad_tipo IS NULL) = (identidad_numero IS NULL))
);

-- =========================================================================
-- 7. Child table: factura_informacion_referencia (max 10 per factura)
-- =========================================================================
CREATE TABLE factura_informacion_referencia (
    id                     UUID     PRIMARY KEY DEFAULT gen_random_uuid(),
    empresa_id             UUID     NOT NULL,
    factura_id             UUID     NOT NULL REFERENCES factura(id) ON DELETE CASCADE,
    orden                  SMALLINT NOT NULL CHECK (orden BETWEEN 1 AND 10),
    tipo_doc_ir            VARCHAR(2)   NOT NULL,
    tipo_doc_ref_otro      VARCHAR(100),
    numero                 VARCHAR(50),
    fecha_emision_ir       TIMESTAMP NOT NULL,
    codigo                 VARCHAR(2),
    codigo_referencia_otro VARCHAR(100),
    razon                  VARCHAR(180),
    UNIQUE (factura_id, orden),
    CHECK (tipo_doc_ir <> '99'
           OR (tipo_doc_ref_otro IS NOT NULL AND tipo_doc_ref_otro <> '')),
    CHECK (codigo IS NULL OR codigo <> '99'
           OR (codigo_referencia_otro IS NOT NULL AND codigo_referencia_otro <> ''))
);

-- =========================================================================
-- 8. Child table: factura_medio_pago (max 4 per factura)
-- =========================================================================
CREATE TABLE factura_medio_pago (
    id               UUID     PRIMARY KEY DEFAULT gen_random_uuid(),
    empresa_id       UUID     NOT NULL,
    factura_id       UUID     NOT NULL REFERENCES factura(id) ON DELETE CASCADE,
    orden            SMALLINT NOT NULL CHECK (orden BETWEEN 1 AND 4),
    tipo_medio_pago  VARCHAR(2)    NOT NULL,
    medio_pago_otros VARCHAR(100),
    total_medio_pago NUMERIC(18,5) NOT NULL CHECK (total_medio_pago > 0),
    UNIQUE (factura_id, orden),
    CHECK (tipo_medio_pago <> '99'
           OR (medio_pago_otros IS NOT NULL AND medio_pago_otros <> ''))
);
