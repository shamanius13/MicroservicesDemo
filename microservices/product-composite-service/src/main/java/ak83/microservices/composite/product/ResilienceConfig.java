package ak83.microservices.composite.product;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ResilienceConfig {

  private static final Logger LOG = LoggerFactory.getLogger(ResilienceConfig.class);

  public ResilienceConfig(
      @Value("${app.resilience.enabled:true}") String resilienceEnabledStr,
      CircuitBreakerRegistry circuitBreakerRegistry,
      TimeLimiterRegistry timeLimiterRegistry,
      RetryRegistry retryRegistry) {

    boolean resilienceEnabled = !("false".equalsIgnoreCase(resilienceEnabledStr));
    LOG.debug("Resilience4j is enabled: {} ({})", resilienceEnabled, resilienceEnabledStr);

    if (!resilienceEnabled) {
      LOG.warn("Resilience4j is disabled (\"app.resilience.enabled = {}\"). CircuitBreaker, TimeLimiter and Retry are not used.",
          resilienceEnabledStr);
      circuitBreakerRegistry.remove("product");
      retryRegistry.remove("product");
      timeLimiterRegistry.replace("product", io.github.resilience4j.timelimiter.TimeLimiter.of(Duration.ofHours(24)));
      LOG.warn("Instead of disabling the timelimiter, a long timeout of {} will be used",
          timeLimiterRegistry.find("product").get().getTimeLimiterConfig().getTimeoutDuration());
    }
  }
}
