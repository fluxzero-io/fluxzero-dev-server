package community.repaircafe.ticket.api;

import community.repaircafe.ticket.api.model.RepairTicket;
import io.fluxzero.sdk.modeling.Id;

public final class TicketId extends Id<RepairTicket> {
    public TicketId(String id) {
        super(id);
    }
}
