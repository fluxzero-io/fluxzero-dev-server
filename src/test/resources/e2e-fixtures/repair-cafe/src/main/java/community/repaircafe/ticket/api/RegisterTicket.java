package community.repaircafe.ticket.api;

import community.repaircafe.ticket.api.model.RepairTicket;
import community.repaircafe.ticket.api.model.TicketHistoryEntry;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;

import java.time.Instant;
import java.util.List;

public record RegisterTicket(
        @NotNull TicketId ticketId,
        @NotBlank String itemName,
        @NotBlank String problemDescription,
        @NotNull Priority priority) implements RepairTicketUpdate {

    @AssertTrue(message = "ticketId must not be blank")
    boolean ticketIdHasText() {
        return ticketId != null && !ticketId.getFunctionalId().isBlank();
    }

    @Apply
    RepairTicket apply(Instant timestamp) {
        return new RepairTicket(ticketId, itemName.trim(), problemDescription.trim(), priority, priority.rank(),
                TicketStatus.OPEN, null, null,
                List.of(new TicketHistoryEntry(LifecycleAction.REGISTERED, TicketStatus.OPEN,
                        "Ticket registered", timestamp)));
    }
}
