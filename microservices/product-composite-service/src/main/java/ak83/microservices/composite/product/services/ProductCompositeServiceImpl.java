package ak83.microservices.composite.product.services;

import static java.util.logging.Level.FINE;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import ak83.microservices.composite.product.services.tracing.ObservationUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import ak83.api.composite.product.*;
import ak83.api.core.product.Product;
import ak83.api.core.recommendation.Recommendation;
import ak83.api.core.review.Review;
import ak83.util.http.ServiceUtil;

@RestController
public class ProductCompositeServiceImpl implements ProductCompositeService {

  private static final Logger LOG = LoggerFactory.getLogger(ProductCompositeServiceImpl.class);

  private final SecurityContext nullSecCtx = new SecurityContextImpl();

  private final ServiceUtil serviceUtil;
  private final ObservationUtil observationUtil;
  private final ProductCompositeIntegration integration;

  public ProductCompositeServiceImpl(ServiceUtil serviceUtil, ObservationUtil observationUtil, ProductCompositeIntegration integration) {
    this.serviceUtil = serviceUtil;
    this.observationUtil = observationUtil;
    this.integration = integration;
  }

  @Override
  public Mono<Void> createProduct(ProductAggregate body) {
    return observationWithProductInfo(body.productId(), () -> createProductInternal(body));
  }

  private Mono<Void> createProductInternal(ProductAggregate body) {
    try {

      List<Mono> monoList = new ArrayList<>();

      monoList.add(getLogAuthorizationInfoMono());

      LOG.info("Will create a new composite entity for product.id: {}", body.productId());

      Product product = new Product(body.productId(), body.name(), body.weight(), null);
      monoList.add(integration.createProduct(product));

      if (body.recommendations() != null) {
        body.recommendations().forEach(r -> {
          Recommendation recommendation = new Recommendation(body.productId(), r.recommendationId(), r.author(), r.rate(), r.content(), null);
          monoList.add(integration.createRecommendation(recommendation));
        });
      }

      if (body.reviews() != null) {
        body.reviews().forEach(r -> {
          Review review = new Review(body.productId(), r.reviewId(), r.author(), r.subject(), r.content(), null);
          monoList.add(integration.createReview(review));
        });
      }

      LOG.debug("createCompositeProduct: composite entities created for productId: {}", body.productId());

      return Mono.zip(r -> "", monoList.toArray(new Mono[0]))
          .doOnError(ex -> LOG.warn("createCompositeProduct failed: {}", ex.toString()))
          .then();

    } catch (RuntimeException re) {
      LOG.warn("createCompositeProduct failed: {}", re.toString());
      throw re;
    }
  }

  @Override
  public Mono<ProductAggregate> getProduct(int productId, int delay, int faultPercent) {
    return observationWithProductInfo(productId, () -> getProductInternal(productId, delay, faultPercent));
  }

  @SuppressWarnings("unchecked")
  private Mono<ProductAggregate> getProductInternal(int productId, int delay, int faultPercent) {
    LOG.info("Will get composite product info for product.id={}", productId);
    return Mono.zip(
            values -> createProductAggregate((SecurityContext) values[0], (Product) values[1],
                (List<Recommendation>) values[2], (List<Review>) values[3], serviceUtil.getServiceAddress()),
            getSecurityContextMono(),
            integration.getProduct(productId, delay, faultPercent),
            integration.getRecommendations(productId).collectList(),
            integration.getReviews(productId).collectList())
        .doOnError(ex -> LOG.warn("getCompositeProduct failed: {}", ex.toString()))
        .log(LOG.getName(), FINE);
  }

  @Override
  public Mono<Void> deleteProduct(int productId) {
    return observationWithProductInfo(productId, () -> deleteProductInternal(productId));
  }

  private Mono<Void> deleteProductInternal(int productId) {
    try {

      LOG.info("Will delete a product aggregate for product.id: {}", productId);

      return Mono.zip(
              r -> "",
              getLogAuthorizationInfoMono(),
              integration.deleteProduct(productId),
              integration.deleteRecommendations(productId),
              integration.deleteReviews(productId))
          .doOnError(ex -> LOG.warn("delete failed: {}", ex.toString()))
          .log(LOG.getName(), FINE).then();

    } catch (RuntimeException re) {
      LOG.warn("deleteCompositeProduct failed: {}", re.toString());
      throw re;
    }
  }

  private <T> T observationWithProductInfo(int productInfo, Supplier<T> supplier) {
    return observationUtil.observe(
        "composite observation",
        "product info",
        "productId",
        String.valueOf(productInfo),
        supplier);
  }

  private ProductAggregate createProductAggregate(
      SecurityContext sc, Product product, List<Recommendation> recommendations, List<Review> reviews, String serviceAddress) {

    logAuthorizationInfo(sc);

    // 1. Setup product info
    int productId = product.productId();
    String name = product.name();
    int weight = product.weight();

    // 2. Copy summary recommendation info, if available
    List<RecommendationSummary> recommendationSummaries = (recommendations == null) ? null :
        recommendations.stream()
            .map(r -> new RecommendationSummary(r.recommendationId(), r.author(), r.rate(), r.content()))
            .collect(Collectors.toList());

    // 3. Copy summary review info, if available
    List<ReviewSummary> reviewSummaries = (reviews == null)  ? null :
        reviews.stream()
            .map(r -> new ReviewSummary(r.reviewId(), r.author(), r.subject(), r.content()))
            .collect(Collectors.toList());

    // 4. Create info regarding the involved microservices addresses
    String productAddress = product.serviceAddress();
    String reviewAddress = (reviews != null && !reviews.isEmpty()) ? reviews.getFirst().serviceAddress() : "";
    String recommendationAddress = (recommendations != null && !recommendations.isEmpty()) ? recommendations.getFirst().serviceAddress() : "";
    ServiceAddresses serviceAddresses = new ServiceAddresses(serviceAddress, productAddress, reviewAddress, recommendationAddress);

    return new ProductAggregate(productId, name, weight, recommendationSummaries, reviewSummaries, serviceAddresses);
  }

  private Mono<SecurityContext> getLogAuthorizationInfoMono() {
    return getSecurityContextMono().doOnNext(this::logAuthorizationInfo);
  }

  private Mono<SecurityContext> getSecurityContextMono() {
    return ReactiveSecurityContextHolder.getContext().defaultIfEmpty(nullSecCtx);
  }

  private void logAuthorizationInfo(SecurityContext sc) {
    if (sc != null && sc.getAuthentication() != null && sc.getAuthentication() instanceof JwtAuthenticationToken) {
      Jwt jwtToken = ((JwtAuthenticationToken)sc.getAuthentication()).getToken();
      logAuthorizationInfo(jwtToken);
    } else {
      LOG.warn("No JWT based Authentication supplied, running tests are we?");
    }
  }

  private void logAuthorizationInfo(Jwt jwt) {
    if (jwt == null) {
      LOG.warn("No JWT supplied, running tests are we?");
    } else {
      if (LOG.isDebugEnabled()) {
        URL issuer = jwt.getIssuer();
        List<String> audience = jwt.getAudience();
        Object subject = jwt.getClaims().get("sub");
        Object scopes = jwt.getClaims().get("scope");
        Object expires = jwt.getClaims().get("exp");

        LOG.debug("Authorization info: Subject: {}, scopes: {}, expires {}: issuer: {}, audience: {}", subject, scopes, expires, issuer, audience);
      }
    }
  }

}
