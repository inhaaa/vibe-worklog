CREATE TABLE tna.leave_bucket_attribution (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    bucket_id uuid NOT NULL,
    revision bigint NOT NULL,
    attendance_type_id uuid NOT NULL,
    classification_status varchar(20) NOT NULL,
    entitlement_year integer,
    basis varchar(500) NOT NULL,
    reason varchar(500) NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,id),
    UNIQUE (tenant_id,bucket_id,revision),
    FOREIGN KEY (tenant_id,bucket_id) REFERENCES tna.leave_bucket(tenant_id,id),
    FOREIGN KEY (tenant_id,attendance_type_id) REFERENCES tna.attendance_type(tenant_id,id),
    FOREIGN KEY (tenant_id,created_by) REFERENCES tna.tenant_account(tenant_id,id),
    CHECK (revision>=1),
    CHECK (classification_status IN ('CONFIRMED','UNCLASSIFIED')),
    CHECK ((classification_status='CONFIRMED' AND entitlement_year IS NOT NULL AND entitlement_year BETWEEN 1900 AND 9998)
        OR (classification_status='UNCLASSIFIED' AND entitlement_year IS NULL)),
    CHECK (btrim(basis)<>'' AND btrim(reason)<>'')
);

ALTER TABLE tna.leave_bucket_attribution ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.leave_bucket_attribution FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.leave_bucket_attribution TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
REVOKE ALL ON tna.leave_bucket_attribution FROM PUBLIC;
GRANT SELECT ON tna.leave_bucket_attribution TO tna_app;
GRANT INSERT (tenant_id,id,bucket_id,revision,attendance_type_id,classification_status,entitlement_year,basis,reason,created_by)
    ON tna.leave_bucket_attribution TO tna_app;
