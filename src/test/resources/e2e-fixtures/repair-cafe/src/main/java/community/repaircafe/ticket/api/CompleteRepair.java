package community.repaircafe.ticket.api;

import community.repaircafe.ticket.api.model.RepairTicket;
import community.repaircafe.ticket.api.model.TicketHistoryEntry;
import io.fluxzero.sdk.modeling.AssertLegal;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public record CompleteRepair(TicketId ticketId, @NotBlank String resolutionSummary) implements RepairTicketUpdate {
    @AssertLegal
    void assertInProgress(RepairTicket ticket) {
        if (ticket == null) throw RepairErrors.ticketNotFound;
        if (ticket.status() != TicketStatus.IN_PROGRESS) throw RepairErrors.repairNotInProgress;
    }

    @Apply
    RepairTicket apply(RepairTicket ticket, Instant timestamp) {
        String summary = resolutionSummary.trim();
        return ticket.withStatus(TicketStatus.COMPLETED, summary, null,
                new TicketHistoryEntry(LifecycleAction.COMPLETED, TicketStatus.COMPLETED, summary, timestamp));
    }
}
