-- T-001: fn_validar_exoneracion_vigente (V10) rechazaba sus 3 casos con RAISE EXCEPTION sin
-- ERRCODE explícito -- SQLSTATE genérico P0001, el mismo que cualquier otro trigger de la base
-- (p. ej. fn_validar_tope_nota_credito, V18). GlobalExceptionHandler#manejarErrorSqlNoCategorizado
-- no podía distinguir "la exoneración venció entre la validación Java y el flush" de cualquier
-- otro P0001 sin parsear el texto del mensaje -- prohibido por ADR-046 (el frontend nunca parsea
-- mensajes, y por el mismo principio, el backend tampoco debería depender de eso para decidir).
--
-- CREATE OR REPLACE redefine fn_validar_exoneracion_vigente con exactamente la misma lógica y los
-- mismos 3 casos de V10, agregando solo `USING ERRCODE = '...'` a cada RAISE EXCEPTION. V10 no se
-- modifica (Flyway no permite editar una migración ya aplicada).
--
-- SQLSTATE propios, clase personalizada 'VX' (Validación de eXoneración): por convención de
-- PostgreSQL (Apéndice A de la documentación), las clases de SQLSTATE que inician con dígito 5-9
-- o letra I-Z están reservadas para uso de aplicaciones (las que inician con 0-4/A-H pertenecen al
-- estándar SQL); 'V' e 'X' caen en ese rango seguro.
--   VX001 -- la cliente_exoneracion referenciada está inactiva
--   VX002 -- la cliente_exoneracion referenciada venció
--   VX003 -- el tipo de exoneración es exclusivo de Nota de Crédito/Débito, no aplica a Factura Electrónica
--
-- fn_validar_mismo_tenant (también V10) NO se toca: sigue usando P0001 genérico y escalando como
-- 409 crudo -- fuera de alcance de T-001 (ver "Fuera de alcance" de la tarea).
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
            RAISE EXCEPTION 'La exoneración referenciada está inactiva'
                USING ERRCODE = 'VX001';
        END IF;

        IF v_vencimiento IS NOT NULL AND v_vencimiento < now() THEN
            RAISE EXCEPTION 'La exoneración referenciada venció el %', v_vencimiento
                USING ERRCODE = 'VX002';
        END IF;

        IF v_tipo_documento IN ('01', '05', '06', '07') THEN
            RAISE EXCEPTION 'El tipo de exoneración % es exclusivo de Nota de Crédito/Débito, no aplica a Factura Electrónica', v_tipo_documento
                USING ERRCODE = 'VX003';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
