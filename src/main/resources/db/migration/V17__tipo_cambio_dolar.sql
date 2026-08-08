-- Cache local (read-through) del tipo de cambio del dólar publicado por Hacienda Costa Rica
-- (https://api.hacienda.go.cr/indicadores/tc/dolar).
--
-- Problema que resuelve: Costa Rica factura en colones (CRC) por defecto, pero una factura en
-- USD sin tipoCambio explícito necesita ese valor para convertir a colones -- Hacienda solo
-- publica un valor nuevo una vez al día, así que golpearla en cada factura sería tanto
-- innecesario como un riesgo de agotar el límite de uso de la API pública, mismo problema que
-- ya resuelve el cache de CABYS (V16__catalogo_cabys.sql). Lo sirve
-- HaciendaConsultaServiceImpl -- ver su javadoc para el detalle de cuándo se sirve desde cache
-- y cuándo se llama a Hacienda en vivo.
--
-- tipo_cambio_dolar: clave natural (fecha) como PK, sin id sustituto -- mismo patrón que cabys.
-- A diferencia de cabys (miles de códigos posibles), esta tabla acumula como mucho una fila por
-- día, así que NO necesita el patrón log+job de reconciliación diferida de CABYS: el propio
-- método cache-aware persiste sincrónicamente en la misma llamada que consulta a Hacienda.
--
-- fecha es la fecha del SERVIDOR al momento de la consulta (LocalDate.now() en Java), no la
-- fecha que venga en el JSON de Hacienda -- así la clave de cache es consistente con nuestra
-- propia noción de "hoy" sin importar qué fecha reporte el payload.
--
-- venta/compra en NUMERIC(10,5) para no perder precisión frente a factura.tipo_cambio
-- (V4__catalogo_y_facturacion.sql, misma escala) -- venta es el valor que se usa para
-- autocompletar factura.tipo_cambio (conversión a colones); compra se guarda por completitud
-- pero no se usa para autocompletar nada.
CREATE TABLE tipo_cambio_dolar (
    fecha         DATE          PRIMARY KEY,
    venta         NUMERIC(10,5) NOT NULL,
    compra        NUMERIC(10,5) NOT NULL,
    consultado_en TIMESTAMP     NOT NULL
);
