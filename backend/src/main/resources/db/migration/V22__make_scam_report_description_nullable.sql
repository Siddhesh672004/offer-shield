-- 30-day retention (P11, D-06, PRD §14.6): the reporter's encrypted description is wiped (set to NULL) 30 days after
-- the admin's decision. The scam_report row itself stays (status, decision, amount asked), because the registry's
-- reporter counts and the audit trail refer to it.
ALTER TABLE scam_report MODIFY description_cipher BLOB NULL;
