package community.repaircafe.ticket.api;

import community.repaircafe.ticket.api.model.RepairTicket;
import community.repaircafe.ticket.api.model.TicketView;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import io.fluxzero.sdk.tracking.handling.Request;

public record GetTicket(TicketId ticketId) implements Request<TicketView> {
    @HandleQuery
    TicketView handle() {
        Entity<RepairTicket> loaded = Fluxzero.loadAggregate(ticketId);
        return loaded.get() == null ? null : TicketView.from(loaded.get());
    }
}
