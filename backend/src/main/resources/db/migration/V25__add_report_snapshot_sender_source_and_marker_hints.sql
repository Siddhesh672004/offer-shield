-- The registry's copy of the P13 report fields (V24; security review S2 and N2): sender_source (TYPED / LETTER),
-- letter_email_domains (distinct registrable email domains in the letter; IndicatorFactory turns a LETTER-sourced
-- domain into a scam marker only when it is the only one) and marker_hints (per phone / UPI HMAC key: typed or in
-- the letter, and a masked hint shown to the TPO or admin; never the raw value). NULL for snapshots made before P13.
ALTER TABLE report_snapshot
    ADD COLUMN sender_source        VARCHAR(8) NULL AFTER domain,
    ADD COLUMN letter_email_domains INT        NULL AFTER sender_source,
    ADD COLUMN marker_hints         JSON       NULL AFTER upi_hmacs;
