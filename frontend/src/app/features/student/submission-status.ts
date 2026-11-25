import { BadgeIcon, BadgeTone } from '../../shared/ui/badge';
import { SourceType, SubmissionStatus } from './submission-api';

interface StatusView {
  readonly label: string;
  readonly tone: BadgeTone;
  readonly icon: BadgeIcon;
}

/** A letter's state as a badge. "Report ready" stays neutral blue: a finished check is not a verdict. */
export const SUBMISSION_STATUS: Readonly<Record<SubmissionStatus, StatusView>> = {
  QUEUED: { label: 'Checking', tone: 'info', icon: 'clock' },
  READY: { label: 'Report ready', tone: 'info', icon: 'check' },
  PARTIAL: { label: 'Partial report', tone: 'warn', icon: 'check' },
  FAILED: { label: "Couldn't check", tone: 'danger', icon: 'cross' },
  DELETED: { label: 'Deleted', tone: 'neutral', icon: 'none' },
};

export const SOURCE_LABELS: Readonly<Record<SourceType, string>> = {
  PDF: 'PDF',
  TEXT: 'Pasted text',
};
