ALTER TABLE tna.approval_event
    ADD COLUMN step_number smallint,
    ADD FOREIGN KEY (tenant_id,request_id,step_number)
        REFERENCES tna.request_approval_step(tenant_id,request_id,step_number);
