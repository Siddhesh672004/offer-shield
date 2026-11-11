-- What the registry needs from a report (PRD §10.1, F7; P9), kept on the report so a re-sent analysis.completed (D-28)
-- carries exactly what the first one did: the letter's SimHash fingerprint (64 bits; signed BIGINT holds the same
-- bits, NULL for letters under 50 words), the HMAC-SHA256 values (lower-case hex) of the phone numbers and UPI IDs
-- found (never the raw values), indicators_complete (false when a list hit its cap; false for reports made before P9)
-- and reported_by = distinct users who reported a matching marker that is not confirmed (C12, D-33.5).
ALTER TABLE report
    ADD COLUMN simhash             BIGINT  NULL AFTER sender_domain,
    ADD COLUMN phone_hmacs         JSON    NULL AFTER simhash,
    ADD COLUMN upi_hmacs           JSON    NULL AFTER phone_hmacs,
    ADD COLUMN indicators_complete BOOLEAN NOT NULL DEFAULT FALSE AFTER upi_hmacs,
    ADD COLUMN reported_by         INT     NOT NULL DEFAULT 0 AFTER indicators_complete;
