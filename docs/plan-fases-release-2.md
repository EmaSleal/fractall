# Plan de Implementación por Fases — Release 2 (Nota de Crédito, Nota de Débito, Tiquete Electrónico)

> Documento complementario a `arquitectura-facturacion-electronica-cr.md` y `plan-fases-release-1.md`. Mismo principio de ese documento: el orden de fases sigue dependencia técnica estricta, no prioridad comercial. Las referencias de sección (ej. "sección 4.15") apuntan al documento de arquitectura salvo que se indique lo contrario.

---

## Principio rector del orden de fases

Igual que en Release 1: ninguna fase se adelanta a otra que necesita como cimentación. A diferencia de Release 1, acá no se parte de cero — el motor de emisión (`ClaveNumericaGenerator`, `contador_consecutivo` por `tipo_comprobante`, el modelo `FacturaInformacionReferencia` con sus catálogos `CodigoReferencia`/`TipoDocumentoIR`) ya existe y ya está probado para Factura Electrónica. Release 2 es extensión de ese motor, no reconstrucción — y el orden de fases refleja exactamente eso: primero lo que falta a nivel de datos y reglas de negocio, después la parametrización del motor de emisión, después el tipo de documento estructuralmente más distinto (Tiquete), y al final el frontend.

**Alcance de Release 2:** Nota de Crédito (tipo `03`), Nota de Débito (tipo `02`), Tiquete Electrónico (tipo `04`). Invitación de usuarios adicionales queda fuera — es Release 3 (ver `arquitectura-facturacion-electronica-cr.md`, sección 8.2 actualizada).

---

## Reglas de negocio confirmadas (no renegociables sin volver a esta discusión)

1. **Precondición dura de Hacienda, no de producto:** una NC/ND solo puede emitirse contra una factura en estado `ACEPTADO`. Si la factura origen fue rechazada, se descarta y se emite una factura nueva — nunca una NC sobre un documento que Hacienda nunca aceptó. Fuente: normativa de Hacienda, no una decisión de Fractall.
2. **NC soporta anulación total y crédito parcial.** Se modela igual que `Factura` (propia `factura` + `linea_factura`, `tipo_comprobante = '03'`), donde el usuario selecciona líneas/cantidades de la factura origen a acreditar. La "anulación total" no es un estado distinto — es simplemente una NC que cubre el 100% de las líneas/cantidades. El saldo disponible de la factura origen (`total - SUM(nc.total)`) es un valor calculado en el momento de la consulta, nunca una columna persistida — mismo principio ya aplicado a `tipo_comprobante` (sección "Hallazgo de auditoría"): no se almacena lo que ya se puede derivar. `comprobante_electronico.estado` no se toca ni se reutiliza para esto — ese campo es exclusivamente el resultado de Hacienda sobre el documento (`ACEPTADO`/`RECHAZADO`/etc.), no un estado de negocio sobre saldo de crédito.
3. **Tope de monto en NC:** la suma de NCs emitidas contra una factura no puede exceder su total. **No aplica a ND** — Nota de Débito agrega monto (cargo olvidado, interés), no lo resta.
4. **Vínculo interno vía FK real**, no solo el campo de texto libre que exige el XML. `factura.factura_referencia_id` apunta a la factura origen; `FacturaInformacionReferencia.numero` (clave numérica para el XML) se deriva automáticamente de esa FK, nunca se le pide al usuario que lo tipee.
5. **Motivo obligatorio:** `codigo` (catálogo `CodigoReferencia`, ya completo y verificado 1:1 contra los XSD oficiales de NC/ND/Tiquete) siempre obligatorio. `codigo_referencia_otro` (texto libre) obligatorio únicamente si `codigo = '99'` (Otros) — verificado contra la documentación de `CodigoReferenciaOTRO` en los XSD oficiales ("Será obligatorio en caso de utilizar el código 99"); ya implementado desde `V11__fe_campos_completos.sql`, antes de Release 2. `razon` es un campo distinto (Razón de referencia), libre siempre, sin obligatoriedad condicional en el XSD.
6. **Mismo cliente siempre:** el cliente de la NC/ND es el mismo que el de la factura referenciada, sin excepción — no existe "corrección hacia otro cliente" vía nota de crédito.
7. **Solo Factura Electrónica (tipo `01`) puede ser el documento de referencia.** El catálogo `TipoDocumentoIR` (Anexo 1 de Hacienda) sí permite técnicamente que una NC/ND referencie otra NC/ND (`02`/`03` están en el catálogo), pero se restringe deliberadamente por alcance: la regla 3 (tope de monto) no tiene una respuesta obvia con cadenas de correcciones, y no hay caso de uso concreto todavía que lo justifique. El diseño de la FK (`factura.factura_referencia_id → factura(id)`, autorreferencial) ya soporta cadenas sin cambio de esquema — esto es una restricción de validación, reversible sin migración, no una limitación del modelo de datos.

