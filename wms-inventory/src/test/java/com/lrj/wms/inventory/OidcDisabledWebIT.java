package com.lrj.wms.inventory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** issuer 为空时业务接口拒绝；不得免认证回退。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OidcDisabledWebIT {
    @LocalServerPort
    private int port;

    @Test
    void healthIsUpAndBusinessIsDenied() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> health = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health/liveness")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode());
        HttpResponse<String> readiness = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/actuator/health/readiness")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(503, readiness.statusCode());
        HttpResponse<String> warehouses = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/wms/v1/warehouses")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, warehouses.statusCode());
    }
}
