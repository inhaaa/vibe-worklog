CREATE TABLE platform.operator_account (
 id uuid PRIMARY KEY,login_id varchar(120) NOT NULL UNIQUE,password_hash text NOT NULL,
 active boolean NOT NULL DEFAULT true,auth_version bigint NOT NULL DEFAULT 1,
 CHECK(login_id=lower(btrim(login_id)) AND login_id<>''),CHECK(btrim(password_hash)<>''),CHECK(auth_version>=1)
);
CREATE TABLE platform.operator_audit_event (
 id uuid PRIMARY KEY,operator_id uuid NOT NULL REFERENCES platform.operator_account(id),tenant_id uuid,
 command_id uuid NOT NULL UNIQUE,action varchar(40) NOT NULL,input_hash char(64) NOT NULL,
 reason varchar(500) NOT NULL,relationship_basis varchar(500),db_actor text NOT NULL DEFAULT session_user,
 summary jsonb NOT NULL,created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK(btrim(reason)<>''),CHECK(input_hash ~ '^[0-9a-f]{64}$'),CHECK(jsonb_typeof(summary)='object')
);
ALTER TABLE platform.tenant_registry ADD COLUMN version bigint NOT NULL DEFAULT 0 CHECK(version>=0);
ALTER TABLE tna.account_token ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE tna.account_token ADD COLUMN operator_created_by uuid REFERENCES platform.operator_account(id);
ALTER TABLE tna.account_token ADD CONSTRAINT account_token_issuer_valid CHECK (
 (created_by IS NOT NULL AND operator_created_by IS NULL) OR(created_by IS NULL AND operator_created_by IS NOT NULL));
ALTER TABLE platform.operator_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.operator_account FORCE ROW LEVEL SECURITY;
CREATE POLICY operator_metadata ON platform.operator_account TO tna_migration,tna_operator USING(true) WITH CHECK(true);
ALTER TABLE platform.operator_audit_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.operator_audit_event FORCE ROW LEVEL SECURITY;
CREATE POLICY operator_metadata ON platform.operator_audit_event TO tna_migration,tna_operator USING(true) WITH CHECK(true);
CREATE POLICY operator_registry_read ON platform.tenant_registry FOR SELECT TO tna_operator USING(true);
REVOKE ALL ON platform.operator_account,platform.operator_audit_event FROM PUBLIC;
GRANT USAGE ON SCHEMA platform TO tna_operator;
GRANT SELECT ON platform.operator_account,platform.operator_audit_event TO tna_operator;
GRANT SELECT(id,company_code,display_name,active,version) ON platform.tenant_registry TO tna_operator;

-- Security bootstrap bridge only; attendance, leave and approval rules remain in Java.
CREATE FUNCTION platform.operator_onboard(_operator uuid,_auth_version bigint,_command uuid,_hash text,
 _tenant uuid,_code text,_name text,_employee uuid,_employee_no text,_employee_name text,_joined date,
 _account uuid,_email text,_token uuid,_token_hash text,_expires timestamptz,_basis text,_reason text)
RETURNS TABLE(new_tenant_id uuid,new_account_id uuid,created boolean)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $body$
DECLARE old platform.operator_audit_event%ROWTYPE;
BEGIN
 IF session_user<>'tna_operator' THEN RAISE EXCEPTION USING ERRCODE='42501',MESSAGE='Operator role required';END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('platform-onboarding',0));
 PERFORM 1 FROM platform.operator_account WHERE id=_operator AND active AND auth_version=_auth_version FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='42501',MESSAGE='Current operator required';END IF;
 SELECT * INTO old FROM platform.operator_audit_event WHERE command_id=_command;
 IF FOUND THEN
  IF old.operator_id<>_operator OR old.action<>'TENANT_ONBOARD' OR old.input_hash<>_hash THEN RAISE EXCEPTION USING ERRCODE='23505',MESSAGE='Command conflict';END IF;
  RETURN QUERY SELECT old.tenant_id,(old.summary->>'accountId')::uuid,false;RETURN;
 END IF;
 IF nullif(btrim(_basis),'') IS NULL OR nullif(btrim(_reason),'') IS NULL OR _joined>(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::date
  OR _expires<=CURRENT_TIMESTAMP OR _expires>CURRENT_TIMESTAMP+INTERVAL '25 hours' THEN
  RAISE EXCEPTION USING ERRCODE='22023',MESSAGE='Invalid onboarding evidence';
 END IF;
 PERFORM pg_catalog.set_config('app.tenant_id',_tenant::text,true);
 INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES(_tenant,_code,_name);
 INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on,created_at,updated_at)
 VALUES(_tenant,_employee,_employee_no,_employee_name,_joined,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
 INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,active) VALUES(_tenant,_account,_employee,_email,false);
 INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES(_tenant,_account,'EMPLOYEE'),(_tenant,_account,'ATTENDANCE_ADMIN');
 INSERT INTO tna.account_token(tenant_id,id,account_id,purpose,token_hash,email,issued_auth_version,expires_at,operator_created_by)
 VALUES(_tenant,_token,_account,'INVITE',_token_hash,_email,1,_expires,_operator);
 INSERT INTO platform.operator_audit_event(id,operator_id,tenant_id,command_id,action,input_hash,reason,relationship_basis,db_actor,summary)
 VALUES(pg_catalog.gen_random_uuid(),_operator,_tenant,_command,'TENANT_ONBOARD',_hash,_reason,_basis,session_user,
  pg_catalog.jsonb_build_object('accountId',_account,'employeeId',_employee,'companyCode',_code,'active',true,'version',0));
 RETURN QUERY SELECT _tenant,_account,true;
