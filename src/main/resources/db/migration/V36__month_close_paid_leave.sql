-- NULL preserves the original workbook layout. An empty array is a new close with no paid hours.
ALTER TABLE tna.attendance_month_closure ADD COLUMN paid_leave_snapshot jsonb
    CHECK (paid_leave_snapshot IS NULL OR (status='CLOSED' AND jsonb_typeof(paid_leave_snapshot)='array'));
