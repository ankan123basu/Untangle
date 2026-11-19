import { isAxiosError } from 'axios';

/** One invalid field from a 400 VALIDATION_FAILED response. */
export interface FieldError {
  field: string;
  message: string;
}

/**
 * Every failed API call becomes an ApiError. The backend sends RFC 9457 Problem Details with a
 * machine-readable `code` (ADR-007); the UI branches on `code`, never on the English text.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly traceId: string | undefined;
  readonly fieldErrors: FieldError[];
  readonly extra: Record<string, unknown>;

  constructor(init: {
    status: number;
    code: string;
    message: string;
    traceId?: string | undefined;
    fieldErrors?: FieldError[];
    extra?: Record<string, unknown>;
  }) {
    super(init.message);
    this.name = 'ApiError';
    this.status = init.status;
    this.code = init.code;
    this.traceId = init.traceId;
    this.fieldErrors = init.fieldErrors ?? [];
    this.extra = init.extra ?? {};
  }
}

/**
 * The ONE place that maps error codes to words a person understands (§12.6). Unknown codes fall
 * back to the server's own detail text.
 */
const MESSAGES: Record<string, string> = {
  NETWORK_ERROR: "Can't reach Untangle. Check your connection and try again.",
  VALIDATION_FAILED: 'Some fields need attention.',
  AUTH_INVALID_CREDENTIALS: 'That email and password don’t match.',
  AUTH_ACCOUNT_LOCKED: 'Too many attempts. Your account is locked for a few minutes.',
  AUTH_ACCOUNT_DISABLED: 'This account has been deactivated. Contact an admin.',
  AUTH_REFRESH_INVALID: 'Your session ended. Please sign in again.',
  AUTH_REFRESH_REUSED: 'For your security, please sign in again.',
  AUTH_TOKEN_EXPIRED: 'Your session expired. Please sign in again.',
  AUTH_UNAUTHENTICATED: 'Please sign in.',
  ACCESS_DENIED: 'You don’t have permission to do that.',
  RATE_LIMITED: 'Slow down a little and try again in a moment.',
  TICKET_NOT_FOUND: 'That ticket doesn’t exist.',
  TICKET_CLOSED: 'This ticket is closed. Reopen it to make changes.',
  INVALID_STATUS_TRANSITION: 'That status change isn’t allowed from here.',
  RESOLUTION_NOTE_REQUIRED: 'Add a resolution note before resolving.',
  ASSIGNEE_REQUIRED: 'Assign the ticket first.',
  AGENT_INACTIVE: 'That agent is deactivated.',
  VERSION_CONFLICT: 'Someone else updated this just now. Refresh to see their changes.',
  EMAIL_TAKEN: 'An agent with that email already exists.',
  LAST_ADMIN: 'At least one active admin must remain.',
  CANNOT_DEACTIVATE_SELF: 'You can’t deactivate your own account.',
  AGENT_HAS_OPEN_TICKETS: 'Reassign this agent’s open tickets first.',
  PARTNER_CODE_TAKEN: 'A partner with that code already exists.',
  AI_UNAVAILABLE: 'AI assistance is unavailable right now.',
  AI_PROPOSAL_NOT_FOUND: 'There is no AI proposal waiting on this ticket.',
  AI_PROPOSAL_STALE: 'The ticket changed after the AI proposed this. Refresh to see the latest.',
  AI_TEAMMATE_FIXED: 'The AI teammate is always a plain agent and can’t sign in.',
  SERVICE_BUSY: 'Untangle is very busy right now. Please try again in a few seconds.',
  INTERNAL_ERROR: 'Something went wrong on our side.',
};

export function messageFor(error: ApiError): string {
  return MESSAGES[error.code] ?? error.message;
}

interface ProblemBody {
  code?: string;
  detail?: string;
  title?: string;
  traceId?: string;
  errors?: FieldError[];
  [key: string]: unknown;
}

/** Normalises anything thrown by the API client into an ApiError. */
export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) return error;
  if (isAxiosError<ProblemBody>(error)) {
    const response = error.response;
    if (!response) {
      return new ApiError({
        status: 0,
        code: 'NETWORK_ERROR',
        message: MESSAGES.NETWORK_ERROR ?? '',
      });
    }
    const body: ProblemBody = typeof response.data === 'object' ? response.data : {};
    const { code, detail, title, traceId, errors, ...extra } = body;
    return new ApiError({
      status: response.status,
      code: code ?? `HTTP_${String(response.status)}`,
      message: detail ?? title ?? `Request failed (${String(response.status)})`,
      traceId,
      fieldErrors: Array.isArray(errors) ? errors : [],
      extra,
    });
  }
  return new ApiError({
    status: 0,
    code: 'UNKNOWN',
    message: error instanceof Error ? error.message : 'Unexpected error',
  });
}
