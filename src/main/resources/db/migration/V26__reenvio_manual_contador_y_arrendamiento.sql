-- T-004 (ADR-055 y ADR-056): reenvío manual de comprobantes (POST /facturas/{id}/reenviar).
--
-- reenvios_manuales: traza de cuántas veces se reclamó un reenvío manual del comprobante. Se
-- incrementa en el mismo UPDATE condicional del reclamo. intentos_envio NO cumple ese papel: es el
-- presupuesto del ciclo automático (ADR-049, mismo contador que el tope MAX_INTENTOS del job de
-- polling) y el reclamo lo reinicia en 0.
--
-- reenvio_reclamado_hasta: arrendamiento del reclamo (ADR-056). El reclamo lo fija en
-- now() + duración con el reloj de la base; la escritura final del reenvío lo deja en NULL. Si la
-- aplicación se cae a mitad del reenvío, el arrendamiento vence solo y el comprobante vuelve a ser
-- reenviable, sin estado transitorio nuevo (no hay REENVIANDO ni se reusa ENVIADO).
ALTER TABLE comprobante_electronico
    ADD COLUMN reenvios_manuales INT NOT NULL DEFAULT 0,
    ADD COLUMN reenvio_reclamado_hasta TIMESTAMP NULL;
