package com.kg.predictions.kalshi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.PSSParameterSpec;
import java.util.Base64;
import java.util.Map;

/**
 * Builds the RSA-PSS signed headers Kalshi requires on the WebSocket handshake
 * (and any authenticated REST call). Credentials come from the environment and
 * are never logged or committed:
 *
 * <ul>
 *   <li>{@code KALSHI_API_KEY_ID}      – the API key UUID</li>
 *   <li>{@code KALSHI_PRIVATE_KEY_PATH} – path to the RSA private-key PEM file</li>
 * </ul>
 *
 * Signature = base64( RSA-PSS(SHA-256, MGF1-SHA256, salt=32)
 *                     over (timestampMs + METHOD + path) ).
 */
public final class KalshiAuth {

    private static final String KEY_ID_ENV = "KALSHI_API_KEY_ID";
    private static final String KEY_PATH_ENV = "KALSHI_PRIVATE_KEY_PATH";

    private final String keyId;
    private final PrivateKey privateKey;

    private KalshiAuth(String keyId, PrivateKey privateKey) {
        this.keyId = keyId;
        this.privateKey = privateKey;
    }

    /** True only when both env vars are present and non-blank. */
    public static boolean isConfigured() {
        return notBlank(System.getenv(KEY_ID_ENV)) && notBlank(System.getenv(KEY_PATH_ENV));
    }

    /** Load credentials from the environment. Caller should guard with {@link #isConfigured()}. */
    public static KalshiAuth fromEnv() throws IOException, GeneralSecurityExceptionWrapper {
        String keyId = System.getenv(KEY_ID_ENV);
        String keyPath = System.getenv(KEY_PATH_ENV);
        if (!notBlank(keyId) || !notBlank(keyPath)) {
            throw new IllegalStateException("Kalshi credentials not set (" + KEY_ID_ENV
                    + " / " + KEY_PATH_ENV + ")");
        }
        try {
            return new KalshiAuth(keyId.trim(), loadPrivateKey(Path.of(keyPath.trim())));
        } catch (Exception e) {
            throw new GeneralSecurityExceptionWrapper(
                    "Could not load Kalshi private key from " + keyPath + ": " + e.getMessage(), e);
        }
    }

    /**
     * Signed headers for a request. For the WebSocket handshake use
     * {@code headers("GET", "/trade-api/ws/v2")}.
     */
    public Map<String, String> headers(String method, String path) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String signature = sign(timestamp + method + path);
        return Map.of(
                "KALSHI-ACCESS-KEY", keyId,
                "KALSHI-ACCESS-SIGNATURE", signature,
                "KALSHI-ACCESS-TIMESTAMP", timestamp);
    }

    private String sign(String message) {
        try {
            Signature signer = Signature.getInstance("RSASSA-PSS");
            signer.setParameter(new PSSParameterSpec(
                    "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
            signer.initSign(privateKey);
            signer.update(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign Kalshi request", e);
        }
    }

    private static PrivateKey loadPrivateKey(Path pemPath) throws Exception {
        String pem = Files.readString(pemPath);
        String base64 = pem
                .replaceAll("-----BEGIN (RSA )?PRIVATE KEY-----", "")
                .replaceAll("-----END (RSA )?PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** Checked wrapper so callers can handle key-loading failure distinctly. */
    public static final class GeneralSecurityExceptionWrapper extends Exception {
        public GeneralSecurityExceptionWrapper(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
