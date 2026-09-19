package community.repaircafe.ticket;

import community.repaircafe.ticket.api.*;
import community.repaircafe.ticket.api.model.RepairTicket;
import community.repaircafe.ticket.api.model.TicketHistoryEntry;
import community.repaircafe.ticket.api.model.TicketView;
import community.repaircafe.ticket.api.model.WorkQueue;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.IllegalCommandException;
import io.fluxzero.sdk.tracking.handling.validation.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static community.repaircafe.ticket.api.Priority.*;
import static community.repaircafe.ticket.api.TicketStatus.*;

class RepairTicketTest {
    private final TicketId id = new TicketId("RC-101");

    @Test
    void registerTicketAndRejectEveryBlankRequiredField() {
        TestFixture.create().whenCommand(register(id, "Toaster", "Trips the breaker", NORMAL))
                .expectResult((RepairTicket ticket) -> ticket.status() == OPEN && ticket.history().size() == 1)
                .expectOnlyEvents(RegisterTicket.class);

        expectValidation(register(new TicketId(" "), "Toaster", "Trips the breaker", NORMAL));
        expectValidation(register(id, " ", "Trips the breaker", NORMAL));
        expectValidation(register(id, "Toaster", " ", NORMAL));
        expectValidation(register(id, "Toaster", "Trips the breaker", null));
    }

    @Test
    void duplicateIdentifierIsRejectedWithoutReplacingTheTicket() {
        TestFixture.create()
                .givenCommands(register(id, "Toaster", "Original problem", LOW))
                .whenCommand(register(id, "Lamp", "Replacement attempt", HIGH))
                .expectExceptionalResult(Entity.ALREADY_EXISTS_EXCEPTION)
                .expectNoEvents()
                .andThen()
                .whenQuery(new GetTicket(id))
                .expectResult((TicketView ticket) -> ticket.itemName().equals("Toaster")
                        && ticket.problemDescription().equals("Original problem") && ticket.priority() == LOW);
    }

    @Test
    void supportsEveryValidLifecycleTransitionAndHistory() {
        TestFixture.create()
                .givenCommands(register(id, "Radio", "No sound", HIGH))
                .whenCommand(new StartRepair(id))
                .expectResult((RepairTicket ticket) -> ticket.status() == IN_PROGRESS)
                .expectOnlyEvents(StartRepair.class)
                .andThen()
                .whenCommand(new CompleteRepair(id, "Replaced a loose wire"))
                .expectResult((RepairTicket ticket) -> ticket.status() == COMPLETED
                        && "Replaced a loose wire".equals(ticket.resolutionSummary())
                        && ticket.history().stream().map(TicketHistoryEntry::action).toList()
                        .equals(List.of(LifecycleAction.REGISTERED, LifecycleAction.STARTED, LifecycleAction.COMPLETED)))
                .expectOnlyEvents(CompleteRepair.class);

        TestFixture.create().givenCommands(register(id, "Radio", "No sound", NORMAL))
                .whenCommand(new CancelRepair(id, "Visitor withdrew it"))
                .expectResult((RepairTicket ticket) -> ticket.status() == CANCELLED
                        && "Visitor withdrew it".equals(ticket.cancellationReason()));

        TestFixture.create().givenCommands(register(id, "Radio", "No sound", NORMAL), new StartRepair(id))
                .whenCommand(new CancelRepair(id, "Parts unavailable"))
                .expectResult((RepairTicket ticket) -> ticket.status() == CANCELLED);
    }

    @Test
    void rejectsBlankCompletionAndCancellationDetails() {
        TestFixture.create().givenCommands(register(id, "Radio", "No sound", NORMAL), new StartRepair(id))
                .whenCommand(new CompleteRepair(id, " "))
                .expectExceptionalResult(ValidationException.class)
                .expectNoEvents();
        TestFixture.create().givenCommands(register(id, "Radio", "No sound", NORMAL))
                .whenCommand(new CancelRepair(id, " "))
                .expectExceptionalResult(ValidationException.class)
                .expectNoEvents();
    }

    @Test
    void rejectsEveryRepeatedOutOfOrderAndPostTerminalTransitionWithoutMutation() {
        assertRejectedState(setupFor(OPEN), new CompleteRepair(id, "Too early"), OPEN, 1);

        assertRejectedState(setupFor(IN_PROGRESS), new StartRepair(id), IN_PROGRESS, 2);

        assertRejectedState(setupFor(COMPLETED), new StartRepair(id), COMPLETED, 3);
        assertRejectedState(setupFor(COMPLETED), new CompleteRepair(id, "Repeated"), COMPLETED, 3);
        assertRejectedState(setupFor(COMPLETED), new CancelRepair(id, "Too late"), COMPLETED, 3);

        assertRejectedState(setupFor(CANCELLED), new StartRepair(id), CANCELLED, 2);
        assertRejectedState(setupFor(CANCELLED), new CompleteRepair(id, "Too late"), CANCELLED, 2);
        assertRejectedState(setupFor(CANCELLED), new CancelRepair(id, "Repeated"), CANCELLED, 2);
    }

