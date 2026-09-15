package community.repaircafe.ticket.api.model;

import community.repaircafe.ticket.api.LifecycleAction;
import community.repaircafe.ticket.api.TicketStatus;

import java.time.Instant;

public record TicketHistoryEntry(LifecycleAction action, TicketStatus status, String detail, Instant occurredAt) {
}
