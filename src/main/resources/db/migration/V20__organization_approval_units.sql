ALTER TABLE tna.organization
    ADD COLUMN approval_unit varchar(12),
    ADD CONSTRAINT organization_approval_unit_valid
        CHECK (approval_unit IN ('TEAM','OFFICE','PASS_THROUGH'));
GRANT UPDATE (approval_unit) ON tna.organization TO tna_app;

ALTER TABLE tna.approval_route_version
    DROP CONSTRAINT approval_route_version_approver_kind_check,
    ADD COLUMN terminal_unit varchar(12),
    ADD CONSTRAINT approval_route_kind_valid
        CHECK (approver_kind IN ('DIRECT_EMPLOYEE','ORGANIZATION_HEAD','ORGANIZATION_CHAIN')),
    ADD CONSTRAINT approval_route_terminal_unit_valid
        CHECK ((approver_kind = 'ORGANIZATION_CHAIN' AND terminal_unit IS NOT NULL AND terminal_unit IN ('TEAM','OFFICE'))
            OR (approver_kind <> 'ORGANIZATION_CHAIN' AND terminal_unit IS NULL));
