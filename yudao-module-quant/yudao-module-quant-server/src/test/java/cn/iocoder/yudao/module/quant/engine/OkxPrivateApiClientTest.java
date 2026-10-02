package cn.iocoder.yudao.module.quant.engine;

import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

class OkxPrivateApiClientTest {
    @Test
    void retriesIdempotentPrivateReadAfterTransientTransportFailures() {
        QuantProperties properties = properties();
        QueueHttpClient http = new QueueHttpClient(new ConnectException("first"), new IOException("second"), okResponse());
        OkxPrivateApiClient client = new OkxPrivateApiClient(properties, credentials(), http);
        assertEquals("{\"code\":\"0\",\"data\":[]}", client.accountBalance());
        assertEquals(3, http.calls);
    }

    @Test
    void doesNotRetryOrderSubmissionAndReportsTransportType() {
        QuantProperties properties = properties();properties.setLiveExecutionEnabled(true);
        QueueHttpClient http = new QueueHttpClient(new ConnectException("submit failed"));
        OkxPrivateApiClient client = new OkxPrivateApiClient(properties, credentials(), http);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.placeSpotLimitOrder("order-1", "buy", "100", "0.01"));
        assertTrue(error.getMessage().contains("ConnectException"));
        assertTrue(error.getMessage().contains("已尝试 1 次"));
        assertEquals(1, http.calls);
    }

    private static QuantProperties properties() {
        QuantProperties properties = new QuantProperties();
        properties.setLivePrivateReadMaxAttempts(3);properties.setLivePrivateReadRetryDelayMillis(1);
        return properties;
    }
    private static LiveCredentialProvider credentials() {
        return new LiveCredentialProvider() {
            public Optional<OkxCredential> load() { return Optional.of(new OkxCredential("api-key", "secret", "passphrase")); }
            public boolean configured() { return true; }
        };
    }
    private static HttpResponse<String> okResponse() {
        return new HttpResponse<>() {
            public int statusCode() { return 200; }
            public HttpRequest request() { return null; }
            public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(java.util.Map.of(), (a, b) -> true); }
            public String body() { return "{\"code\":\"0\",\"data\":[]}"; }
            public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
            public URI uri() { return URI.create("https://openapi.okx.com"); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
        };
    }
    private static final class QueueHttpClient extends HttpClient {
        private final ArrayDeque<Object> outcomes = new ArrayDeque<>();private int calls;
        QueueHttpClient(Object... outcomes) { this.outcomes.addAll(java.util.List.of(outcomes)); }
        public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(1)); }
        public Redirect followRedirects() { return Redirect.NEVER; }
        public Optional<ProxySelector> proxy() { return Optional.empty(); }
        public SSLContext sslContext() { return null; }
        public SSLParameters sslParameters() { return new SSLParameters(); }
        public Optional<Authenticator> authenticator() { return Optional.empty(); }
        public Version version() { return Version.HTTP_2; }
        public Optional<Executor> executor() { return Optional.empty(); }
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
            calls++;Object outcome=outcomes.removeFirst();if(outcome instanceof IOException error)throw error;return (HttpResponse<T>)outcome;
        }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) { throw new UnsupportedOperationException(); }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) { throw new UnsupportedOperationException(); }
    }
}
