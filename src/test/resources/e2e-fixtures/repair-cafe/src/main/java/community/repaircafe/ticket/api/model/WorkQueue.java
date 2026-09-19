package community.repaircafe.ticket.api.model;

import java.util.List;

public record WorkQueue(List<TicketView> items, long total) {
}
