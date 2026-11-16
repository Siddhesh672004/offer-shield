package com.offershield.scamreport;

/** PRD §6.4 / 02 §7: OPEN → CONFIRMED or REJECTED, decided once by the admin with If-Match. */
public enum ScamReportStatus {
    OPEN,
    CONFIRMED,
    REJECTED
}
