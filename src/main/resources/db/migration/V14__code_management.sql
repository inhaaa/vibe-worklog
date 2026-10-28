CREATE TABLE tna.common_code_group (
 tenant_id uuid NOT NULL REFERENCES platform.tenant_registry(id),id uuid NOT NULL,code varchar(40) NOT NULL,
 name varchar(120) NOT NULL,description varchar(500) NOT NULL DEFAULT '',sort_order integer NOT NULL DEFAULT 0,
 active boolean NOT NULL DEFAULT true,system_owned boolean NOT NULL DEFAULT false,version bigint NOT NULL DEFAULT 0,
 created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,created_by uuid,
 updated_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_by uuid,
 PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,code),
 FOREIGN KEY(tenant_id,created_by) REFERENCES tna.tenant_account(tenant_id,id),
 FOREIGN KEY(tenant_id,updated_by) REFERENCES tna.tenant_account(tenant_id,id),
 CHECK(code=btrim(code) AND code<>'' AND btrim(name)<>''),CHECK(sort_order BETWEEN 0 AND 1000000 AND version>=0),
 CHECK(system_owned OR (created_by IS NOT NULL AND updated_by IS NOT NULL))
);
CREATE TABLE tna.common_code (
 tenant_id uuid NOT NULL,id uuid NOT NULL,group_id uuid NOT NULL,code varchar(40) NOT NULL,
 name varchar(120) NOT NULL,description varchar(500) NOT NULL DEFAULT '',sort_order integer NOT NULL DEFAULT 0,
 active boolean NOT NULL DEFAULT true,system_owned boolean NOT NULL DEFAULT false,version bigint NOT NULL DEFAULT 0,
 created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,created_by uuid,
 updated_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_by uuid,
 PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,group_id,code),
 FOREIGN KEY(tenant_id,group_id) REFERENCES tna.common_code_group(tenant_id,id),
 FOREIGN KEY(tenant_id,created_by) REFERENCES tna.tenant_account(tenant_id,id),
 FOREIGN KEY(tenant_id,updated_by) REFERENCES tna.tenant_account(tenant_id,id),
 CHECK(code=btrim(code) AND code<>'' AND btrim(name)<>''),CHECK(sort_order BETWEEN 0 AND 1000000 AND version>=0),
 CHECK(system_owned OR (created_by IS NOT NULL AND updated_by IS NOT NULL))
);
CREATE TABLE tna.attendance_type (
 tenant_id uuid NOT NULL REFERENCES platform.tenant_registry(id),id uuid NOT NULL,code varchar(40) NOT NULL,
 system_kind varchar(30),active boolean NOT NULL DEFAULT true,version bigint NOT NULL DEFAULT 0,
 created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,created_by uuid,
 updated_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_by uuid,
 PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,code),UNIQUE(tenant_id,system_kind),
 FOREIGN KEY(tenant_id,created_by) REFERENCES tna.tenant_account(tenant_id,id),
 FOREIGN KEY(tenant_id,updated_by) REFERENCES tna.tenant_account(tenant_id,id),
 CHECK(code=btrim(code) AND code<>'' AND version>=0),
 CHECK(system_kind IS NULL OR system_kind IN ('ANNUAL_LEAVE','ATTENDANCE_CORRECTION')),
 CHECK(system_kind IS NOT NULL OR (created_by IS NOT NULL AND updated_by IS NOT NULL))
);
CREATE TABLE tna.attendance_type_version (
 tenant_id uuid NOT NULL,id uuid NOT NULL,type_id uuid NOT NULL,revision bigint NOT NULL,
 effective_from date NOT NULL,enabled boolean NOT NULL,name varchar(120) NOT NULL,
 unit varchar(10) NOT NULL,pay_kind varchar(20) NOT NULL,deduction_rule varchar(20) NOT NULL,
 recognition_rule varchar(20) NOT NULL,basis varchar(500) NOT NULL,reason varchar(500) NOT NULL,
 system_seed boolean NOT NULL DEFAULT false,created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,created_by uuid,
 PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,type_id,revision),
 FOREIGN KEY(tenant_id,type_id) REFERENCES tna.attendance_type(tenant_id,id),
 FOREIGN KEY(tenant_id,created_by) REFERENCES tna.tenant_account(tenant_id,id),
 CHECK(revision>=1 AND effective_from BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'),
 CHECK(btrim(name)<>'' AND btrim(basis)<>'' AND btrim(reason)<>''),
 CHECK(unit IN ('DAY','HOUR','NONE')),CHECK(pay_kind IN ('UNSPECIFIED','PAID','UNPAID')),
 CHECK(deduction_rule IN ('LEAVE_DAY','NONE')),CHECK(recognition_rule='NOT_SUPPORTED'),
 CHECK((created_by IS NULL)=system_seed),
 CHECK(NOT system_seed OR (revision=1 AND effective_from=DATE '0001-01-01'))
);

