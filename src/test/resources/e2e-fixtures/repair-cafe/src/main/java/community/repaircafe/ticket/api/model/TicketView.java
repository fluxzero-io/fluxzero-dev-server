package community.repaircafe.ticket.api.model;

import community.repaircafe.ticket.api.Priority;
import community.repaircafe.ticket.api.TicketStatus;

import java.util.List;

public record TicketView(
        String ticketId,
        String itemName,
        String problemDescription,
        Priority priority,
        TicketStatus status,
        String resolutionSummary,
        String cancellationReason,
        List<TicketHistoryEntry> history) {

    public static TicketView from(RepairTicket ticket) {
        return new TicketView(ticket.ticketId().getFunctionalId(), ticket.itemName(), ticket.problemDescription(),
                ticket.priority(), ticket.status(), ticket.resolutionSummary(), ticket.cancellationReason(),
                ticket.history());
    }
}
