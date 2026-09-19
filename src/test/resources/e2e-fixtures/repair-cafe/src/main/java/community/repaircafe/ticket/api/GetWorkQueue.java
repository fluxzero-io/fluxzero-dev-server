package community.repaircafe.ticket.api;

import community.repaircafe.ticket.api.model.RepairTicket;
import community.repaircafe.ticket.api.model.TicketView;
import community.repaircafe.ticket.api.model.WorkQueue;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import io.fluxzero.sdk.tracking.handling.Request;

public record GetWorkQueue(TicketStatus status) implements Request<WorkQueue> {
    @HandleQuery
    WorkQueue handle() {
        var search = Fluxzero.search(RepairTicket.class);
        if (status != null) {
            search = search.match(status, true, "status");
        }
        long total = search.count();
        var tickets = search.sortBy("priorityRank").sortBy("ticketId").fetchAll(RepairTicket.class);
        return new WorkQueue(tickets.stream().map(TicketView::from).toList(), total);
    }
}