---

## Hallazgo de auditoría — punto de partida real, no el asumido originalmente

Antes de escribir este plan se auditó el repo directamente. Contra lo asumido al abrir esta discusión, **el modelo de `informacionReferencia` ya está construido**: entidad `FacturaInformacionReferencia` (migración `V11__fe_campos_completos.sql`), generación del bloque XML en `XmlFacturaGeneratorServiceImpl.agregarInformacionReferencia()`, catálogos `CodigoReferencia` y `TipoDocumentoIR` completos, y `ClaveNumericaGenerator.generar()` ya parametrizado por `tipoComprobante`. El trabajo real no es modelar esto — es lo que describen las fases siguientes.

**Diferencias estructurales confirmadas contra los XSD oficiales de Hacienda v4.4** (`FacturaElectronica_V4.4.xsd`, `NotaCreditoElectronica_V4.4.xsd`, `NotaDebitoElectronica_V4.4.xsd`, `TiqueteElectronico_V4.4.xsd`, `atv.hacienda.go.cr`):

| Campo | Factura (01) | Tiquete (04) | NC/ND (02/03) |
|---|---|---|---|
| Elemento raíz / namespace | `FacturaElectronica` | `TiqueteElectronico` | `NotaCreditoElectronica` / `NotaDebitoElectronica` |
| `Receptor` | Obligatorio | Opcional | Obligatorio |
| `CodigoActividadEmisor` | Obligatorio | Obligatorio | Opcional |
| `CodigoActividadReceptor` | Presente | Ausente | Presente |
| `TipoTransaccion` (línea) | Presente | Ausente | Presente |
| `InformacionReferencia` | Opcional | Opcional | Obligatorio (mínimo 1) |
| `MontoExportacion` / `PartidaArancelaria` (línea) | No existe | No existe | Disponibles (opcionales) |
| Catálogo `Codigo` de `InformacionReferencia` | 16 valores | 12 valores | 12 valores — coincide 1:1 con `CodigoReferencia.java` actual |

**Fuera de alcance explícito de Release 2:** `MontoExportacion` y `PartidaArancelaria` (disponibles como campos opcionales de línea en NC/ND según el XSD) quedan fuera — sin caso de uso concreto en el mercado objetivo actual (no exportación), y agregarlos implica columnas nuevas en `linea_factura` más manejo en el generador de XML, expansión de alcance no planeada para esta release. Omitirlos es válido contra el XSD (`minOccurs="0"` en ambos campos) — no hay riesgo de rechazo de Hacienda por su ausencia.

---

## Fase A — Modelo de datos: referencia interna y reglas de motor