-- Fixed metadata provisioning only; policy selection and workflow rules stay in Java.
CREATE FUNCTION tna.seed_tenant_codes(_tenant uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $body$
DECLARE _group uuid=pg_catalog.gen_random_uuid();_leave uuid=pg_catalog.gen_random_uuid();_correction uuid=pg_catalog.gen_random_uuid();_previous text;
BEGIN
 _previous:=pg_catalog.current_setting('app.tenant_id',true);
 PERFORM pg_catalog.set_config('app.tenant_id',_tenant::text,true);
 INSERT INTO tna.common_code_group(tenant_id,id,code,name,description,system_owned)
 VALUES(_tenant,_group,'SYSTEM_PROCESS','신청 처리 유형 (시스템)','서버 고정 프로세스의 읽기 전용 참고 분류',true);
 INSERT INTO tna.common_code(tenant_id,id,group_id,code,name,sort_order,system_owned) VALUES
 (_tenant,pg_catalog.gen_random_uuid(),_group,'LEAVE_DAY','종일 연차',0,true),
 (_tenant,pg_catalog.gen_random_uuid(),_group,'ATTENDANCE_CORRECTION','출퇴근·휴게 정정',1,true),
 (_tenant,pg_catalog.gen_random_uuid(),_group,'CANCEL_LEAVE','연차 취소',2,true);
 INSERT INTO tna.attendance_type(tenant_id,id,code,system_kind) VALUES
 (_tenant,_leave,'ANNUAL_LEAVE','ANNUAL_LEAVE'),(_tenant,_correction,'ATTENDANCE_CORRECTION','ATTENDANCE_CORRECTION');
 INSERT INTO tna.attendance_type_version(tenant_id,id,type_id,revision,effective_from,enabled,name,unit,pay_kind,deduction_rule,recognition_rule,basis,reason,system_seed)
 VALUES(_tenant,pg_catalog.gen_random_uuid(),_leave,1,DATE '0001-01-01',true,'연차','DAY','UNSPECIFIED','LEAVE_DAY','NOT_SUPPORTED','기존 종일 연차·정수 DAY·승인 시 원장 차감 계약','기존 고정 유형 호환 기초',true),
 (_tenant,pg_catalog.gen_random_uuid(),_correction,1,DATE '0001-01-01',true,'출퇴근·휴게 정정','NONE','UNSPECIFIED','NONE','NOT_SUPPORTED','기존 출퇴근·휴게 정정 프로세스 계약','기존 고정 유형 호환 기초',true);
 PERFORM pg_catalog.set_config('app.tenant_id',coalesce(_previous,''),true);
END $body$;
REVOKE ALL ON FUNCTION tna.seed_tenant_codes(uuid) FROM PUBLIC;
CREATE FUNCTION tna.provision_tenant_codes() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $body$
BEGIN PERFORM tna.seed_tenant_codes(NEW.id);RETURN NEW;END $body$;
REVOKE ALL ON FUNCTION tna.provision_tenant_codes() FROM PUBLIC;
CREATE TRIGGER tenant_codes_provision AFTER INSERT ON platform.tenant_registry FOR EACH ROW EXECUTE FUNCTION tna.provision_tenant_codes();

ALTER TABLE tna.approval_request ADD COLUMN attendance_type_version_id uuid;
CREATE POLICY code_migration_registry_scan ON platform.tenant_registry FOR SELECT TO tna_migration USING(true);
DO $body$
DECLARE _tenant uuid;
BEGIN
 FOR _tenant IN SELECT id FROM platform.tenant_registry LOOP
  PERFORM tna.seed_tenant_codes(_tenant);
  PERFORM pg_catalog.set_config('app.tenant_id',_tenant::text,true);
  UPDATE tna.approval_request r SET attendance_type_version_id=v.id
   FROM tna.attendance_type t JOIN tna.attendance_type_version v ON v.tenant_id=t.tenant_id AND v.type_id=t.id AND v.revision=1
   WHERE r.tenant_id=_tenant AND t.tenant_id=r.tenant_id
    AND t.system_kind=CASE WHEN r.kind='ATTENDANCE_CORRECTION' THEN 'ATTENDANCE_CORRECTION' ELSE 'ANNUAL_LEAVE' END;
 END LOOP;
 PERFORM pg_catalog.set_config('app.tenant_id','',true);
END $body$;
DROP POLICY code_migration_registry_scan ON platform.tenant_registry;
ALTER TABLE tna.approval_request ALTER COLUMN attendance_type_version_id SET NOT NULL,
 ADD CONSTRAINT approval_request_attendance_type_version FOREIGN KEY(tenant_id,attendance_type_version_id) REFERENCES tna.attendance_type_version(tenant_id,id);

ALTER TABLE tna.common_code_group ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.common_code_group FORCE ROW LEVEL SECURITY;
ALTER TABLE tna.common_code ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.common_code FORCE ROW LEVEL SECURITY;
ALTER TABLE tna.attendance_type ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.attendance_type FORCE ROW LEVEL SECURITY;
ALTER TABLE tna.attendance_type_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.attendance_type_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.common_code_group TO tna_migration,tna_app USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY tenant_isolation ON tna.common_code TO tna_migration,tna_app USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY tenant_isolation ON tna.attendance_type TO tna_migration,tna_app USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY tenant_isolation ON tna.attendance_type_version TO tna_migration,tna_app USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
REVOKE ALL ON tna.common_code_group,tna.common_code,tna.attendance_type,tna.attendance_type_version FROM PUBLIC;
GRANT SELECT ON tna.common_code_group,tna.common_code,tna.attendance_type,tna.attendance_type_version TO tna_app;
GRANT INSERT(tenant_id,id,code,name,description,sort_order,active,created_by,updated_by) ON tna.common_code_group TO tna_app;
GRANT INSERT(tenant_id,id,group_id,code,name,description,sort_order,active,created_by,updated_by) ON tna.common_code TO tna_app;
GRANT UPDATE(name,description,sort_order,active,version,updated_at,updated_by) ON tna.common_code_group,tna.common_code TO tna_app;
GRANT INSERT(tenant_id,id,code,active,created_by,updated_by) ON tna.attendance_type TO tna_app;
GRANT UPDATE(active,version,updated_at,updated_by) ON tna.attendance_type TO tna_app;
GRANT INSERT(tenant_id,id,type_id,revision,effective_from,enabled,name,unit,pay_kind,deduction_rule,recognition_rule,basis,reason,created_by) ON tna.attendance_type_version TO tna_app;
GRANT INSERT(attendance_type_version_id) ON tna.approval_request TO tna_app;
