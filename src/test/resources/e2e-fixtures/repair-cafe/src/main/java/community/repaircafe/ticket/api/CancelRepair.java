package community.repaircafe.ticket.api;

import community.repaircafe.ticket.api.model.RepairTicket;
import community.repaircafe.ticket.api.model.TicketHistoryEntry;
import io.fluxzero.sdk.modeling.AssertLegal;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public record CancelRepair(TicketId ticketId, @NotBlank String reason) implements RepairTicketUpdate {
    @AssertLegal
    void assertCancellable(RepairTicket ticket) {
        if (ticket == null) throw RepairErrors.ticketNotFound;
        if (ticket.status() != TicketStatus.OPEN && ticket.status() != TicketStatus.IN_PROGRESS) {
            throw RepairErrors.repairCannotBeCancelled;
        }
    }

    @Apply
    RepairTicket apply(RepairTicket ticket, Instant timestamp) {
        String cancellation = reason.trim();
        return ticket.withStatus(TicketStatus.CANCELLED, null, cancellation,
                new TicketHistoryEntry(LifecycleAction.CANCELLED, TicketStatus.CANCELLED, cancellation, timestamp));
    }
}