END $body$;
REVOKE ALL ON FUNCTION platform.operator_onboard(uuid,bigint,uuid,text,uuid,text,text,uuid,text,text,date,uuid,text,uuid,text,timestamptz,text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.operator_onboard(uuid,bigint,uuid,text,uuid,text,text,uuid,text,text,date,uuid,text,uuid,text,timestamptz,text,text) TO tna_operator;

CREATE FUNCTION platform.operator_set_state(_operator uuid,_auth_version bigint,_command uuid,_hash text,
 _tenant uuid,_active boolean,_version bigint,_reason text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $body$
DECLARE old platform.operator_audit_event%ROWTYPE;before_state boolean;before_version bigint;
BEGIN
 IF session_user<>'tna_operator' THEN RAISE EXCEPTION USING ERRCODE='42501',MESSAGE='Operator role required';END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('platform-onboarding',0));
 PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(_tenant::text,0));
 PERFORM 1 FROM platform.operator_account WHERE id=_operator AND active AND auth_version=_auth_version FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='42501',MESSAGE='Current operator required';END IF;
 SELECT * INTO old FROM platform.operator_audit_event WHERE command_id=_command;
 IF FOUND THEN
  IF old.operator_id<>_operator OR old.action<>'TENANT_STATE' OR old.input_hash<>_hash THEN RAISE EXCEPTION USING ERRCODE='23505',MESSAGE='Command conflict';END IF;
  RETURN old.tenant_id;
 END IF;
 PERFORM pg_catalog.set_config('app.tenant_id',_tenant::text,true);
 SELECT active,version INTO before_state,before_version FROM platform.tenant_registry WHERE id=_tenant FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0002',MESSAGE='Tenant unavailable';END IF;
 IF _version IS NULL OR _version<>before_version THEN RAISE EXCEPTION USING ERRCODE='23505',MESSAGE='Version conflict';END IF;
 IF nullif(btrim(_reason),'') IS NULL THEN RAISE EXCEPTION USING ERRCODE='22023',MESSAGE='Reason required';END IF;
 IF before_state IS DISTINCT FROM _active THEN
  UPDATE platform.tenant_registry SET active=_active,version=version+1 WHERE id=_tenant;
  UPDATE tna.tenant_account SET auth_version=auth_version+1 WHERE tenant_id=_tenant;
 END IF;
 INSERT INTO platform.operator_audit_event(id,operator_id,tenant_id,command_id,action,input_hash,reason,db_actor,summary)
 VALUES(pg_catalog.gen_random_uuid(),_operator,_tenant,_command,'TENANT_STATE',_hash,_reason,session_user,
  pg_catalog.jsonb_build_object('beforeActive',before_state,'active',_active,'beforeVersion',before_version));
 RETURN _tenant;
