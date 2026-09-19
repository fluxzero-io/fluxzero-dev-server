package community.repaircafe.ticket.api;

public enum Priority {
    HIGH(0), NORMAL(1), LOW(2);

    private final int rank;

    Priority(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }
}
