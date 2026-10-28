ALTER TABLE tna.role_grant DROP CONSTRAINT role_grant_supported_role;
ALTER TABLE tna.role_grant ADD CONSTRAINT role_grant_supported_role
    CHECK (role IN ('EMPLOYEE', 'APPROVER', 'ATTENDANCE_ADMIN', 'COMPANY_REPRESENTATIVE'));
