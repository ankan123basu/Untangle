/**
 * Automatic ticket assignment (ADR-029): waiting tickets go to the least busy agent who handles
 * their category, most urgent SLA first.
 *
 * <p>Who uses it: leads and admins switch it on and choose who takes automatic work; agents simply
 * receive tickets. Every assignment still goes through {@code TicketService}.
 */
package dev.untangle.assignment;