**Trabajo:**
- Migración: `factura.factura_referencia_id UUID REFERENCES factura(id)`, nullable, poblado únicamente cuando `tipo_comprobante IN ('02','03')`.
- Extender `fn_validar_mismo_tenant` (sección 4.16) para cubrir esta nueva FK — mismo patrón ya aplicado a `cliente_id`/`producto_id`/`exoneracion_id`.
- Trigger nuevo (`BEFORE INSERT ON comprobante_electronico`, `WHEN NEW.tipo_comprobante = '03'`): la suma de NC previas emitidas contra la misma `factura_referencia_id` más la NC que se está insertando no puede exceder el `total` de la factura origen. Engancha en `comprobante_electronico` y no en `factura` porque `tipo_comprobante` vive únicamente ahí (`comprobante_electronico.factura_id UNIQUE REFERENCES factura(id)`, relación 1:1) — la fila `factura` ya existe en la misma transacción al momento de este insert, así que el trigger accede a `factura_referencia_id` y `total` vía join sin necesidad de denormalizar `tipo_comprobante` en `factura`. No aplica a ND — el `WHEN` de la cláusula lo filtra explícitamente.
- Trigger separado (`BEFORE INSERT OR UPDATE ON factura`, cuando `factura_referencia_id IS NOT NULL`): valida que el documento referenciado sea Factura Electrónica (`comprobante_electronico.tipo_comprobante = '01'` para ese `factura_id`), aplicando la regla de negocio 7. Invariante distinta a la del tope de monto — vive en su propio trigger, no mezclada con el anterior.
- ~~`CHECK` "todo o nada" sobre `codigo`/`razon`~~ — no hace falta: el `CHECK` real (regla 5, sobre `codigo`/`codigo_referencia_otro`) ya existe desde `V11__fe_campos_completos.sql`. Ver la corrección de la regla 5 más arriba.

**Criterio de salida:** extensión de `AislamientoMultiTenantTest` (o test dedicado, mismo criterio de rigor de Fase 1 de Release 1) que confirma: (a) una NC no puede referenciar una factura de otro tenant, (b) el trigger de tope de monto bloquea una NC que excede el saldo disponible, (c) una segunda NC parcial sobre la misma factura sí se permite mientras no exceda el total, (d) intentar setear `factura_referencia_id` apuntando a una NC/ND (no a una Factura tipo `01`) es rechazado (regla 7), (e) el `CHECK` ya existente de `codigo_referencia_otro` obligatorio si `codigo='99'` (regla 5) queda con cobertura de test explícita, cosa que no tenía antes de Release 2. Corre en CI desde este punto, no como verificación manual — mismo criterio no negociable que ya aplicaste en Release 1.

---

## Fase B — Motor de emisión parametrizado + reglas de servicio de NC/ND

**Trabajo:**
- Extraer `ComprobanteEmisionService` de `FacturaService`: asignación de consecutivo (`contador_consecutivo`, ya separado por `tipo_comprobante`), `ClaveNumericaGenerator` (ya genérico), firma XML-DSig, envío/consulta a Hacienda. Parametrizado por `tipoComprobante`, usado por los cuatro tipos de documento.
- Reducir `FacturaService` a lo específico de tipo `01` — dejar de asumir `TIPO_COMPROBANTE_FACTURA_ELECTRONICA = "01"` como constante fija en los 4 puntos donde hoy se usa.
- `XmlFacturaGeneratorServiceImpl`: introducir un perfil de configuración por tipo de comprobante (root tag, namespace, obligatoriedad de `Receptor`/`CodigoActividadEmisor`/`CodigoActividadReceptor`/`TipoTransaccion`/`InformacionReferencia`, según la tabla de la sección anterior) en lugar de condicionales dispersos por el método. Un solo lugar de verdad para la variación por tipo, mismo principio que ya rige el resto del backend.
- `NotaCreditoDebitoService` nuevo: valida la precondición de estado `ACEPTADO` de la factura origen (regla de negocio 1), valida mismo cliente (regla 6), delega el resto a `ComprobanteEmisionService`. El armado de líneas bifurca por tipo, no es lógica compartida: **NC** gestiona selección de líneas/cantidades a acreditar desde la factura origen (regla 2, `CrearNotaCreditoRequest`); **ND** arma líneas nuevas desde el catálogo de productos, mismo shape que `CrearFacturaRequest` (`CrearNotaDebitoRequest`) — un cargo olvidado o un interés no es una línea que ya existía en la factura original, así que ND no hereda el modelo de selección de NC, hereda el de creación libre de Factura. El vínculo con la factura origen en ND es exclusivamente para `InformacionReferencia` y para heredar el cliente, no para prellenar líneas.
- Endpoints separados, no un endpoint genérico parametrizado: `POST /notas-credito`, `POST /notas-debito`. Motivo ya discutido: el shape del request difiere materialmente (referencia obligatoria + selección de líneas existentes vs. creación desde cero), y un endpoint único ensuciaría el contrato OpenAPI que el frontend consume vía `openapi-typescript`.

