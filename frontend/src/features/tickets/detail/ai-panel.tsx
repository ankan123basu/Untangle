import { useMutation } from '@tanstack/react-query';
import { Sparkles, ThumbsDown, ThumbsUp } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Card, CardBody, CardHeader, CardTitle } from '@/components/ui/card';
import { api } from '@/lib/api/client';
import { ApiError, messageFor } from '@/lib/api/problem';
import type { ReplyDraft, TimelineSummary } from '@/lib/api/types';
import { useCan } from '@/features/auth/session-store';
import { useAiStatus } from '../ticket-api';

function sendFeedback(insightId: string, accepted: boolean) {
  void api.patch(`/ai/insights/${insightId}/feedback`, { accepted }).catch(() => undefined);
}

function errorText(error: unknown): string {
  return error instanceof ApiError ? messageFor(error) : 'AI assistance is unavailable right now.';
}

/**
 * AI helpers on the ticket (ADR-022): a summary for catching up and a reply draft. Both are only
 * suggestions; the draft goes into the composer where the agent edits it before sending.
 * Hidden entirely when AI is off or the user lacks AI_USE.
 */
export function AiPanel({
  ticketId,
  onUseDraft,
}: {
  ticketId: string;
  onUseDraft: (text: string) => void;
}) {
  const canUse = useCan('AI_USE');
  const status = useAiStatus();
  const summary = useMutation({
    mutationFn: async () =>
      (await api.post<TimelineSummary>(`/tickets/${ticketId}/ai/summary`)).data,
  });
  const draft = useMutation({
    mutationFn: async () =>
      (await api.post<ReplyDraft>(`/tickets/${ticketId}/ai/reply-draft`)).data,
  });

  if (!canUse || !status.data?.enabled) return null;

  return (
    <Card tone="glass">
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Sparkles aria-hidden="true" className="size-4" /> AI assist
        </CardTitle>
      </CardHeader>
      <CardBody className="flex flex-col gap-4 text-sm">
        <p className="text-xs text-muted">
          Suggestions only. Customer contact details are removed before anything is sent.
        </p>
        <div className="flex flex-wrap gap-2">
          <Button
            size="sm"
            loading={summary.isPending}
            onClick={() => {
              summary.mutate();
            }}
          >
            Summarise
          </Button>
          <Button
            size="sm"
            loading={draft.isPending}
            onClick={() => {
              draft.mutate();
            }}
          >
            Draft note
          </Button>
        </div>

        {summary.isError && (
          <p role="alert" className="text-danger">
            {errorText(summary.error)}
          </p>
        )}
        {summary.data && (
          <section
            aria-label="AI summary"
            className="flex flex-col gap-2 rounded-md border-2 border-line-soft bg-surface p-3"
          >
            <p className="whitespace-pre-wrap">{summary.data.summary}</p>
            {summary.data.keyPoints.length > 0 && (
              <ul className="list-disc pl-5">
                {summary.data.keyPoints.map((point, i) => (
                  <li key={i}>{point}</li>
                ))}
              </ul>
            )}
            {summary.data.nextStep && (
              <p>
                <span className="font-semibold">Next step:</span> {summary.data.nextStep}
              </p>
            )}
            <FeedbackButtons insightId={summary.data.insightId} />
          </section>
        )}

        {draft.isError && (
          <p role="alert" className="text-danger">
            {errorText(draft.error)}
          </p>
        )}
        {draft.data && (
          <section
            aria-label="AI note draft"
            className="flex flex-col gap-2 rounded-md border-2 border-line-soft bg-surface p-3"
          >
            <p className="whitespace-pre-wrap">{draft.data.draft}</p>
            <div className="flex gap-2">
              <Button
                size="sm"
                variant="primary"
                onClick={() => {
                  onUseDraft(draft.data.draft);
                  sendFeedback(draft.data.insightId, true);
                  toast.success('Draft placed in the comment box. Review it before sending.');
                  draft.reset();
                }}
              >
                Use draft
              </Button>
              <Button
                size="sm"
                variant="ghost"
                onClick={() => {
                  sendFeedback(draft.data.insightId, false);
                  draft.reset();
                }}
              >
                Discard
              </Button>
            </div>
          </section>
        )}
      </CardBody>
    </Card>
  );
}

function FeedbackButtons({ insightId }: { insightId: string }) {
  return (
    <div className="flex items-center gap-1 text-xs text-muted">
      Helpful?
      <Button
        variant="ghost"
        size="sm"
        aria-label="Helpful"
        icon={<ThumbsUp className="size-4" />}
        onClick={() => {
          sendFeedback(insightId, true);
          toast('Thanks for the feedback');
        }}
      />
      <Button
        variant="ghost"
        size="sm"
        aria-label="Not helpful"
        icon={<ThumbsDown className="size-4" />}
        onClick={() => {
          sendFeedback(insightId, false);
          toast('Thanks for the feedback');
        }}
      />
    </div>
  );
}
