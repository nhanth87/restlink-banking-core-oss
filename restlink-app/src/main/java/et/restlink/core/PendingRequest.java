package et.restlink.core;

import java.io.Serializable;

/**
 * Replicated in-flight request record (DIST_SYNC). Immutable; state transitions return copies so the
 * whole value is re-put into the cache (clean replication). Serializable for JavaSerializationMarshaller.
 *
 * <p>{@code requestId} correlates transport; {@code idempotencyKey} dedupes the money effect —
 * they are deliberately distinct (see 02-nn-ha.md).
 */
public record PendingRequest(
        String requestId,
        String tenantId,
        String commandType,
        String idempotencyKey,
        String provider,
        State state,
        String ownerNode,
        String resultJson,
        long createdMs
) implements Serializable {

    public enum State { SUBMITTED, COMPLETED, NEEDS_REVIEW, COMPENSATED }

    public static PendingRequest submitted(String requestId, String tenantId, String commandType,
                                           String idempotencyKey, String provider, String ownerNode) {
        return new PendingRequest(requestId, tenantId, commandType, idempotencyKey, provider,
                State.SUBMITTED, ownerNode, null, System.currentTimeMillis());
    }

    public PendingRequest completed(String result) {
        return new PendingRequest(requestId, tenantId, commandType, idempotencyKey, provider,
                State.COMPLETED, ownerNode, result, createdMs);
    }

    public PendingRequest withState(State next) {
        return new PendingRequest(requestId, tenantId, commandType, idempotencyKey, provider,
                next, ownerNode, resultJson, createdMs);
    }

    public PendingRequest adoptedBy(String node) {
        return new PendingRequest(requestId, tenantId, commandType, idempotencyKey, provider,
                state, node, resultJson, createdMs);
    }
}
