-- "AI-extracted" facts of a report (PRD §7, §8.1; P7): the company, recruiter, CTC and joining date the AI read in the
-- letter. Each is text found in the letter (at most 100 characters, never a placeholder); NULL when the AI did not run
-- or found nothing. They are shown labelled "AI-extracted" and never affect the score. Deleted with the report (P11).
ALTER TABLE report
    ADD COLUMN ai_company_name   VARCHAR(100) NULL AFTER ai_used,
    ADD COLUMN ai_recruiter_name VARCHAR(100) NULL AFTER ai_company_name,
    ADD COLUMN ai_ctc            VARCHAR(100) NULL AFTER ai_recruiter_name,
    ADD COLUMN ai_joining_date   VARCHAR(100) NULL AFTER ai_ctc;
