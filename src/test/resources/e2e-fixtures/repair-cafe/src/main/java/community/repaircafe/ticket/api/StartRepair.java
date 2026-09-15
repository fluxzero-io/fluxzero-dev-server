package community.repaircafe.ticket.api;

import community.repaircafe.ticket.api.model.RepairTicket;
import community.repaircafe.ticket.api.model.TicketHistoryEntry;
import io.fluxzero.sdk.modeling.AssertLegal;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;

import java.time.Instant;

public record StartRepair(TicketId ticketId) implements RepairTicketUpdate {
    @AssertLegal
    void assertOpen(RepairTicket ticket) {
        if (ticket == null) throw RepairErrors.ticketNotFound;
        if (ticket.status() != TicketStatus.OPEN) throw RepairErrors.repairNotOpen;
    }

    @Apply
    RepairTicket apply(RepairTicket ticket, Instant timestamp) {
        return ticket.withStatus(TicketStatus.IN_PROGRESS, null, null,
                new TicketHistoryEntry(LifecycleAction.STARTED, TicketStatus.IN_PROGRESS,
                        "Repair started", timestamp));
    }
}
