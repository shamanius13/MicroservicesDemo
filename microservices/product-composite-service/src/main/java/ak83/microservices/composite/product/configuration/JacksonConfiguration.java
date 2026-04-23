package ak83.microservices.composite.product.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class JacksonConfiguration {

  @Bean
  @Primary
  public JsonMapper objectMapper() {
    return JsonMapper.builder().configureForJackson2().build();
  }
}
