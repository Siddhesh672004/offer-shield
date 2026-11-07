-- An official email domain of a known company (PRD §4 F8, §10; D-24). Stored as the registrable domain in lower-case
-- ASCII (punycode for IDNs), e.g. infosys.com, after the SSRF guard's checks, so a subdomain such as
-- careers.infosys.com counts as the same official domain. A domain belongs to at most one company. D-08: an official
-- domain never becomes a scam marker and is never flagged as a look-alike (C4).
CREATE TABLE known_company_domain (
    id          BINARY(16)    NOT NULL,
    company_id  BINARY(16)    NOT NULL,
    domain      VARCHAR(253)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_known_company_domain_domain UNIQUE (domain),
    CONSTRAINT fk_known_company_domain_company FOREIGN KEY (company_id) REFERENCES known_company (id) ON DELETE CASCADE,
    INDEX idx_known_company_domain_company (company_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
