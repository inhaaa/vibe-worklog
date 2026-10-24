CREATE INDEX org_audit_event_lookup_order
    ON tna.org_audit_event (tenant_id, occurred_at DESC, id DESC);
