import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Button } from '@/components/ui/button';
import { Dialog } from '@/components/ui/dialog';
import { Field, Input, Select, Textarea } from '@/components/ui/field';
import { CATEGORIES, PRIORITIES, type TicketDetail } from '@/lib/api/types';
import { humanize } from '@/lib/utils';

const schema = z.object({
  subject: z.string().trim().min(5, 'At least 5 characters').max(150, 'At most 150 characters'),
  description: z
    .string()
    .trim()
    .min(10, 'At least 10 characters')
    .max(5000, 'At most 5000 characters'),
  category: z.enum(CATEGORIES),
  priority: z.enum(PRIORITIES),
});
export type EditValues = z.infer<typeof schema>;

/** Edit subject/description/category/priority. Only changed fields are sent. */
export function EditDialog({
  open,
  ticket,
  onClose,
  onSave,
}: {
  open: boolean;
  ticket: TicketDetail;
  onClose: () => void;
  onSave: (changes: Partial<EditValues>) => Promise<unknown>;
}) {
  const form = useForm<EditValues>({
    resolver: zodResolver(schema),
    values: {
      subject: ticket.subject,
      description: ticket.description,
      category: ticket.category,
      priority: ticket.priority,
    },
  });
  const { errors, isSubmitting } = form.formState;

  const submit = form.handleSubmit(async (values) => {
    const changes: Partial<EditValues> = {};
    if (values.subject !== ticket.subject) changes.subject = values.subject;
    if (values.description !== ticket.description) changes.description = values.description;
    if (values.category !== ticket.category) changes.category = values.category;
    if (values.priority !== ticket.priority) changes.priority = values.priority;
    if (Object.keys(changes).length === 0) {
      onClose();
      return;
    }
    try {
      await onSave(changes);
      onClose();
    } catch {
      // error already shown by the page
    }
  });

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={`Edit ${ticket.ticketNumber}`}
      description="Changing the priority recalculates the SLA deadline."
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" loading={isSubmitting} onClick={() => void submit()}>
            Save changes
          </Button>
        </>
      }
    >
      <form className="flex flex-col gap-4" onSubmit={(e) => void submit(e)} noValidate>
        <Field label="Subject" required error={errors.subject?.message}>
          {(props) => <Input {...props} {...form.register('subject')} />}
        </Field>
        <Field label="Description" required error={errors.description?.message}>
          {(props) => <Textarea {...props} rows={6} {...form.register('description')} />}
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Category" required>
            {(props) => (
              <Select {...props} {...form.register('category')}>
                {CATEGORIES.map((c) => (
                  <option key={c} value={c}>
                    {humanize(c)}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          <Field label="Priority" required>
            {(props) => (
              <Select {...props} {...form.register('priority')}>
                {PRIORITIES.map((p) => (
                  <option key={p} value={p}>
                    {humanize(p)}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        </div>
      </form>
    </Dialog>
  );
}
