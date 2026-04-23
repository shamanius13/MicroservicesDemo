package ak83.springcloud.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT,
    classes = {TestSecurityConfig.class},
    properties = {
     //   "spring.security.oauth2.resourceserver.jwt.issuer-uri=",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.cloud.config.enabled=false"})
class GatewayApplicationTests {

  @Test
  void contextLoads() {
  }

}
