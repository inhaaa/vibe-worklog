-- 휴일 관리: 연도별 휴일 표와 근태 달력 날짜별 반영 (설계 2026-11-24-holiday-management-design.md §2)
CREATE TABLE tna.holiday (
    tenant_id uuid NOT NULL REFERENCES platform.tenant_registry (id),
    id uuid NOT NULL,
    holiday_date date NOT NULL,
    name varchar(120) NOT NULL,
    category varchar(20) NOT NULL,
    basis varchar(500),
    created_by uuid NOT NULL,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by uuid NOT NULL,
    updated_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,id),
    UNIQUE (tenant_id,holiday_date),
    FOREIGN KEY (tenant_id,created_by) REFERENCES tna.tenant_account (tenant_id,id),
    FOREIGN KEY (tenant_id,updated_by) REFERENCES tna.tenant_account (tenant_id,id),
    CONSTRAINT holiday_name_valid CHECK (btrim(name)<>''),
    CONSTRAINT holiday_category_valid CHECK (category IN ('PUBLIC','COMPANY','NON_WORKDAY')),
    CONSTRAINT holiday_basis_valid CHECK (basis IS NULL OR btrim(basis)<>'')
);

CREATE TABLE tna.holiday_year (
    tenant_id uuid NOT NULL REFERENCES platform.tenant_registry (id),
    year integer NOT NULL,
    version bigint NOT NULL,
    applied_at timestamptz(3),
    applied_by uuid,
    PRIMARY KEY (tenant_id,year),
    FOREIGN KEY (tenant_id,applied_by) REFERENCES tna.tenant_account (tenant_id,id),
    CONSTRAINT holiday_year_range CHECK (year BETWEEN 2000 AND 2100),
    CONSTRAINT holiday_year_version_positive CHECK (version>0)
);

ALTER TABLE tna.holiday ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.holiday FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.holiday
    TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE tna.holiday_year ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.holiday_year FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.holiday_year
    TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
REVOKE ALL ON tna.holiday,tna.holiday_year FROM PUBLIC;
GRANT SELECT,INSERT,DELETE ON tna.holiday TO tna_app;
GRANT UPDATE (name,category,basis,updated_by,updated_at) ON tna.holiday TO tna_app;
GRANT SELECT,INSERT ON tna.holiday_year TO tna_app;
GRANT UPDATE (version,applied_at,applied_by) ON tna.holiday_year TO tna_app;

-- 날짜 하나만 고치는 반영분(DAY)은 기간(PERIOD) 위에 쌓인다. 기간끼리는 계속 겹칠 수 없다. 기존 행은 모두 PERIOD다.
ALTER TABLE tna.calendar_version
    ADD COLUMN scope varchar(10) NOT NULL DEFAULT 'PERIOD',
    ADD CONSTRAINT calendar_scope_valid CHECK (scope IN ('PERIOD','DAY')),
    ADD CONSTRAINT calendar_day_scope_one_date CHECK (scope='PERIOD' OR valid_to=valid_from+1);
ALTER TABLE tna.calendar_version DROP CONSTRAINT calendar_period_not_overlapping;
ALTER TABLE tna.calendar_version ADD CONSTRAINT calendar_period_not_overlapping EXCLUDE USING gist (
    tenant_id tna.gist_uuid_ops WITH =,
    daterange(valid_from,valid_to,'[)') WITH &&
) WHERE (scope='PERIOD');
CREATE INDEX calendar_day_by_date ON tna.calendar_day (tenant_id,work_date);

-- 한 날짜에는 그 날짜를 포함하는 버전 가운데 revision이 가장 큰 버전을 쓴다. V41 전에는 겹침이 없어 기존 결과와 같다.
CREATE VIEW tna.calendar_effective_day WITH (security_invoker=true) AS
SELECT DISTINCT ON (d.tenant_id,d.work_date)
       d.tenant_id,d.work_date,d.version_id,d.kind,d.name,d.basis
FROM tna.calendar_day d
JOIN tna.calendar_version v ON v.tenant_id=d.tenant_id AND v.id=d.version_id
    AND v.valid_from<=d.work_date AND v.valid_to>d.work_date
ORDER BY d.tenant_id,d.work_date,v.revision DESC;
REVOKE ALL ON tna.calendar_effective_day FROM PUBLIC;
GRANT SELECT ON tna.calendar_effective_day TO tna_app;

-- 기존 데이터 이전(§2.3): 휴일 → COMPANY, 평일 비근로일 → NON_WORKDAY. 주말 비근로일은 근로일과 같게 보므로 옮기지 않는다.
CREATE POLICY holiday_migration_registry_scan ON platform.tenant_registry FOR SELECT TO tna_migration USING (true);
DO $body$
DECLARE _tenant uuid;
BEGIN
    FOR _tenant IN SELECT id FROM platform.tenant_registry LOOP
        PERFORM pg_catalog.set_config('app.tenant_id',_tenant::text,true);
        INSERT INTO tna.holiday(tenant_id,id,holiday_date,name,category,basis,created_by,updated_by)
        SELECT e.tenant_id,pg_catalog.gen_random_uuid(),e.work_date,e.name,
               CASE e.kind WHEN 'HOLIDAY' THEN 'COMPANY' ELSE 'NON_WORKDAY' END,e.basis,v.created_by,v.created_by
        FROM tna.calendar_effective_day e
        JOIN tna.calendar_version v ON v.tenant_id=e.tenant_id AND v.id=e.version_id
        WHERE e.tenant_id=_tenant AND extract(year FROM e.work_date) BETWEEN 2000 AND 2100
          AND (e.kind='HOLIDAY' OR e.kind='NON_WORKDAY' AND extract(isodow FROM e.work_date)<6);
        INSERT INTO tna.holiday_year(tenant_id,year,version,applied_at)
        SELECT DISTINCT _tenant,extract(year FROM work_date)::integer,1,CURRENT_TIMESTAMP
        FROM tna.calendar_effective_day
        WHERE tenant_id=_tenant AND extract(year FROM work_date) BETWEEN 2000 AND 2100;
    END LOOP;
    PERFORM pg_catalog.set_config('app.tenant_id','',true);
END $body$;
DROP POLICY holiday_migration_registry_scan ON platform.tenant_registry;
