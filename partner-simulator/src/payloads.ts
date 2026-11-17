/** Realistic complaints a partner bank might forward. */
const COMPLAINTS = [
  { subject: 'Refund not received', category: 'REFUND', priority: 'HIGH',
    description: 'I cancelled my order 7 days ago and the refund has not arrived in my account.' },
  { subject: 'Card declined abroad', category: 'ACCOUNT', priority: 'CRITICAL',
    description: 'My card is being declined at every merchant while I am travelling in Singapore.' },
  { subject: 'Charged twice', category: 'BILLING', priority: 'MEDIUM',
    description: 'I see two identical debits for the same purchase on my statement this morning.' },
  { subject: 'App login loop', category: 'TECHNICAL', priority: 'LOW',
    description: 'The app keeps returning to the login screen after I enter the OTP.' },
] as const;

const NAMES = ['Rahul Sharma', 'Priya Nair', 'Arjun Mehta', 'Sneha Iyer', 'Imran Khan', 'Meera Pillai'];

let counter = 0;

export function complaint(overrides: Record<string, unknown> = {}): Buffer {
  const c = COMPLAINTS[counter % COMPLAINTS.length]!;
  const name = NAMES[counter % NAMES.length]!;
  counter++;
  const body = {
    customerName: name,
    customerEmail: `${name.toLowerCase().replace(' ', '.')}@example.com`,
    subject: c.subject,
    description: c.description,
    category: c.category,
    priority: c.priority,
    ...overrides,
  };
  return Buffer.from(JSON.stringify(body), 'utf8');
}
