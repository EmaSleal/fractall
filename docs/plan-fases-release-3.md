# Plan de Implementación por Fases — Release 3 (Multiusuario, Resiliencia de Reintentos, Reportes Fiscales)

> Documento complementario a `arquitectura-facturacion-electronica-cr.md`, `endpoints-faltantes-frontend.md`, `plan-fases-release-1.md` y `plan-fases-release-2.md`. Mismo principio de esos documentos: el orden de fases sigue dependencia técnica estricta, no prioridad comercial. Las referencias de sección apuntan al documento de arquitectura salvo que se indique lo contrario.

---

## Principio rector del orden de fases

Release 3 agrupa tres iniciativas de origen distinto, y ese origen distinto es exactamente lo que determina el orden:

1. **Un defecto activo en producción** (reintentos de comprobantes estancados) — no es una funcionalidad nueva, es una regresión de resiliencia sobre algo que ya debería funcionar. Se trata como incidente, no como feature: diagnóstico completo antes de cualquier cambio de código, y no se cierra hasta tener solución integral, no un parche sobre el síntoma reportado.
2. **Alcance original diferido desde Release 1** (invitación de usuarios, cambio de tenant en caliente) — el modelo de datos (`usuario_empresa`, N:N) ya existe y está probado; el trabajo es casi exclusivamente de interfaz.
3. **Iniciativa nueva no contemplada en el plan original** (reportes de IVA y flujo de caja, motivada por la evaluación del modelo de suscripción premium) — requiere una entidad nueva (`cobro_factura`) antes de poder construirse honestamente.

Estas tres líneas no tienen dependencia técnica entre sí — pueden trabajarse en paralelo si hay más de una persona, pero se documentan en orden de prioridad de riesgo: primero lo que ya está roto en producción, después lo que ya estaba comprometido desde el inicio del proyecto, al final lo que es alcance nuevo.

**Fuera de alcance de Release 3:** el módulo POS móvil ("Release 3.5") evaluado en paralelo para el modelo de suscripción premium. Es una iniciativa distinta con su propio scoping — no se mezcla con este plan salvo decisión explícita en contrario.

---

## Fase A — Diagnóstico y corrección de reintentos (`ComprobanteHaciendaPollingScheduledJob`) — ✅ CERRADA

