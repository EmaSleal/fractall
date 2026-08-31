-- Release 3, Fase D (reporte-flujo-caja, PR1): corrige un defecto compartido de raiz entre
-- fn_validar_alcance_cobro_factura (V23, ruta de escritura) y factura_estado_cobro (V23, ruta de
-- lectura) -- ver diseño de sdd/reporte-flujo-caja/design, Decision B11.
--
-- Raiz comun: una Nota de Credito/Debito hereda condicion_venta de su factura origen
-- (NotaCreditoDebitoService.java:270,338), asi que pasa cualquier chequeo que mire solo
-- condicion_venta sin confirmar ademas que la fila es realmente un documento de venta cobrable
-- (tipo_comprobante IN ('01','04'), Factura o Tiquete -- nunca '02'/'03', ND/NC, que son ajustes,
-- no ventas). Antes de este fix:
--   - Escritura: POST /facturas/{ncFacturaId}/cobros contra una NC aceptada cuya condicion_venta
--     heredada esta en ('02','03','04') INSERTA un cobro_factura real contra un documento que
--     nunca fue una venta cobrable, contaminando el "saldo" derivado de esa NC.
--   - Lectura: factura_estado_cobro reporta esa misma NC como su propia fila PENDIENTE, un
--     "deuda" espuria que nunca existio.
--
-- Ambos son CREATE OR REPLACE: misma firma/columnas, sin DROP. Rollback = re-aplicar el cuerpo
-- V23 de ambos objetos verbatim (otro CREATE OR REPLACE, nunca un DROP); ningun dato existente en
-- cobro_factura/factura se pierde o altera en ninguna direccion.
--
-- Deliberadamente NO incluido aqui (fuera de alcance, no aprobado por el usuario): agregar
-- estado = 'ACEPTADO' al base set de la vista -- eso es un gap distinto (cualquier factura no
-- aceptada, no solo NC/ND, sigue apareciendo en la vista hoy y despues de V24).
--
-- DESVIACION vs. el diseno original (obs #918, Decision B11): el diseno proponia JOIN (INNER) a
-- comprobante_electronico en ambos objetos, razonando (finding 9) que "una factura sin
-- comprobante_electronico no deberia existir para una factura ya emitida". La corrida del gate de
-- no-regresion obligatorio (tarea 1.6) demostro que esa premisa es falsa para la suite Fase C
-- existente: AislamientoMultiTenantTest construye facturas con factura_id NUNCA acompanadas de un
-- comprobante_electronico (p.ej. cobroSobreFacturaDeContadoEsRechazado,
-- topeCobroBloqueaExcesoSobreElSaldoNeto, vistaReportaEstadosCorrectosParaCreditoConsignacionYApartado)
-- -- un INNER JOIN las hace desaparecer de la vista y las rechaza en el trigger con un mensaje de
-- "inexistente" en vez de dejar que la regla de negocio real (condicion_venta, o el trigger de
-- aislamiento de tenant que corre despues) se ejecute. Se usa LEFT JOIN en su lugar: el chequeo de
-- tipo_comprobante solo se aplica CUANDO existe un comprobante_electronico, preservando el
-- comportamiento pre-V24 para facturas sin comprobante (nunca se veian afectadas por esta regla) y
-- preservando el fix real -- una NC/ND SIEMPRE tiene un comprobante_electronico propio (se crea en
-- la misma transaccion que la factura, ver NotaCreditoDebitoService), asi que v_tipo_comprobante
-- nunca es NULL para el caso que este fix corrige.

-- =========================================================================
-- 1. Fix de escritura: fn_validar_alcance_cobro_factura ahora tambien exige tipo_comprobante
--    CUANDO existe un comprobante_electronico (LEFT JOIN, ver desviacion arriba).
-- =========================================================================
CREATE OR REPLACE FUNCTION fn_validar_alcance_cobro_factura()
RETURNS TRIGGER AS $$
DECLARE
    v_condicion_venta VARCHAR(2);
    v_tipo_comprobante VARCHAR(2);
BEGIN
    SELECT f.condicion_venta, ce.tipo_comprobante
      INTO v_condicion_venta, v_tipo_comprobante
    FROM factura f
    LEFT JOIN comprobante_electronico ce ON ce.factura_id = f.id
    WHERE f.id = NEW.factura_id;

    IF v_condicion_venta IS NULL OR v_condicion_venta NOT IN ('02', '03', '04')
       OR (v_tipo_comprobante IS NOT NULL AND v_tipo_comprobante NOT IN ('01', '04')) THEN
        RAISE EXCEPTION 'La factura % tiene condicion_venta % y tipo_comprobante % -- el registro de cobros solo aplica a Facturas (01) o Tiquetes (04) en credito (02), consignacion (03) o apartado (04)',
            NEW.factura_id, COALESCE(v_condicion_venta, 'inexistente'), COALESCE(v_tipo_comprobante, 'inexistente');
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- El trigger trg_validar_alcance_cobro_factura (V23) referencia esta funcion por nombre y no
-- necesita recrearse -- CREATE OR REPLACE FUNCTION reemplaza el cuerpo completo.

-- =========================================================================
-- 2. Fix de lectura: factura_estado_cobro ahora tambien exige tipo_comprobante en el base set,
--    CUANDO existe un comprobante_electronico (LEFT JOIN, ver desviacion arriba).
-- =========================================================================
-- factura_id es UNIQUE en comprobante_electronico (V4:78, nunca eliminado), asi que el LEFT JOIN
-- no puede producir fan-out (0-o-1 fila por factura), igual que el INNER JOIN que proponia el
-- diseno original -- la diferencia es unicamente la tolerancia a la ausencia de comprobante.
CREATE OR REPLACE VIEW factura_estado_cobro AS
SELECT base.factura_id,
       base.empresa_id,
       base.total,
       base.total_nota_credito,
       base.total_neto,
       base.total_cobrado,
       CAST(base.total_neto - base.total_cobrado AS NUMERIC(14,5)) AS saldo_pendiente,
       CAST(
           CASE
               WHEN base.total_neto <= 0 THEN 'COBRADO'
               WHEN base.total_cobrado = 0 THEN 'PENDIENTE'
               WHEN base.total_cobrado < base.total_neto THEN 'PARCIAL'
               ELSE 'COBRADO'
           END AS VARCHAR(20)) AS estado_cobro
FROM (
    SELECT f.id AS factura_id, f.empresa_id AS empresa_id, f.total AS total,
           nc.total_nc AS total_nota_credito,
           CAST(f.total - nc.total_nc AS NUMERIC(14,5)) AS total_neto,
           c.total_cobrado AS total_cobrado
    FROM factura f
    LEFT JOIN comprobante_electronico ce ON ce.factura_id = f.id     -- NUEVO (V24)
    CROSS JOIN LATERAL (
        SELECT CAST(COALESCE(SUM(nc2.total), 0) AS NUMERIC(14,5)) AS total_nc
        FROM factura nc2
        JOIN comprobante_electronico nc_ce ON nc_ce.factura_id = nc2.id
        WHERE nc2.factura_referencia_id = f.id
          AND nc_ce.tipo_comprobante = '03'
          AND nc_ce.estado = 'ACEPTADO'
    ) nc
    CROSS JOIN LATERAL (
        SELECT CAST(COALESCE(SUM(cf.monto_cobrado), 0) AS NUMERIC(14,5)) AS total_cobrado
        FROM cobro_factura cf
        WHERE cf.factura_id = f.id
    ) c
    WHERE f.condicion_venta IN ('02', '03', '04')
      AND (ce.tipo_comprobante IS NULL OR ce.tipo_comprobante IN ('01', '04'))  -- NUEVO (V24)
) base;
