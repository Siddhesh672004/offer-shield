package com.offershield.auth;

import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionCookieTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private Environment environment;

    @Test
    void attributesHttpOnlySecureLax() throws Exception {
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/oauth2/authorization/google")).GET().build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(response.headers().allValues("Set-Cookie"))
                .filteredOn(cookie -> cookie.startsWith("JSESSIONID="))
                .singleElement()
                .satisfies(cookie -> assertThat(cookie)
                        .containsIgnoringCase("HttpOnly")
                        .containsIgnoringCase("Secure")
                        .containsIgnoringCase("SameSite=Lax"));
    }

    @Test
    void sessionTimeout_fourHours() {
        assertThat(environment.getProperty("server.servlet.session.timeout", Duration.class)).isEqualTo(Duration.ofHours(4));
    }
}
