package et.restlink.spi.model;

/**
 * Read-only balance outcome. {@code TRANSIENT} is peer-down (HTTP 503); {@code NOT_FOUND} is a
 * missing account (404); {@code UNSUPPORTED} is a scaffold provider (501).
 */
public record BalanceQuery(Kind kind, AccountBalance snapshot, String message) {

    public enum Kind { FOUND, NOT_FOUND, TRANSIENT, UNSUPPORTED }

    public static BalanceQuery found(AccountBalance snapshot) {
        return new BalanceQuery(Kind.FOUND, snapshot, null);
    }

    public static BalanceQuery notFound(String message) {
        return new BalanceQuery(Kind.NOT_FOUND, null, message);
    }

    public static BalanceQuery transientFailure(String message) {
        return new BalanceQuery(Kind.TRANSIENT, null, message);
    }

    public static BalanceQuery unsupported(String message) {
        return new BalanceQuery(Kind.UNSUPPORTED, null, message);
    }
}
