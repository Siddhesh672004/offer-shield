-- P13 (security review S2 and N2), kept on the report so a re-sent analysis.completed (D-28) carries what the first
-- one did: sender_source = TYPED / LETTER (NULL without a sender domain); letter_email_domains = how many distinct
-- registrable email domains the letter itself contains (a LETTER-sourced domain becomes a scam marker only when it is
-- the only one); marker_hints = JSON array of {type, key, source, hint} for each phone / UPI HMAC key: whether it was
-- typed by the student or is in the letter, and a masked hint (last 4 digits of a phone, "••••ar@okaxis" for a UPI ID),
-- never the raw value. NULL for reports made before P13.
ALTER TABLE report
    ADD COLUMN sender_source        VARCHAR(8) NULL AFTER sender_domain,
    ADD COLUMN letter_email_domains INT        NULL AFTER sender_source,
    ADD COLUMN marker_hints         JSON       NULL AFTER upi_hmacs;
