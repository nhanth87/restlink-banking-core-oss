package et.restlink.ra.fineract.client;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

/**
 * Lab-only TLS escape hatch. Prod must never enable {@code insecure-tls} without the explicit
 * lab flag — both default false so a prod JAR does not trust-all unless an operator opts in twice.
 */
public final class FineractTls {

    private FineractTls() {}

    /**
     * @return a trust-all SSL context for lab Fineract images
     * @throws IllegalStateException if {@code allowInsecureTlsLab} is false
     */
    public static SSLContext requireLabTrustAll(boolean allowInsecureTlsLab) {
        if (!allowInsecureTlsLab) {
            throw new IllegalStateException(
                    "insecure-tls requires restlink.fineract.allow-insecure-tls-lab=true (lab only; never prod)");
        }
        return trustAllContext();
    }

    static SSLContext trustAllContext() {
        try {
            TrustManager[] trustAll = {new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
                @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new java.security.SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("insecure TLS context init failed", e);
        }
    }
}
