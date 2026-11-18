-- A membership keeps the original assignment primary key, including the first appointment reference.
CREATE TABLE tna.work_group (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    PRIMARY KEY (tenant_id,id),
    FOREIGN KEY (tenant_id,id) REFERENCES tna.schedule_template(tenant_id,id)
);

CREATE TABLE tna.work_group_day (
    tenant_id uuid NOT NULL,
    schedule_version_id uuid NOT NULL,
    weekday integer NOT NULL,
    code_version_id uuid NOT NULL,
    PRIMARY KEY (tenant_id,schedule_version_id,weekday),
    FOREIGN KEY (tenant_id,schedule_version_id,weekday) REFERENCES tna.schedule_day(tenant_id,version_id,weekday),
    FOREIGN KEY (tenant_id,code_version_id) REFERENCES tna.work_time_code_version(tenant_id,id)
);
CREATE INDEX work_group_day_code ON tna.work_group_day(tenant_id,code_version_id,schedule_version_id);
CREATE UNIQUE INDEX schedule_version_group_reference ON tna.schedule_version(tenant_id,template_id,id);
CREATE INDEX schedule_version_effective_date ON tna.schedule_version(tenant_id,template_id,valid_from,revision DESC);

ALTER TABLE tna.schedule_assignment
    ADD COLUMN work_group_id uuid,
    ADD FOREIGN KEY (tenant_id,work_group_id) REFERENCES tna.work_group(tenant_id,id),
    ADD FOREIGN KEY (tenant_id,work_group_id,schedule_version_id) REFERENCES tna.schedule_version(tenant_id,template_id,id);
CREATE INDEX schedule_assignment_group_effective ON tna.schedule_assignment(tenant_id,work_group_id,valid_from)
    WHERE work_group_id IS NOT NULL;

ALTER TABLE tna.work_group ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.work_group FORCE ROW LEVEL SECURITY;
ALTER TABLE tna.work_group_day ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.work_group_day FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.work_group TO tna_app,tna_migration
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY tenant_isolation ON tna.work_group_day TO tna_app,tna_migration
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
REVOKE ALL ON tna.work_group,tna.work_group_day FROM PUBLIC;
GRANT SELECT, INSERT ON tna.work_group,tna.work_group_day TO tna_app;
GRANT UPDATE (work_group_id,schedule_version_id,reason) ON tna.schedule_assignment TO tna_app;

-- Retain superseded reservations as immutable versions. Only the latest revision for one date applies.
CREATE VIEW tna.work_group_revision_period WITH (security_invoker=true) AS
SELECT tenant_id, group_id, schedule_version_id, valid_from,
       lead(valid_from) OVER (PARTITION BY tenant_id,group_id ORDER BY valid_from) AS valid_to
FROM (
    SELECT DISTINCT ON (v.tenant_id,v.template_id,v.valid_from)
           v.tenant_id,v.template_id AS group_id,v.id AS schedule_version_id,v.valid_from
    FROM tna.schedule_version v
    JOIN tna.work_group g ON g.tenant_id=v.tenant_id AND g.id=v.template_id
    ORDER BY v.tenant_id,v.template_id,v.valid_from,v.revision DESC
) active_revision;

CREATE VIEW tna.effective_schedule_assignment WITH (security_invoker=true) AS
SELECT a.tenant_id,a.id,a.employee_id,a.schedule_version_id,a.valid_from,a.valid_to,
       a.reason,a.version,a.created_at,a.created_by,a.updated_at,a.updated_by,a.work_group_id
FROM tna.schedule_assignment a WHERE a.work_group_id IS NULL
UNION ALL
SELECT a.tenant_id,a.id,a.employee_id,r.schedule_version_id,
       greatest(a.valid_from,r.valid_from),least(a.valid_to,r.valid_to),
       a.reason,a.version,a.created_at,a.created_by,a.updated_at,a.updated_by,a.work_group_id
FROM tna.schedule_assignment a
JOIN tna.work_group_revision_period r ON r.tenant_id=a.tenant_id AND r.group_id=a.work_group_id
    AND (a.valid_to IS NULL OR r.valid_from<a.valid_to)
    AND (r.valid_to IS NULL OR a.valid_from<r.valid_to);
REVOKE ALL ON tna.work_group_revision_period,tna.effective_schedule_assignment FROM PUBLIC;
GRANT SELECT ON tna.work_group_revision_period,tna.effective_schedule_assignment TO tna_app;