END $body$;
REVOKE ALL ON FUNCTION platform.operator_set_state(uuid,bigint,uuid,text,uuid,boolean,bigint,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.operator_set_state(uuid,bigint,uuid,text,uuid,boolean,bigint,text) TO tna_operator;

-- Reissues only the original audited, still-pending first account; never creates another administrator.
CREATE FUNCTION platform.operator_reissue_initial(_operator uuid,_auth_version bigint,_command uuid,_hash text,
 _tenant uuid,_version bigint,_token uuid,_token_hash text,_expires timestamptz,_reason text)
RETURNS TABLE(new_account_id uuid,resolved_company_code text,resolved_email text,sent boolean)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $body$
DECLARE old platform.operator_audit_event%ROWTYPE;first_account uuid;company_code_value text;
 company_active boolean;company_version bigint;identity_row record;first_email text;first_employee uuid;
BEGIN
 IF session_user<>'tna_operator' THEN RAISE EXCEPTION USING ERRCODE='42501',MESSAGE='Operator role required';END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('platform-onboarding',0));
 PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(_tenant::text,0));
 PERFORM 1 FROM platform.operator_account WHERE id=_operator AND active AND auth_version=_auth_version FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='42501',MESSAGE='Current operator required';END IF;
 SELECT * INTO old FROM platform.operator_audit_event WHERE command_id=_command;
 IF FOUND THEN
  IF old.operator_id<>_operator OR old.tenant_id<>_tenant OR old.action<>'TENANT_INVITE_REISSUE' OR old.input_hash<>_hash
   THEN RAISE EXCEPTION USING ERRCODE='23505',MESSAGE='Command conflict';END IF;
  RETURN QUERY SELECT (old.summary->>'accountId')::uuid,old.summary->>'companyCode',NULL::text,false;RETURN;
 END IF;
 PERFORM pg_catalog.set_config('app.tenant_id',_tenant::text,true);
 SELECT r.company_code,r.active,r.version INTO company_code_value,company_active,company_version
  FROM platform.tenant_registry r WHERE r.id=_tenant FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0002',MESSAGE='Tenant unavailable';END IF;
 IF _version IS NULL OR _version<>company_version THEN RAISE EXCEPTION USING ERRCODE='23505',MESSAGE='Version conflict';END IF;
 IF NOT company_active OR nullif(btrim(_reason),'') IS NULL OR _expires<=CURRENT_TIMESTAMP OR _expires>CURRENT_TIMESTAMP+INTERVAL '25 hours'
  THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='Active company and current invitation evidence required';END IF;
 SELECT (a.summary->>'accountId')::uuid,(a.summary->>'employeeId')::uuid INTO first_account,first_employee FROM platform.operator_audit_event a
  WHERE a.tenant_id=_tenant AND a.action='TENANT_ONBOARD' ORDER BY a.created_at,a.id LIMIT 1;
 IF NOT FOUND OR first_account IS NULL THEN RAISE EXCEPTION USING ERRCODE='P0002',MESSAGE='Initial invitation unavailable';END IF;
 SELECT t.email INTO first_email FROM tna.account_token t WHERE t.tenant_id=_tenant AND t.account_id=first_account
  AND t.purpose='INVITE' ORDER BY t.created_at,t.id LIMIT 1;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0002',MESSAGE='Initial invitation unavailable';END IF;
 SELECT a.employee_id,a.email,a.active,a.credential_set,a.auth_version,e.active AS employee_active,e.joined_on,e.left_on
  INTO identity_row FROM tna.tenant_account a JOIN tna.employee e ON e.tenant_id=a.tenant_id AND e.id=a.employee_id
  WHERE a.tenant_id=_tenant AND a.id=first_account FOR UPDATE OF a;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0002',MESSAGE='Initial invitation unavailable';END IF;
 IF first_employee IS DISTINCT FROM identity_row.employee_id OR first_email IS DISTINCT FROM identity_row.email
  OR identity_row.active OR identity_row.credential_set OR NOT identity_row.employee_active
  OR identity_row.joined_on>(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::date
  OR(identity_row.left_on IS NOT NULL AND identity_row.left_on<(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::date)
  THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='Only the unchanged pending first account can be reissued';END IF;
 UPDATE tna.account_token SET revoked_at=CURRENT_TIMESTAMP WHERE tenant_id=_tenant AND account_id=first_account
  AND purpose='INVITE' AND used_at IS NULL AND revoked_at IS NULL;
 UPDATE tna.tenant_account SET auth_version=auth_version+1 WHERE tenant_id=_tenant AND id=first_account;
 INSERT INTO tna.account_token(tenant_id,id,account_id,purpose,token_hash,email,issued_auth_version,expires_at,operator_created_by)
 VALUES(_tenant,_token,first_account,'INVITE',_token_hash,identity_row.email,identity_row.auth_version+1,_expires,_operator);
 UPDATE platform.tenant_registry SET version=version+1 WHERE id=_tenant;
 INSERT INTO platform.operator_audit_event(id,operator_id,tenant_id,command_id,action,input_hash,reason,db_actor,summary)
 VALUES(pg_catalog.gen_random_uuid(),_operator,_tenant,_command,'TENANT_INVITE_REISSUE',_hash,_reason,session_user,
  pg_catalog.jsonb_build_object('accountId',first_account,'companyCode',company_code_value,'beforeVersion',company_version));
 RETURN QUERY SELECT first_account,company_code_value,identity_row.email::text,true;
END $body$;
REVOKE ALL ON FUNCTION platform.operator_reissue_initial(uuid,bigint,uuid,text,uuid,bigint,uuid,text,timestamptz,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.operator_reissue_initial(uuid,bigint,uuid,text,uuid,bigint,uuid,text,timestamptz,text) TO tna_operator;
