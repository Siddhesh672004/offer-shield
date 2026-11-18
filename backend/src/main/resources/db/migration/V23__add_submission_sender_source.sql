-- Where the sender domain came from (P13, security review S2; D-33.6): TYPED = the sender email the student typed,
-- LETTER = the first email address written in the letter itself (the letter's author controls it), NULL when there
-- is no sender domain. Rows saved before P13 stay NULL and are read as TYPED when a typed sender email exists, else
-- as LETTER.
ALTER TABLE submission
    ADD COLUMN sender_source VARCHAR(8) NULL AFTER sender_domain,
    ADD CONSTRAINT ck_submission_sender_source CHECK (sender_source IN ('TYPED', 'LETTER'));
