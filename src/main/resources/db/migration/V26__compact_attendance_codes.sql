-- Change only the display code of the two built-in types. Requests and ledgers keep
-- their original type and policy version IDs, and customer-created codes keep their spelling.
CREATE FUNCTION tna.compact_attendance_codes(company uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE
    previous_tenant text := current_setting('app.tenant_id', true);
    policy record;
    candidate text;
    suffix integer := 2;
BEGIN
    PERFORM set_config('app.tenant_id', company::text, true);
    FOR policy IN
        SELECT id, system_kind FROM tna.attendance_type
        WHERE tenant_id=company AND system_kind IN ('ANNUAL_LEAVE','ATTENDANCE_CORRECTION')
          AND code=system_kind
        ORDER BY system_kind
    LOOP
        candidate := CASE policy.system_kind WHEN 'ANNUAL_LEAVE' THEN 'A01' ELSE 'A02' END;
        WHILE EXISTS (SELECT 1 FROM tna.attendance_type WHERE tenant_id=company AND upper(code)=candidate) LOOP
            suffix := suffix+1;
            candidate := 'A' || CASE WHEN suffix<10 THEN '0' ELSE '' END || suffix::text;
        END LOOP;
        UPDATE tna.attendance_type SET code=candidate WHERE tenant_id=company AND id=policy.id;
    END LOOP;
    PERFORM set_config('app.tenant_id', coalesce(previous_tenant,''), true);
END $$;
REVOKE ALL ON FUNCTION tna.compact_attendance_codes(uuid) FROM PUBLIC;

CREATE OR REPLACE FUNCTION tna.provision_tenant_codes() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
BEGIN
    PERFORM tna.seed_tenant_codes(NEW.id);
    PERFORM tna.compact_attendance_codes(NEW.id);
    RETURN NEW;
END $$;

CREATE POLICY compact_codes_registry_scan ON platform.tenant_registry FOR SELECT TO tna_migration USING (true);
DO $$ DECLARE company uuid;
BEGIN
    FOR company IN SELECT id FROM platform.tenant_registry LOOP
        PERFORM tna.compact_attendance_codes(company);
    END LOOP;
END $$;
DROP POLICY compact_codes_registry_scan ON platform.tenant_registry;
