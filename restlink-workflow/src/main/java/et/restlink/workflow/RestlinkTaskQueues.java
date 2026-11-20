package et.restlink.workflow;

/** Task queues — all N workers must register the same workflow + activity types. */
public final class RestlinkTaskQueues {

    public static final String PAYMENT_WORKFLOW = "restlink-payment-wf";
    public static final String PAYMENT_ACTIVITY = "restlink-payment-act";

    private RestlinkTaskQueues() {}
}
