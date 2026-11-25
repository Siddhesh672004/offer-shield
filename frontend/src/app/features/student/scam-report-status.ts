import { BadgeIcon, BadgeTone } from '../../shared/ui/badge';
import { ScamReportStatus } from './scam-report-api';

interface StatusView {
  readonly label: string;
  readonly tone: BadgeTone;
  readonly icon: BadgeIcon;
  readonly text: string;
}

/** How the reporter's own scam report reads (same wording as the decision email). */
export const SCAM_REPORT_STATUS: Readonly<Record<ScamReportStatus, StatusView>> = {
  OPEN: {
    label: 'Waiting for review',
    tone: 'warn',
    icon: 'clock',
    text: "An OfferShield admin will review your report. You'll get an email when they decide.",
  },
  CONFIRMED: {
    label: 'Confirmed by an admin',
    tone: 'danger',
    icon: 'check',
    text:
      'An admin confirmed your report. Letters that share its details are now flagged as ' +
      '“Confirmed scam” for everyone. Thank you for warning other students.',
  },
  REJECTED: {
    label: 'Not confirmed',
    tone: 'neutral',
    icon: 'cross',
    text:
      "An admin couldn't confirm your report, so it no longer counts in warnings. This doesn't " +
      'mean the offer is genuine: never pay money to get a job.',
  },
};
