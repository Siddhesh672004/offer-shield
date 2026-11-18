package com.offershield.retention;

/**
 * What one retention run did (counts only, D-27).
 *
 * @param submissionsWiped       submissions that became DELETED (text, contacts and sender domain wiped)
 * @param reportsDeleted         reports deleted with their signals and saved explanation
 * @param pdfsDeleted            private PDFs deleted from Cloudinary
 * @param pdfDeleteFailures      PDFs that could not be deleted (failure, storage outage or storage not configured);
 *                               their public IDs are kept and the next run tries again
 * @param snapshotsDeleted       report snapshots deleted
 * @param snapshotsKept          expired snapshots kept because an open (or recently decided) case or report needs them
 * @param descriptionsWiped      scam-report descriptions wiped 30 days after the decision
 * @param llmCacheDeleted        AI-cache rows older than 30 days deleted
 * @param processedEventsDeleted consumer dedupe rows older than 14 days deleted
 * @param failedSteps            steps stopped by an error (logged by exception class; the next run continues)
 * @param failedBatches          submission or snapshot batches whose transaction failed and was skipped (P13; logged by
 *                               exception class; the next run tries them again)
 */
public record RetentionResult(int submissionsWiped, int reportsDeleted, int pdfsDeleted, int pdfDeleteFailures,
                              int snapshotsDeleted, int snapshotsKept, int descriptionsWiped, int llmCacheDeleted,
                              int processedEventsDeleted, int failedSteps, int failedBatches) {
}
