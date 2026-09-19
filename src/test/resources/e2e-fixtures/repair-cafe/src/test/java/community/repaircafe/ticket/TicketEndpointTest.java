package community.repaircafe.ticket;

import community.repaircafe.ticket.TicketEndpoint.CancellationRequest;
import community.repaircafe.ticket.TicketEndpoint.RegisterTicketRequest;
import community.repaircafe.ticket.TicketEndpoint.ResolutionRequest;
import community.repaircafe.ticket.api.Priority;
import community.repaircafe.ticket.api.TicketStatus;
import community.repaircafe.ticket.api.model.TicketView;
import community.repaircafe.ticket.api.model.WorkQueue;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.IllegalCommandException;
import io.fluxzero.sdk.tracking.handling.validation.ValidationException;
import org.junit.jupiter.api.Test;

class TicketEndpointTest {
    private final RegisterTicketRequest request = new RegisterTicketRequest(
            "RC-201", "Coffee grinder", "Motor hums but does not turn", Priority.HIGH);

    @Test
    void routesRegistrationExactLookupAndFilteredQueue() {
        TestFixture.create(new TicketEndpoint())
                .whenPost("/api/tickets", request)
                .expectResult((TicketView ticket) -> ticket.ticketId().equals("RC-201")
                        && ticket.status() == TicketStatus.OPEN)
                .andThen()
                .whenGet("/api/tickets/RC-201")
                .expectResult((TicketView ticket) -> ticket.itemName().equals("Coffee grinder"))
                .andThen()
                .whenGet("/api/tickets?status=OPEN")
                .expectResult((WorkQueue queue) -> queue.total() == 1 && queue.items().size() == 1);
    }

    @Test
    void routesStartAndCompleteOperations() {
        TestFixture.create(new TicketEndpoint()).givenPost("/api/tickets", request)
                .whenPost("/api/tickets/RC-201/start", null)
                .expectResult((TicketView ticket) -> ticket.status() == TicketStatus.IN_PROGRESS)
                .andThen()
                .whenPost("/api/tickets/RC-201/complete", new ResolutionRequest("Replaced worn brushes"))
                .expectResult((TicketView ticket) -> ticket.status() == TicketStatus.COMPLETED
                        && "Replaced worn brushes".equals(ticket.resolutionSummary()));
    }

    @Test
    void routesCancellationOperation() {
        TestFixture.create(new TicketEndpoint()).givenPost("/api/tickets", request)
                .whenPost("/api/tickets/RC-201/cancel", new CancellationRequest("Visitor collected item"))
                .expectResult((TicketView ticket) -> ticket.status() == TicketStatus.CANCELLED
                        && "Visitor collected item".equals(ticket.cancellationReason()));
    }

    @Test
    void routedBoundaryRejectsInvalidDuplicateAndUnknownRequests() {
        TestFixture.create(new TicketEndpoint())
                .whenPost("/api/tickets", new RegisterTicketRequest(" ", "Lamp", "Broken switch", Priority.NORMAL))
                .expectExceptionalResult(ValidationException.class)
                .andThen()
                .givenPost("/api/tickets", request)
                .whenPost("/api/tickets", request)
                .expectExceptionalResult(Entity.ALREADY_EXISTS_EXCEPTION);

        TestFixture.create(new TicketEndpoint())
                .whenPost("/api/tickets/missing/start", null)
                .expectExceptionalResult(IllegalCommandException.class);
    }

    @Test
    void servesApiDiscoveryForEveryBrowserOperation() {
        TestFixture.create(new TicketEndpoint()).whenGet("/api/openapi.json")
                .expectWebResult(response -> {
                    String document = response.getPayloadAs(String.class);
                    return response.getStatus() == 200
                            && document.contains("registerTicket")
                            && document.contains("getWorkQueue")
                            && document.contains("getTicket")
                            && document.contains("startRepair")
                            && document.contains("completeRepair")
                            && document.contains("cancelRepair");
                });
    }
}