> **Cierre (2026-08-27):** implementada y mergeada a `main` vía PRs [#18](https://github.com/EmaSleal/fractall/pull/18)-[#24](https://github.com/EmaSleal/fractall/pull/24) (change SDD `reintentos-hacienda-causa-fallo`). El nombre real de la clase es `ComprobanteHaciendaPollingScheduledJob`, no `ComprobanteReintentosJob` como decía este documento. Ver sección "Diagnóstico real y desviaciones del plan" al final de esta fase para el detalle completo de qué hipótesis se confirmaron y cuál no.

**Contexto:** comprobantes en estado `ENVIADO` desde hace más de 8 horas, con evidencia de un solo reintento y ningún cambio de estado posterior. Comportamiento reportado en producción, no reproducido aún de forma controlada.

### A.1 — Análisis estructural (sin tocar código de producción)

**Trabajo:**
- Extraer logs de `HaciendaComprobanteApiServiceImpl` / `ComprobanteReintentosJob` correspondientes a los comprobantes reportados (los de la captura: `00100001010000000002` a `00100001010000000005`, entre otros) — confirmar si el job corrió sobre ellos más de una vez en la ventana de 8 horas, y qué resultado registró en cada corrida.
- Confirmar contra el código fuente, no por inspección superficial, si `TenantContext.set()`/`TenantContext.clear()` está dentro del bucle de iteración por empresa o fuera de él (hipótesis de la sección 5.5 del documento de arquitectura: un job que itera sobre todas las empresas y no gestiona el `ThreadLocal` por iteración termina procesando solo la última empresa que lo dejó puesto).
- Confirmar si `HaciendaComprobanteApiServiceImpl` distingue en código, hoy, entre excepción de red/timeout/`5xx` y un `ind-estado` de Hacienda pendiente o ambiguo — documentado como pendiente explícito no resuelto en `endpoints-faltantes-frontend.md`, sección 6.
- Confirmar si existe algún tope de `intentos_consulta` diseñado, o si el job reintenta indefinidamente sin límite. Verificar contra código, no asumir ninguna de las dos opciones.
- Confirmar si `ultimo_resultado_consulta` se está escribiendo correctamente en cada corrida para los comprobantes afectados, o si permanece en `PENDIENTE`/`NULL` pese a que el job ya corrió sobre ellos.
- Verificar el estado real de `POST /facturas/{id}/reenviar` (sección 5 de `endpoints-faltantes-frontend.md`) contra estos comprobantes: si la precondición (`ultimo_resultado_consulta = 'ERROR_COMUNICACION' AND intentos_consulta >= 1`) nunca se cumple porque el job no la escribe correctamente, el endpoint está construido pero es inalcanzable en la práctica.

**Criterio de salida:** lista de causa(s) raíz confirmadas contra código y logs, no hipótesis. Si aparece más de un defecto — es el escenario más probable dado lo ya documentado como pendiente — se listan todos antes de pasar a A.2. No se escribe una sola línea de corrección hasta cerrar este criterio.

### A.2 — Solución integral (no parche puntual)

**Trabajo**, condicionado a lo que confirme A.1, pero cubriendo como mínimo:
- Propagación correcta de `TenantContext` por iteración del bucle, si esa resulta ser causa confirmada — mismo patrón ya advertido en sección 5.5 del documento de arquitectura.
- Distinción explícita y correcta entre `ERROR_COMUNICACION` y estado pendiente real dentro de `HaciendaComprobanteApiServiceImpl`, con escritura consistente de `ultimo_resultado_consulta` en cada corrida, sin excepción, tal como especifica la tabla de la sección 6 de `endpoints-faltantes-frontend.md`.
- **Política de reintentos con tope definido** — no existe hoy. Definir cuántos intentos automáticos ocurren antes de que el sistema deje de reintentar solo y requiera intervención (manual vía `POST /facturas/{id}/reenviar`, o notificación al usuario). Un comprobante que reintenta indefinidamente sin resolución es un resultado tan malo como uno que deja de reintentar por error silencioso.
- Coherencia entre el mecanismo automático (`ComprobanteReintentosJob`) y el manual (`POST /facturas/{id}/reenviar`): deben quedar como una sola lógica de resiliencia con una única fuente de verdad sobre el estado de reintento, no dos caminos que puedan contradecirse entre sí.
- Notificación al usuario (por correo, vía Resend) cuando un comprobante agota su tope de reintentos automáticos y queda pendiente de acción manual — sin esto, el usuario no tiene forma de enterarse de que Hacienda nunca resolvió su comprobante.

**Criterio de salida:** un comprobante forzado artificialmente a fallar por comunicación en un ambiente de prueba (mock de timeout/5xx contra la API de Hacienda) pasa por el ciclo completo — detección correcta de `ERROR_COMUNICACION`, reintentos automáticos respetando el tope definido, notificación al usuario al agotar el tope, y disponibilidad correcta de `POST /facturas/{id}/reenviar` en ese punto — sin intervención manual en el medio y con test de integración corriendo en CI, mismo criterio de rigor no negociable que ya aplica `AislamientoMultiTenantTest`. **Cumplido — ver diagnóstico real abajo.**

### Diagnóstico real y desviaciones del plan (post-implementación)

La investigación contra código (no contra hipótesis) descartó las tres causas que este documento daba por probables, y encontró una distinta:

- **`TenantContext` por iteración (línea 29 arriba):** ya estaba bien — `set()`/`clear()` corre dentro de un `try/finally` por cada empresa del bucle. No era la causa.
- **Distinción comunicación vs. pendiente (línea 30):** ya existía — `ComprobanteHaciendaEnvioService.mapearResultadoConsulta()` ya mapeaba a 4 valores. No era la causa.
- **Tope de intentos (línea 31):** ya existía — `MAX_INTENTOS = 10` con backoff exponencial (5 min a 120 min de tope). No era la causa.
- **Precondición de `POST /facturas/{id}/reenviar` (línea 33):** la precondición descrita acá (`ultimo_resultado_consulta = 'ERROR_COMUNICACION' AND intentos_consulta >= 1`) **nunca se implementó así**. La real es `estado ∈ {FIRMADO, RECHAZADO, ERROR}` (`ComprobanteEmisionService.ESTADOS_REENVIABLES`) — no depende de `ultimo_resultado_consulta`. El endpoint sí era alcanzable; esta línea del plan describía una precondición fantasma.
- **Causa raíz real:** cuando `consultarYActualizar` lanzaba una excepción (el caso real y frecuente: `CredencialHaciendaNoEncontradaException`, y antes del fix también cualquier fallo no absorbido por el fallback del circuit breaker), `registrarIntentoFallidoYGuardar` actualizaba `intentosEnvio`/`fechaRespuesta` pero **nunca** `ultimoResultadoConsulta`/`fechaUltimaConsultaHacienda` — quedaban stale pese a que el job seguía reintentando activamente. Ese fue el bug real detrás de "un solo reintento visible, ningún cambio de estado posterior".
- **Hallazgo adicional no anticipado:** `HaciendaComprobanteApiServiceImpl.consultarComprobanteFallback` (fallback de `@CircuitBreaker`) declaraba su parámetro como `Throwable` — Resilience4j matchea fallbacks por tipo declarado, así que interceptaba cualquier excepción, no solo cuando el circuito estaba abierto. Tuvo que angostarse a `CallNotPermittedException` para que la clasificación de causa pudiera siquiera llegar al job.

**Alcance final implementado** (más específico que lo redactado en A.2): clasificación de causa en dos categorías —
- **Comunicación** (timeout, conexión rechazada, 5xx): sigue el tope de 10 intentos con backoff existente, sin cambios.
- **Configuración/autenticación** (401 persistente tras refresh de token, credencial no encontrada, certificado inválido): escala a `ESTADO_ERROR` en **1 solo intento** — reintentar con las mismas credenciales rotas no cambia el resultado. Incluye corte por credencial dentro del mismo ciclo del job (no repite la llamada a Hacienda para el resto de comprobantes de la misma empresa+ambiente ya sabida rota) y un correo digest por empresa por ciclo (no uno por comprobante) vía `EmailNotificacionService`.
- Modelo de datos: se agregó `ERROR_CONFIGURACION` al CHECK constraint existente de `ultimo_resultado_consulta` (migración `V21`), sin columna ni contador nuevo.
- Fuera de alcance, explícitamente: `enviarComprobante` (nunca fue la causa del incidente), configuración de thresholds de Resilience4j (queda como iniciativa separada), y `POST /facturas/{id}/reenviar` (su precondición actual ya era correcta).

**Endpoints:** ningún endpoint HTTP fue agregado ni modificado en esta fase — el cambio fue interno al job y al adaptador de Hacienda. `endpoints-faltantes-frontend.md` no requiere actualización por esta fase.

**Evidencia:** 491/491 tests pasando, `sdd-verify` con veredicto PASS WITH WARNINGS (ambos warnings cerrados antes de mergear). PRs #18-#23 (unidades de trabajo, stacked-to-main) + #24 (aterrizaje final a `main`, necesario porque los PRs intermedios se habían mergeado entre sí sin retargetear a `main`).

---

## Fase B — Invitación de usuarios y cambio de tenant en caliente — ✅ CERRADA

> **Cierre (2026-08-27):** implementada vía 9 unidades de trabajo encadenadas (PR1–PR6, `stacked-to-main`, change SDD `invitacion-usuarios-cambio-tenant`). Rama final `feat/invitacion-usuarios-pr6`, aún no mergeada a `main` — pendiente de apertura de PR(s) por parte del orquestador. Ver `sdd/invitacion-usuarios-cambio-tenant/design` (Engram) para el detalle de las tres discrepancias que el diseño encontró contra este mismo documento, corregidas en el texto de abajo.

**Contexto:** el modelo de datos (`usuario_empresa`, N:N, sección 3.2 del documento de arquitectura) y el catálogo de permisos correspondiente (`usuario.invitar`, `usuario.ver`, `usuario.editar_rol`, `usuario.suspender`, `permiso.personalizar`) ya existían desde Release 1/2. **Corrección post-diseño:** eso fue lo único que ya existía. Los 5 endpoints de `/usuarios/*`, el guard de autorización (`PermisoGuard`), la tabla `invitacion_usuario`, el ciclo de vida del token de invitación, la rama de registro por invitación (`registrarPorInvitacion`) y el enganche de MFA en aceptación/promoción eran, todos, trabajo de backend completamente nuevo — no "trabajo de interfaz sobre un backend ya construido".
- `POST /usuarios/invitar`: genera invitación por correo (mismo patrón de token aleatorio + hash almacenado + token crudo enviado, ya usado en verificación de email, sección 3.1). Requiere permiso `usuario.invitar`. **Corrección post-diseño:** ese permiso está sembrado con `critico=false` (`V1__esquema_base.sql`) y lo tienen tanto `ADMIN_EMPRESA` como `SUPER_ADMIN` (`V20__poblar_rol_permiso.sql`) — no es "exclusivo de `ADMIN_EMPRESA`" ni está marcado como no personalizable por catálogo; el usuario del cambio decidió explícitamente dejarlo así por ahora (decisión D5 del proposal), no que esa fuera ya la política vigente.
- `POST /usuarios/invitacion/{token}/aceptar`: crea la fila `usuario_empresa` con el rol asignado en la invitación. Si el correo invitado no tiene cuenta `usuario` existente, deriva a flujo de registro con el token de invitación preservado en el estado — no se pierde el contexto de la invitación por tener que registrarse primero.
- `GET /usuarios` (listado de miembros de la empresa activa), `PATCH /usuarios/{id}/rol`, `POST /usuarios/{id}/suspender` — superficie mínima de administración de membresías, gated por los permisos ya existentes.
- `POST /auth/cambiar-tenant` (sección 3.2): **corrección post-diseño** — ya estaba completamente implementado y probado (`SesionService.cambiarTenant` + `AuthController.cambiarTenant`) antes de este cambio, y este cambio no lo tocó ni le agregó cobertura nueva; no era un endpoint pendiente de "confirmar o completar".
- Frontend: selector de tenant visible en el layout principal solo cuando el usuario tiene 2+ membresías activas (`usuario_empresa`); pantalla de administración de miembros dentro de la sección Empresa existente (no una entrada nueva de sidebar, mismo criterio ya aplicado en Release 2 Fase D); flujo de aceptación de invitación como página pública (`/invitacion/[token]`).
- MFA: si el usuario invitado va a recibir rol `ADMIN_EMPRESA`, la obligatoriedad de TOTP (sección 3.3) debe activarse en el mismo flujo de aceptación, no quedar pendiente hasta el primer login posterior.

**Criterio de salida:** un `ADMIN_EMPRESA` invita a un correo nuevo con rol `EMPLEADO_FACTURACION`; el invitado acepta, se registra si no tenía cuenta, y aparece en el listado de miembros. Un usuario con membresía en dos empresas de prueba cambia de tenant desde la interfaz sin volver a autenticarse, y el aislamiento multi-tenant (`AislamientoMultiTenantTest`) se extiende para confirmar que el cambio de tenant no filtra datos de la empresa anterior en la sesión nueva. **Cumplido para el backend** (los 5 endpoints, el token lifecycle, el hook MFA, y `AislamientoMultiTenantTest` extendido con los 3 casos de cambio de tenant); el frontend de esta fase permanece en Fase E, sin cambios por este cierre.

---

## Fase C — Modelo de datos: `cobro_factura`

**Motivación:** `factura` registra `condicion_venta` y `plazo_credito`, pero no existe ningún campo que registre la fecha real de cobro de una venta a crédito — el esquema actual asume que "facturado" equivale a "cobrado", válido solo para venta de contado. Sin esto, cualquier reporte de flujo de caja sería, en el mejor caso, un reporte de ventas facturadas mal etiquetado.

**Trabajo:**
- Migración nueva:

```sql
CREATE TABLE cobro_factura (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    empresa_id      UUID NOT NULL REFERENCES empresa(id),  -- @TenantId
    factura_id      UUID NOT NULL REFERENCES factura(id),
    monto_cobrado   NUMERIC(14,5) NOT NULL CHECK (monto_cobrado > 0),
    fecha_cobro     TIMESTAMP NOT NULL DEFAULT now(),
    medio_pago      VARCHAR(2) NOT NULL,
    referencia      VARCHAR(100),
    registrado_por  UUID NOT NULL REFERENCES usuario(id),
    create_date     TIMESTAMP NOT NULL DEFAULT now()
);
```

- `TenantAwareEntity` como superclase (mismo patrón obligatorio que toda entidad de negocio, sección 5.1) — no declarar `empresaId` por su cuenta.
- Trigger de tope de sobre-cobro (`BEFORE INSERT ON cobro_factura`): `SUM(monto_cobrado)` existente más el registro nuevo no puede exceder `factura.total`. Mismo patrón ya usado para el tope de Nota de Crédito en Release 2, Fase A.
- Trigger de restricción a venta a crédito (`BEFORE INSERT ON cobro_factura`): rechaza si `factura.condicion_venta <> '02'`. Una factura de contado se considera cobrada en el momento de la emisión — permitir un registro de cobro aparte abriría una vía de doble conteo.
- Extender `fn_validar_mismo_tenant` (sección 4.16) para cubrir `cobro_factura.factura_id` — mismo patrón ya aplicado a `cliente_id`/`producto_id`/`exoneracion_id`/`factura_referencia_id`.
- Vista derivada, sin columna persistida de saldo (mismo principio de "no almacenar lo que ya se puede derivar" ya aplicado al saldo disponible de NC en Release 2):

```sql
CREATE VIEW factura_estado_cobro AS
SELECT f.id AS factura_id, f.empresa_id, f.total,
       COALESCE(SUM(c.monto_cobrado), 0) AS total_cobrado,
       f.total - COALESCE(SUM(c.monto_cobrado), 0) AS saldo_pendiente,
       CASE
           WHEN COALESCE(SUM(c.monto_cobrado), 0) = 0 THEN 'PENDIENTE'
           WHEN COALESCE(SUM(c.monto_cobrado), 0) < f.total THEN 'PARCIAL'
           ELSE 'COBRADO'
       END AS estado_cobro
FROM factura f
LEFT JOIN cobro_factura c ON c.factura_id = f.id
WHERE f.condicion_venta = '02'
GROUP BY f.id, f.empresa_id, f.total;
```

- `POST /facturas/{id}/cobros`: registra un cobro parcial/total. Precondición validada en backend: `factura.condicion_venta = '02'` y comprobante en estado `ACEPTADO` (no tiene sentido registrar cobro sobre una factura que Hacienda todavía no aceptó o que fue rechazada).
- `GET /facturas/{id}/cobros`: historial de cobros de una factura específica, para reconciliación.

**Criterio de salida:** extensión de `AislamientoMultiTenantTest` con los mismos tres ejes de cobertura ya exigidos en Release 2 Fase A: (a) un cobro no puede referenciar una factura de otro tenant, (b) el trigger de tope bloquea un cobro que excede el saldo pendiente, (c) un segundo cobro parcial sobre la misma factura sí se permite mientras no exceda el total, (d) un intento de registrar cobro sobre una factura de contado (`condicion_venta = '01'`) es rechazado. La vista `factura_estado_cobro` devuelve `estado_cobro` correcto para los tres casos (`PENDIENTE`/`PARCIAL`/`COBRADO`) contra datos de prueba.

---

## Fase D — Motor de reportes: IVA mensual y Flujo de Caja

**Depende de:** Fase C (el reporte de Flujo de Caja requiere `cobro_factura`/`factura_estado_cobro`).

**Trabajo:**

### D.1 — Reporte de IVA mensual (D-104)

- `GET /reportes/iva?desde=&hasta=`: agregación sobre `comprobante_electronico` (filtrado a `estado = 'ACEPTADO'` exclusivamente) JOIN `factura` JOIN `linea_factura`, agrupado por `porcentaje_impuesto_aplicado`.
- Columna separada para ventas exentas (`gravado_aplicado = false`), distinta de gravadas al 0% — fiscalmente no son lo mismo aunque el monto de impuesto resultante sea igual.
- Neteo obligatorio contra NC/ND del mismo período (`tipo_comprobante` `03`/`02`, vía `factura_referencia_id`) antes de totalizar — un IVA mensual que ignora las NC del período sobreestima la obligación real.
- Response incluye desglose por tarifa + totalizador de IVA débito fiscal del período.

### D.2 — Reporte de Flujo de Caja

- `GET /reportes/flujo-caja?desde=&hasta=`: dos series temporales separadas, no una sola cifra — ventas del período (`factura.fecha_emision`, comprobante `ACEPTADO`) y cobros del período (`cobro_factura.fecha_cobro`). Un cobro de agosto puede corresponder a una factura de julio; mezclarlas en una sola serie produce un número que no responde ni "cuánto vendí" ni "cuánto cobré".
- Desglose por `condicion_venta` (contado vs. crédito) y por `medio_pago`.
- Totalizador de cartera pendiente al cierre del período: `SUM(saldo_pendiente)` desde `factura_estado_cobro` para facturas con `fecha_emision` dentro o antes del rango consultado.
- Comparativo contra el período anterior de igual duración (mismo rango de días, desplazado hacia atrás) — no solo snapshot del período consultado.

### D.3 — Exportación en dos niveles (convención general, no exclusiva de estos dos reportes)

- **PDF:** página 1 con los agregados ya calculados (desglose por tarifa de IVA, KPIs de flujo de caja, cartera pendiente) — legible sin procesar nada más; página 2 con el detalle transaccional fila por fila.
- **Excel:** misma separación pero como hojas, no páginas — pestaña `Resumen` (agregados) y pestaña `Detalle` (una fila por transacción, sin agregación), dado que un contador que va a cruzar esta información en Excel espera pestañas separadas, no un salto de página dentro de una hoja continua.
- Esta convención de "Resumen + Detalle" queda establecida como estándar para cualquier reporte exportable futuro del sistema, no se rediseña reporte por reporte.

**Criterio de salida:** contra un set de datos de prueba con facturas gravadas a múltiples tarifas, una NC parcial, y facturas a crédito con cobros parciales registrados, ambos reportes devuelven cifras verificables a mano; la exportación PDF y Excel de ambos respeta la convención Resumen/Detalle; el reporte de IVA neteado contra NC coincide con el cálculo manual esperado.

---

## Fase E — Frontend

**Trabajo:**
- Pantallas de Fase B (invitación, listado de miembros, selector de tenant) — ver detalle de criterio de salida en esa fase.
- `(app)/facturas/[id]/registrar-cobro/` — punto de entrada desde el detalle de una factura a crédito con `estado_cobro != 'COBRADO'`; formulario simple (monto, medio de pago, referencia opcional), con el saldo pendiente visible antes de capturar el monto.
- `(app)/reportes/iva/` y `(app)/reportes/flujo-caja/` — nueva entrada de sidebar `Reportes` (única adición de nivel superior de este release; justificada porque, a diferencia de cobros/invitaciones, no vive naturalmente dentro de un módulo existente). Selector de rango de fechas, vista de dashboard con KPIs, botones de exportación PDF/Excel.
- Badge de `estado_cobro` (`PENDIENTE`/`PARCIAL`/`COBRADO`) visible en el listado de facturas y en el detalle, para facturas a crédito.
- Regenerar tipos vía `openapi-typescript` contra el spec actualizado una vez que Fase B/C/D expongan los nuevos endpoints — no tipar a mano, mismo criterio ya aplicado en Release 2.

**Criterio de salida:** flujo completo desde la interfaz para los tres frentes — invitación aceptada y cambio de tenant sin re-autenticación; cobro parcial registrado sobre una factura a crédito con el badge de estado actualizándose correctamente; reporte de flujo de caja exportado en PDF y Excel reflejando el cobro recién registrado — sin salir de sus módulos correspondientes.

---

## Fase F — Cierre de Release 3

Mismo criterio formal que la Fase E de Release 2 — evidencia guardada, no verificación manual de última hora:

- Prueba de extremo a extremo para las tres líneas de trabajo, corriendo en CI junto con `AislamientoMultiTenantTest` extendido de las Fases B y C — mismo gate, no uno paralelo.
- Test de integración de reintentos (Fase A.2) corriendo en CI de forma permanente, no solo como verificación puntual del incidente original.
- Actualizar `endpoints-faltantes-frontend.md` con los endpoints nuevos (`/usuarios/*`, `/auth/cambiar-tenant`, `/facturas/{id}/cobros`, `/reportes/iva`, `/reportes/flujo-caja`).
- Actualizar `frontend-paginas.md` con el inventario de pantallas nuevas de la Fase E.
- Actualizar `arquitectura-facturacion-electronica-cr.md` sección 8.2: mover Release 3 de "diferido"/"sin asignar" a "cerrado", con referencia a los commits correspondientes. Confirmar que la activación de ambiente `PRODUCCIÓN` (ya validada por el usuario antes de abrir este plan) queda registrada como cerrada en esa misma sección.
- Registrar en la bitácora de decisiones el diagnóstico final de causa raíz de Fase A.1 — para que una regresión similar futura no repita el mismo ciclo de diagnóstico desde cero.

---

## Resumen de dependencias entre fases

```
Fase A (diagnóstico + corrección de reintentos)         ── independiente, prioridad de incidente ──┐
                                                                                                      │
Fase B (invitación de usuarios + cambio de tenant)       ── independiente, alcance original ─────────┤
                                                                                                      │
Fase C (modelo cobro_factura)                                                                        │
   └─→ Fase D (reportes IVA + flujo de caja, depende de C)                                           │  Prueba de extremo a extremo
          └─→ Fase E (frontend, depende de B, C y D)                                                 │  en CI desde la Fase A
                 └─→ Fase F (cierre)  ←──────────────────────────────────────────────────────────────┘
```

Las Fases A, B y C no tienen dependencia técnica entre sí y pueden trabajarse en paralelo si hay más de una persona disponible. Con un desarrollador solo, el orden recomendado es A → B → C → D → E → F, priorizando primero el defecto activo en producción.
