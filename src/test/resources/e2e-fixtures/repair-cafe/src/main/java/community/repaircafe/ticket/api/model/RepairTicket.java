package community.repaircafe.ticket.api.model;

import community.repaircafe.ticket.api.Priority;
import community.repaircafe.ticket.api.TicketId;
import community.repaircafe.ticket.api.TicketStatus;
import io.fluxzero.common.search.Sortable;
import io.fluxzero.sdk.modeling.Aggregate;
import io.fluxzero.sdk.modeling.EventPublication;
import io.fluxzero.sdk.modeling.EntityId;

import java.util.List;

@Aggregate(searchable = true, eventPublication = EventPublication.IF_MODIFIED)
public record RepairTicket(
        @EntityId @Sortable TicketId ticketId,
        String itemName,
        String problemDescription,
        Priority priority,
        @Sortable int priorityRank,
        @Sortable TicketStatus status,
        String resolutionSummary,
        String cancellationReason,
        List<TicketHistoryEntry> history) {

    public RepairTicket withStatus(TicketStatus nextStatus, String resolution, String cancellation,
                                   TicketHistoryEntry entry) {
        var updatedHistory = new java.util.ArrayList<>(history);
        updatedHistory.add(entry);
        return new RepairTicket(ticketId, itemName, problemDescription, priority, priorityRank, nextStatus,
                resolution, cancellation, List.copyOf(updatedHistory));
    }
}
