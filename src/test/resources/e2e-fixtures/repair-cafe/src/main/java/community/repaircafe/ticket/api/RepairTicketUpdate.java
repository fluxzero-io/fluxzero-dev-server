package community.repaircafe.ticket.api;

import community.repaircafe.ticket.api.model.RepairTicket;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.TrackSelf;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.Request;
import io.fluxzero.sdk.publishing.routing.RoutingKey;
import jakarta.validation.constraints.NotNull;

@TrackSelf
@Consumer(name = "repair-ticket-update")
public interface RepairTicketUpdate extends Request<RepairTicket> {
    @RoutingKey
    @NotNull
    TicketId ticketId();

    @HandleCommand
    default RepairTicket handle() {
        return Fluxzero.loadAggregate(ticketId()).assertAndApply(this).get();
    }
}
