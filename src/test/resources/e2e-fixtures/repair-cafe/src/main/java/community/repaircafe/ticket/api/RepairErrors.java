package community.repaircafe.ticket.api;

import io.fluxzero.sdk.tracking.handling.IllegalCommandException;

public interface RepairErrors {
    IllegalCommandException ticketNotFound = new IllegalCommandException("Repair ticket does not exist");
    IllegalCommandException repairNotOpen = new IllegalCommandException("Only an open repair can be started");
    IllegalCommandException repairNotInProgress = new IllegalCommandException(
            "Only an in-progress repair can be completed");
    IllegalCommandException repairCannotBeCancelled = new IllegalCommandException(
            "Only an open or in-progress repair can be cancelled");
}
