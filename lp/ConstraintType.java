package lp;

public enum ConstraintType {
    LE("<="), GE(">="), EQ("=");
    private final String symbol;
    ConstraintType(String symbol) {this.symbol = symbol; }
    public String symbol() {return symbol; }
    public ConstraintType flipped() {
        return switch (this) {
            case LE -> GE;
            case GE -> LE;
            case EQ -> EQ;
        };
    }
}
