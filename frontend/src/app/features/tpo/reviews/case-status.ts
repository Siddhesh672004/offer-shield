import { BadgeIcon, BadgeTone } from '../../../shared/ui/badge';
import { ReviewDecision, ReviewStatus } from './reviews-api';

interface StatusView {
  readonly label: string;
  readonly tone: BadgeTone;
  readonly icon: BadgeIcon;
}

/** How a case's state reads to the TPO (the student's labels, from the TPO's side). */
export const CASE_STATUS: Readonly<Record<ReviewStatus, StatusView>> = {
  OPEN: { label: 'Waiting for you', tone: 'warn', icon: 'clock' },
  CONFIRMED_SCAM: { label: 'Marked as a scam', tone: 'danger', icon: 'cross' },
  CONFIRMED_GENUINE: { label: 'Marked as genuine', tone: 'info', icon: 'check' },
  INCONCLUSIVE: { label: 'No conclusion', tone: 'neutral', icon: 'none' },
};

export interface DecisionOption {
  readonly value: ReviewDecision;
  readonly label: string;
  readonly hint: string;
}

/** The three final decisions (PRD F5), with what each one does. */
export const DECISION_OPTIONS: readonly DecisionOption[] = [
  {
    value: 'CONFIRMED_SCAM',
    label: 'Mark as a scam',
    hint: 'The student is warned, and letters that share its details show “Confirmed scam” for everyone.',
  },
  {
    value: 'CONFIRMED_GENUINE',
    label: 'Mark as genuine',
    hint: 'The student is told, and reminded never to pay money to get a job.',
  },
  {
    value: 'INCONCLUSIVE',
    label: 'No conclusion',
    hint: "The student is asked to check the offer on the company's official website.",
  },
];
