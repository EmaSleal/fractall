/**
 * Almacenamiento de documentos de comprobantes electrónicos en Oracle Object Storage (Fase 8,
 * sección 6.4 de {@code arquitectura-facturacion-electronica-cr.md}) -- mismo principio de
 * aislamiento de un proveedor externo detrás de una interfaz propia ya aplicado a
 * {@code cr.ac.fractall.secretos} para Vault: la lógica de negocio de {@code facturacion} nunca
 * invoca el SDK nativo de OCI directamente, solo {@link cr.ac.fractall.almacenamiento.ObjectStorageService}.
 */
package cr.ac.fractall.almacenamiento;
