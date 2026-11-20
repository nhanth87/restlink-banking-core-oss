package et.restlink.spi;

/**
 * Which external core-banking engine backs a tenant. Chosen by config/profile,
 * never hard-wired in an SBB. Both are reached only through an RA adaptor.
 */
public enum CoreProvider {
    /**
     * Phase 1 native double-entry wallet ledger owned by restlink-app (Postgres). No external engine —
     * lets wallet/payments ship before Phase 2 core banking exists.
     */
    WALLET_LEDGER,
    /** Phase 2 — Apache Fineract (OSS) via REST — {@code ra-fineract}. */
    FINERACT,
    /** Phase 2 — Temenos T24 / Transact (licensed) via OFS/IRIS/TAFJ — {@code ra-temenos}. */
    TEMENOS
}
