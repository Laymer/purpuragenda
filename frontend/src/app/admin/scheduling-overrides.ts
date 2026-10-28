export const OVERRIDE_RULES: Record<string, { rule: string; label: string }> = {
  START_NOT_AVAILABLE: { rule: 'START_RULES', label: 'Outside scheduled start times' },
  MINIMUM_NOTICE: { rule: 'NOTICE', label: 'Less than minimum notice' },
  OUTSIDE_HORIZON: { rule: 'HORIZON', label: 'Outside the booking horizon' },
  DAILY_CAPACITY_REACHED: { rule: 'DAILY_CAPACITY', label: 'Daily booking limit reached' },
  ACTIVE_LIMIT_REACHED: { rule: 'ACTIVE_LIMIT', label: 'Customer active booking limit reached' },
  REQUEST_LIMIT_REACHED: { rule: 'REQUEST_LIMIT', label: 'Request limit reached' },
  HARD_BLACKOUT: { rule: 'HARD_BLACKOUT', label: 'Reservation intersects a blackout' },
};