    @Test
    void unknownTicketActionsRejectAndNeverCreateState() {
        for (Object action : List.of(new StartRepair(id), new CompleteRepair(id, "Fixed"),
                new CancelRepair(id, "No owner"))) {
            TestFixture.create().whenCommand(action)
                    .expectExceptionalResult(IllegalCommandException.class)
                    .expectNoEvents()
                    .andThen()
                    .whenQuery(new GetTicket(id))
                    .expectNoResult();
        }
    }

    @Test
    void exactLookupQueueFilterOrderingAndTotalsAreCorrect() {
        var highB = register(new TicketId("B-2"), "Blender", "Stuck", HIGH);
        var low = register(new TicketId("D-4"), "Clock", "Slow", LOW);
        var highA = register(new TicketId("A-1"), "Amplifier", "Buzzing", HIGH);
        var normal = register(new TicketId("C-3"), "Kettle", "Leaking", NORMAL);
        TestFixture.create().givenCommands(highB, low, highA, normal, new StartRepair(highB.ticketId()),
                        new CompleteRepair(highB.ticketId(), "Cleaned bearing"))
                .whenQuery(new GetTicket(highB.ticketId()))
                .expectResult((TicketView ticket) -> ticket.ticketId().equals("B-2") && ticket.status() == COMPLETED)
                .andThen()
                .whenQuery(new GetTicket(new TicketId("missing")))
                .expectNoResult()
                .andThen()
                .whenQuery(new GetWorkQueue(null))
                .expectResult((WorkQueue queue) -> queue.total() == 4
                        && queue.items().stream().map(TicketView::ticketId).toList()
                        .equals(List.of("A-1", "B-2", "C-3", "D-4")))
                .andThen()
                .whenQuery(new GetWorkQueue(COMPLETED))
                .expectResult((WorkQueue queue) -> queue.total() == 1
                        && queue.items().size() == 1 && queue.items().getFirst().ticketId().equals("B-2"));
    }

    @Test
    void reconstructsEveryLifecycleStateAndHistoryFromEvents() {
        assertReconstructed(OPEN, 1, register(id, "Radio", "No sound", NORMAL));
        assertReconstructed(IN_PROGRESS, 2, register(id, "Radio", "No sound", NORMAL), new StartRepair(id));
        assertReconstructed(COMPLETED, 3, register(id, "Radio", "No sound", NORMAL), new StartRepair(id),
                new CompleteRepair(id, "Fixed"));
        assertReconstructed(CANCELLED, 2, register(id, "Radio", "No sound", NORMAL),
                new CancelRepair(id, "Unrepairable"));
    }

    private void expectValidation(RegisterTicket command) {
        TestFixture.create().whenCommand(command)
                .expectExceptionalResult(ValidationException.class)
                .expectNoEvents();
    }

    private void assertRejectedState(List<Object> setup, Object rejected, TicketStatus status, int historySize) {
        TestFixture.create().givenCommands(setup.toArray())
                .whenCommand(rejected)
                .expectExceptionalResult(IllegalCommandException.class)
                .expectNoEvents()
                .andThen()
                .whenQuery(new GetTicket(id))
                .expectResult((TicketView ticket) -> ticket.status() == status && ticket.history().size() == historySize);
    }

    private void assertReconstructed(TicketStatus status, int historySize, Object... events) {
        TestFixture.create().givenAppliedEvents(id, events)
                .whenQuery(new GetTicket(id))
                .expectResult((TicketView ticket) -> ticket.status() == status && ticket.history().size() == historySize);
    }

    private List<Object> setupFor(TicketStatus status) {
        var registered = register(id, "Radio", "No sound", NORMAL);
        return switch (status) {
            case OPEN -> List.of(registered);
            case IN_PROGRESS -> List.of(registered, new StartRepair(id));
            case COMPLETED -> List.of(registered, new StartRepair(id), new CompleteRepair(id, "Fixed"));
            case CANCELLED -> List.of(registered, new CancelRepair(id, "Unrepairable"));
        };
    }

    private RegisterTicket register(TicketId ticketId, String item, String problem, Priority priority) {
        return new RegisterTicket(ticketId, item, problem, priority);
    }
}
