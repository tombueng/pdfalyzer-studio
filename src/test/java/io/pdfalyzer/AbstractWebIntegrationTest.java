package io.pdfalyzer;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;

/**
 * Base class for full-stack web integration tests.
 *
 * <p>Spring Boot 4 removed {@code TestRestTemplate}. This base provides an
 * equivalent {@link RestTemplate}: it resolves relative paths against the
 * random server port and, like {@code TestRestTemplate}, never throws on a
 * non-2xx status so tests can assert on error responses directly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractWebIntegrationTest {

    @LocalServerPort
    protected int port;

    protected RestTemplate restTemplate;

    @BeforeEach
    void initRestTemplate() {
        RestTemplate template = new RestTemplate();
        template.setUriTemplateHandler(new DefaultUriBuilderFactory("http://localhost:" + port));
        template.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
        this.restTemplate = template;
    }
}
