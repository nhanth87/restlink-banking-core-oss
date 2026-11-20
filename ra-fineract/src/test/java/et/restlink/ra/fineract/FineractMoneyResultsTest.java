package et.restlink.ra.fineract;

import et.restlink.ra.fineract.client.FineractRestClient.HttpResult;
import et.restlink.spi.model.BalanceQuery;
import et.restlink.spi.payment.MoneyOutcomeKind;
import et.restlink.spi.payment.MoneyStepResult;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FineractMoneyResultsTest {

    @Test
    void write2xxWithTxnIsCommitted() {
        MoneyStepResult r = FineractMoneyResults.ofWrite(
                new HttpResult(200, "{\"resourceId\":9}", false, null), "9");
        assertEquals(MoneyOutcomeKind.COMMITTED, r.kind());
        assertEquals("9", r.coreTxnId());
    }

    @Test
    void write4xxIsBusinessRejectNotPeerDown() {
        MoneyStepResult r = FineractMoneyResults.ofWrite(
                new HttpResult(400, "{\"errors\":[\"insufficient\"]}", false, null), null);
        assertEquals(MoneyOutcomeKind.BUSINESS_REJECT, r.kind());
    }

    @Test
    void writeTransportIsTransientForIdempotentRetry() {
        MoneyStepResult r = FineractMoneyResults.ofWrite(
                new HttpResult(0, null, true, "connect timeout"), null);
        assertEquals(MoneyOutcomeKind.TRANSIENT, r.kind());
    }

    @Test
    void write5xxIsTransient() {
        MoneyStepResult r = FineractMoneyResults.ofWrite(
                new HttpResult(503, "unavailable", false, null), null);
        assertEquals(MoneyOutcomeKind.TRANSIENT, r.kind());
    }

    @Test
    void undo404IsNoOp() {
        MoneyStepResult r = FineractMoneyResults.ofUndo(
                new HttpResult(404, "not found", false, null), "12");
        assertEquals(MoneyOutcomeKind.NO_OP, r.kind());
    }

    @Test
    void lookupFindsExternalId() {
        String body = """
                {"id":1,"transactions":[
                  {"id":10,"externalId":"other"},
                  {"id":11,"externalId":"step-key-1"}
                ]}
                """;
        assertEquals("11", FineractMoneyResults.findTxnIdByExternalId(body, "step-key-1").orElse(null));
        assertTrue(FineractMoneyResults.findTxnIdByExternalId(body, "missing").isEmpty());
    }

    @Test
    void balance2xxParsesSummary() {
        String body = """
                {"currency":{"code":"ETB"},"summary":{"accountBalance":"80.50","availableBalance":"75.00"}}
                """;
        BalanceQuery q = FineractMoneyResults.ofBalance(new HttpResult(200, body, false, null), "et", "SAV-1");
        assertEquals(BalanceQuery.Kind.FOUND, q.kind());
        assertEquals("75.00", q.snapshot().available().amount().toPlainString());
        assertEquals("80.50", q.snapshot().ledger().amount().toPlainString());
        assertEquals("ETB", q.snapshot().available().currency());
    }

    @Test
    void balance404IsNotFoundNotPeerDown() {
        BalanceQuery q = FineractMoneyResults.ofBalance(
                new HttpResult(404, "missing", false, null), "et", "SAV-x");
        assertEquals(BalanceQuery.Kind.NOT_FOUND, q.kind());
    }

    @Test
    void balance5xxIsTransient() {
        BalanceQuery q = FineractMoneyResults.ofBalance(
                new HttpResult(503, "down", false, null), "et", "SAV-1");
        assertEquals(BalanceQuery.Kind.TRANSIENT, q.kind());
    }
}