**Criterio de salida:** una Nota de Crédito parcial y una Nota de Débito completas pasan por `GENERADO → FIRMADO → ENVIADO → ACEPTADO` contra el ambiente `SANDBOX` de Hacienda, referenciando una factura de prueba ya aceptada — de punta a punta, sin intervención manual en el medio.

---

## Fase C — Tiquete Electrónico

**Trabajo:**
- `TiqueteService`: sin referencia a factura previa (no usa `factura_referencia_id`), `Receptor` opcional, sin `CodigoActividadReceptor` ni `TipoTransaccion` en el XML generado (confirmado ausentes en el XSD de Tiquete). `CodigoActividadEmisor` permanece obligatorio, igual que Factura.
- Endpoint: `POST /tiquetes`.
- Reutiliza el mismo perfil de configuración por tipo introducido en la Fase B — no un generador de XML aparte.

**Criterio de salida:** un Tiquete Electrónico sin receptor identificado pasa por el ciclo completo hasta `ACEPTADO` en `SANDBOX`.

---

## Fase D — Frontend: extensión del módulo de facturas, no un módulo nuevo

**Trabajo:**
- `(app)/facturas/[id]/nota-credito/nueva/` — el punto de entrada es el detalle de la factura ya aceptada (botón visible solo si `estado = 'ACEPTADO'` y el permiso correspondiente). El wizard llega con las líneas de la factura origen precargadas; el usuario ajusta cantidades/selección, no arranca en blanco.
- `(app)/facturas/[id]/nota-debito/nueva/` — mismo punto de entrada, pero el wizard **no** precarga líneas: es prácticamente el mismo wizard de "nueva factura" (selección libre de catálogo), con el cliente ya fijado (heredado de la factura origen, no editable) y el motivo de referencia como campo adicional. Un cargo que se está cobrando ahora no existía en la factura original — no hay nada que precargar.
- `(app)/facturas/tiquetes/nueva/` — sí es entrada de nivel superior, porque Tiquete no referencia nada: es una venta nueva, no una corrección.
- `(app)/facturas/page.tsx` (listado existente): agregar filtro por tipo de comprobante, reutilizando el mismo envelope `{ items, nextCursor }` ya establecido.
- Regenerar tipos vía `openapi-typescript` contra el spec actualizado una vez que Fase B/C exponga los nuevos endpoints — no tipar a mano.
- No se agrega una quinta entrada al sidebar (`frontend-disenno.md`, sección 5 fija Facturas/Clientes/Productos/Empresa) — todo vive dentro del módulo de Facturas ya existente.

**Criterio de salida:** flujo completo desde la interfaz — factura aceptada → botón "Emitir nota de crédito" → wizard precargado → confirmación → visible en el listado con su propio badge de estado — sin salir del módulo de Facturas.

---

## Fase E — Cierre de Release 2

Mismo criterio formal que la Fase 10 de Release 1 — evidencia guardada, no verificación manual de última hora:

- Prueba de extremo a extremo en `SANDBOX` para los tres tipos nuevos (NC parcial, NC total, ND, Tiquete), corriendo en CI junto con `AislamientoMultiTenantTest` extendido de la Fase A — mismo gate, no uno paralelo.
- Actualizar `endpoints-faltantes-frontend.md` con los nuevos endpoints (`/notas-credito`, `/notas-debito`, `/tiquetes`).
- Actualizar `frontend-paginas.md` con el inventario de pantallas nuevas de la Fase D.
- Actualizar `arquitectura-facturacion-electronica-cr.md` sección 8.2: mover Release 2 de "diferido" a "cerrado", con referencia a los commits correspondientes.

---

## Resumen de dependencias entre fases

```
Fase A (referencia interna + triggers)
   └─→ Fase B (motor parametrizado + servicio NC/ND) ──────┐
          └─→ Fase C (Tiquete, reutiliza perfil de Fase B)  │  Prueba de extremo a extremo
                 └─→ Fase D (frontend, depende de B y C)    │  en CI desde la Fase A
                        └─→ Fase E (cierre)                  │  en adelante
```
