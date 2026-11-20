-- A request without annual deduction still records every approved leave day.
ALTER TABLE tna.approval_request DROP CONSTRAINT approval_request_leave_bucket_required;

CREATE FUNCTION tna.check_leave_deduction_bucket() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $$
DECLARE
    without_deduction boolean;
BEGIN
    SELECT EXISTS (
        SELECT 1 FROM tna.attendance_type_version v
        JOIN tna.attendance_type t ON t.tenant_id=v.tenant_id AND t.id=v.type_id
        WHERE v.tenant_id=NEW.tenant_id AND v.id=NEW.attendance_type_version_id AND t.system_kind IS NULL
          AND v.recognition_rule='COMPANY_LEAVE' AND v.deduction_rule='NONE'
    ) INTO without_deduction;
    IF (NEW.leave_bucket_id IS NULL) <> without_deduction THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='leave bucket does not match the pinned deduction policy';
    END IF;
    RETURN NEW;
END
$$;

REVOKE ALL ON FUNCTION tna.check_leave_deduction_bucket() FROM PUBLIC;
CREATE TRIGGER approval_request_leave_bucket_required
BEFORE INSERT OR UPDATE OF kind,leave_bucket_id,attendance_type_version_id ON tna.approval_request
FOR EACH ROW WHEN (NEW.kind='LEAVE_DAY') EXECUTE FUNCTION tna.check_leave_deduction_bucket();
