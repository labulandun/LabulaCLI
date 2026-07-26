package ledger;

public class Report {

    private final Ledger ledger;

    public Report(Ledger ledger) {
        this.ledger = ledger;
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        for (Entry entry : ledger.entries()) {
            sb.append(entry.description())
                    .append(" [").append(entry.category()).append("] ")
                    .append(MoneyFormat.format(entry.amountCents()))
                    .append('\n');
        }
        sb.append("TOTAL ").append(MoneyFormat.format(ledger.balanceCents()));
        return sb.toString();
    }
}
