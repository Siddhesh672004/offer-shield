-- The TPO queue filters membership on (college_id, status) and orders by updated_at (a re-apply resets it), which the
-- V6 index on (college_id, status, created_at) can't serve without a filesort. This index also backs fk_membership_college.
CREATE INDEX idx_membership_college_status_updated ON membership (college_id, status, updated_at);
