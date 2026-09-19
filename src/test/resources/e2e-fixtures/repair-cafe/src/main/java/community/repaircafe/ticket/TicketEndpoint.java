package community.repaircafe.ticket;

import community.repaircafe.ticket.api.*;
import community.repaircafe.ticket.api.model.TicketView;
import community.repaircafe.ticket.api.model.WorkQueue;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.web.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Component;

@Component
@Path("tickets")
@ApiDoc(tags = "Repair tickets", description = "Register repairs and move them through the work queue.")
public class TicketEndpoint {
    @HandlePost
    @ApiDoc(summary = "Register a repair ticket", operationId = "registerTicket")
    TicketView register(@Valid RegisterTicketRequest request) {
        var result = Fluxzero.sendCommandAndWait(new RegisterTicket(new TicketId(request.ticketId()),
                request.itemName(), request.problemDescription(), request.priority()));
        return TicketView.from(result);
    }

    @HandleGet
    @ApiDoc(summary = "List the work queue", operationId = "getWorkQueue")
    WorkQueue queue(@QueryParam("status") TicketStatus status) {
        return Fluxzero.queryAndWait(new GetWorkQueue(status));
    }

    @HandleGet("/{ticketId}")
    @ApiDoc(summary = "Get one repair ticket", operationId = "getTicket")
    TicketView get(@PathParam("ticketId") String ticketId) {
        return Fluxzero.queryAndWait(new GetTicket(new TicketId(ticketId)));
    }

    @HandlePost("/{ticketId}/start")
    @ApiDoc(summary = "Start an open repair", operationId = "startRepair")
    TicketView start(@PathParam("ticketId") String ticketId) {
        return TicketView.from(Fluxzero.sendCommandAndWait(new StartRepair(new TicketId(ticketId))));
    }

    @HandlePost("/{ticketId}/complete")
    @ApiDoc(summary = "Complete an in-progress repair", operationId = "completeRepair")
    TicketView complete(@PathParam("ticketId") String ticketId, @Valid ResolutionRequest request) {
        return TicketView.from(Fluxzero.sendCommandAndWait(
                new CompleteRepair(new TicketId(ticketId), request.resolutionSummary())));
    }

    @HandlePost("/{ticketId}/cancel")
    @ApiDoc(summary = "Cancel an active repair", operationId = "cancelRepair")
    TicketView cancel(@PathParam("ticketId") String ticketId, @Valid CancellationRequest request) {
        return TicketView.from(Fluxzero.sendCommandAndWait(
                new CancelRepair(new TicketId(ticketId), request.reason())));
    }

    public record RegisterTicketRequest(
            @ApiDoc(required = true) @NotBlank String ticketId,
            @ApiDoc(required = true) @NotBlank String itemName,
            @ApiDoc(required = true) @NotBlank String problemDescription,
            @ApiDoc(required = true) @NotNull Priority priority) {
    }

    public record ResolutionRequest(
            @ApiDoc(required = true) @NotBlank String resolutionSummary) {
    }

    public record CancellationRequest(
            @ApiDoc(required = true) @NotBlank String reason) {
    }
}
