-- Cache local (read-through) de códigos CABYS de la API de Hacienda Costa Rica.
--
-- Problema que resuelve: la API pública de Hacienda (https://api.hacienda.go.cr/fe/cabys) tiene
-- límite de uso por cliente, y cada creación/edición de producto volvía a golpearla para
-- validar el mismo código CABYS una y otra vez (ver ProductoService#validarYObtenerCabys). El
-- cache lo sirve HaciendaConsultaServiceImpl -- ver su javadoc para el detalle de cuándo se
-- sirve desde cache y cuándo se llama a Hacienda en vivo.
--
-- cabys: clave natural (codigo, 13 dígitos) como PK, sin id sustituto -- mismo patrón que
-- provincia/canton/distrito (V15__catalogo_ubicacion_cr.sql): duplicar el mismo código CABYS es
-- estructuralmente imposible.
--
-- categorias se persiste como TEXT (String simple unido con un separador de control no
-- imprimible, U+001F, en la capa Java -- ver SEPARADOR_CATEGORIAS en
-- HaciendaConsultaServiceImpl), no JSONB ni array: no hay precedente de esos tipos en este
-- proyecto y no se justifica introducirlos solo para esto.
CREATE TABLE cabys (
    codigo         VARCHAR(13)  PRIMARY KEY,
    descripcion    VARCHAR(255) NOT NULL,
    categorias     TEXT,
    impuesto       SMALLINT,
    uri            VARCHAR(255),
    estado         VARCHAR(20),
    actualizado_en TIMESTAMP    NOT NULL
);

-- cabys_consulta_log: registro crudo de cada item que Hacienda devolvió en una consulta CABYS
-- (por código o por texto), sin deduplicar ni resolver contra el cache todavía -- eso lo hace
-- CabysReconciliacionJob de forma asíncrona (diaria), no la llamada HTTP en sí, para no
-- alargar la respuesta al usuario que está creando/editando un producto.
CREATE TABLE cabys_consulta_log (
    id             UUID         PRIMARY KEY DEFAULT uuidv7(),
    codigo         VARCHAR(13)  NOT NULL,
    descripcion    VARCHAR(255),
    categorias     TEXT,
    impuesto       SMALLINT,
    uri            VARCHAR(255),
    estado         VARCHAR(20),
    consultado_en  TIMESTAMP    NOT NULL,
    procesado      BOOLEAN      NOT NULL DEFAULT FALSE,
    procesado_en   TIMESTAMP
);

-- El job de reconciliación diario solo consulta filas sin procesar; un índice parcial evita
-- escanear toda la tabla a medida que las filas ya procesadas se acumulan (mismo patrón que
-- ix_cola_reintento_email_pendientes en V5__cola_reintento_email.sql).
CREATE INDEX ix_cabys_consulta_log_pendientes
    ON cabys_consulta_log (procesado)
    WHERE procesado = FALSE;
