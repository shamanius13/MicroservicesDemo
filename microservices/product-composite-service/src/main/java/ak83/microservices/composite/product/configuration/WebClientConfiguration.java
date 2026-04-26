package ak83.microservices.composite.product.configuration;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Collections;

@Configuration
public class WebClientConfiguration {

  @Bean
  public WebClient getWebClient(ObservationRegistry observationRegistry) {
    return WebClient.builder()
        .observationRegistry(observationRegistry)
        .baseUrl("http://localhost")
        .defaultCookie("cookieKey", "cookieValue")
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .defaultUriVariables(Collections.singletonMap("url", "http://localhost"))
        .build();
  }
}
