package ak83.microservices.composite.product.services;

import static java.util.logging.Level.FINE;
import static reactor.core.publisher.Flux.empty;
import static ak83.api.event.Event.Type.CREATE;
import static ak83.api.event.Event.Type.DELETE;

import ak83.util.http.ServiceUtil;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import ak83.api.core.product.Product;
import ak83.api.core.product.ProductService;
import ak83.api.core.recommendation.Recommendation;
import ak83.api.core.recommendation.RecommendationService;
import ak83.api.core.review.Review;
import ak83.api.core.review.ReviewService;
import ak83.api.event.Event;
import ak83.api.exceptions.InvalidInputException;
import ak83.api.exceptions.NotFoundException;
import ak83.util.http.HttpErrorInfo;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;

@Component
public class ProductCompositeIntegration implements ProductService, RecommendationService, ReviewService {

  private static final Logger LOG = LoggerFactory.getLogger(ProductCompositeIntegration.class);

  private final WebClient webClient;
  private final JsonMapper mapper;

  private final StreamBridge streamBridge;

  private final Scheduler publishEventScheduler;

  private static final String PRODUCT_SERVICE_URL = "http://product:8080";
  private static final String RECOMMENDATION_SERVICE_URL = "http://recommendation:8080";
  private static final String REVIEW_SERVICE_URL = "http://review:8080";

  private final ServiceUtil serviceUtil;

  public ProductCompositeIntegration(
      @Qualifier("publishEventScheduler") Scheduler publishEventScheduler,
      WebClient webClient,
      JsonMapper mapper,
      StreamBridge streamBridge,
      ServiceUtil serviceUtil) {

    this.publishEventScheduler = publishEventScheduler;
    this.webClient = webClient;
    this.mapper = mapper;
    this.streamBridge = streamBridge;
    this.serviceUtil = serviceUtil;
  }

  @Override
  public Mono<Product> createProduct(Product body) {

    return Mono.fromCallable(() -> {
      sendMessage("products-out-0", new Event<>(CREATE, body.productId(), body));
      return body;
    }).subscribeOn(publishEventScheduler);
  }

  @Override
  @Retry(name = "product")
  @TimeLimiter(name = "product")
  @CircuitBreaker(name = "product", fallbackMethod = "getProductFallbackValue")
  public Mono<Product> getProduct(int productId, int delay, int faultPercent) {

    URI url = UriComponentsBuilder.fromUriString(PRODUCT_SERVICE_URL
        + "/product/{productId}?delay={delay}&faultPercent={faultPercent}").build(productId, delay, faultPercent);
    LOG.info("Will call the getProduct API on URL: {}", url);

    return webClient.get().uri(url)
        .retrieve().bodyToMono(Product.class).log(LOG.getName(), FINE)
        .onErrorMap(WebClientResponseException.class, this::handleException);
  }

  private Mono<Product> getProductFallbackValue(int productId, int delay, int faultPercent, CallNotPermittedException ex) {

    LOG.warn("Creating a fail-fast fallback product for productId = {}, delay = {}, faultPercent = {} and exception = {} ",
        productId, delay, faultPercent, ex.toString());

    if (productId == 13) {
      String errMsg = "Product Id: " + productId + " not found in fallback cache!";
      LOG.warn(errMsg);
      throw new NotFoundException(errMsg);
    }

    return Mono.just(new Product(productId, "Fallback product" + productId, productId, serviceUtil.getServiceAddress()));
  }

  @Override
  public Mono<Void> deleteProduct(int productId) {

    return Mono.fromRunnable(() -> sendMessage("products-out-0", new Event<>(DELETE, productId, null)))
        .subscribeOn(publishEventScheduler).then();
  }

  @Override
  public Mono<Recommendation> createRecommendation(Recommendation body) {

    return Mono.fromCallable(() -> {
      sendMessage("recommendations-out-0", new Event<>(CREATE, body.productId(), body));
      return body;
    }).subscribeOn(publishEventScheduler);
  }

  @Override
  public Flux<Recommendation> getRecommendations(int productId) {

    URI url = UriComponentsBuilder.fromUriString(RECOMMENDATION_SERVICE_URL + "/recommendation?productId={productId}").build(productId);

    LOG.debug("Will call the getRecommendations API on URL: {}", url);

    // Return an empty result if something goes wrong to make it possible for the composite service to return partial responses
    return webClient.get().uri(url).retrieve().bodyToFlux(Recommendation.class).log(LOG.getName(), FINE).onErrorResume(error -> empty());
  }

  @Override
  public Mono<Void> deleteRecommendations(int productId) {

    return Mono.fromRunnable(() -> sendMessage("recommendations-out-0", new Event<>(DELETE, productId, null)))
        .subscribeOn(publishEventScheduler).then();
  }

  @Override
  public Mono<Review> createReview(Review body) {

    return Mono.fromCallable(() -> {
      sendMessage("reviews-out-0", new Event<>(CREATE, body.productId(), body));
      return body;
    }).subscribeOn(publishEventScheduler);
  }

  @Override
  public Flux<Review> getReviews(int productId) {

    URI url = UriComponentsBuilder.fromUriString(REVIEW_SERVICE_URL + "/review?productId={productId}").build(productId);

    LOG.debug("Will call the getReviews API on URL: {}", url);

    // Return an empty result if something goes wrong to make it possible for the composite service to return partial responses
    return webClient.get().uri(url).retrieve().bodyToFlux(Review.class).log(LOG.getName(), FINE).onErrorResume(error -> empty());
  }

  @Override
  public Mono<Void> deleteReviews(int productId) {

    return Mono.fromRunnable(() -> sendMessage("reviews-out-0", new Event<>(DELETE, productId, null)))
        .subscribeOn(publishEventScheduler).then();
  }

  private void sendMessage(String bindingName, Event<Integer, ?> event) {
    LOG.debug("Sending a {} message to {}", event.getEventType(), bindingName);
    streamBridge.send(bindingName, helper(event));
  }

  private <T> Message<Event<Integer, T>> helper(Event<Integer, T> event) {
    return MessageBuilder.withPayload(event)
        .setHeader("partitionKey", event.getKey())
        .build();
  }

  private Throwable handleException(Throwable ex) {

    if (!(ex instanceof WebClientResponseException wcre)) {
      LOG.warn("Got a unexpected error: {}, will rethrow it", ex.toString());
      return ex;
    }

    switch (HttpStatus.resolve(wcre.getStatusCode().value())) {

      case NOT_FOUND:
        return new NotFoundException(getErrorMessage(wcre));

      case UNPROCESSABLE_CONTENT:
        return new InvalidInputException(getErrorMessage(wcre));

      default:
        LOG.warn("Got an unexpected HTTP error: {}, will rethrow it", wcre.getStatusCode());
        LOG.warn("Error body: {}", wcre.getResponseBodyAsString());
        return ex;
    }
  }

  private String getErrorMessage(WebClientResponseException ex) {
    return mapper.readValue(ex.getResponseBodyAsString(), HttpErrorInfo.class).getMessage();
  }
}