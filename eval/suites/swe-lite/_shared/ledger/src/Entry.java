package ledger;

public class Entry {

    private final String description;
    private final String category;
    private final long amountCents;

    public Entry(String description, String category, long amountCents) {
        this.description = description;
        this.category = category;
        this.amountCents = amountCents;
    }

    public String description() {
        return description;
    }

    public String category() {
        return category;
    }

    public long amountCents() {
        return amountCents;
    }
}
