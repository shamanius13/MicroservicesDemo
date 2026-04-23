package ak83.microservices.core.review.services;

import ak83.microservices.core.review.persistence.ReviewEntity;
import ak83.microservices.core.review.persistence.ReviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.RestController;
import ak83.api.core.review.Review;
import ak83.api.core.review.ReviewService;
import ak83.api.exceptions.InvalidInputException;
import ak83.util.http.ServiceUtil;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import java.util.List;

import static java.util.logging.Level.FINE;

@RestController
public class ReviewServiceImpl implements ReviewService {

  private static final Logger LOG = LoggerFactory.getLogger(ReviewServiceImpl.class);

  private final ReviewRepository repository;
  private final ReviewMapper mapper;
  private final ServiceUtil serviceUtil;

  private final Scheduler jdbcScheduler;

  public ReviewServiceImpl(@Qualifier("jdbcScheduler") Scheduler jdbcScheduler, ReviewRepository repository, ReviewMapper mapper, ServiceUtil serviceUtil) {
    this.jdbcScheduler = jdbcScheduler;
    this.repository = repository;
    this.mapper = mapper;
    this.serviceUtil = serviceUtil;
  }

  @Override
  public Mono<Review> createReview(Review body) {

    if (body.productId() < 1) {
      throw new InvalidInputException("Invalid productId: " + body.productId());
    }
    return Mono.fromCallable(() -> internalCreateReview(body))
        .subscribeOn(jdbcScheduler);
  }

  private Review internalCreateReview(Review body) {
    try {
      ReviewEntity entity = mapper.apiToEntity(body);
      ReviewEntity newEntity = repository.save(entity);

      LOG.debug("createReview: created a review entity: {}/{}", body.productId(), body.reviewId());
      return mapper.entityToApi(newEntity);

    } catch (DataIntegrityViolationException dive) {
      throw new InvalidInputException("Duplicate key, Product Id: " + body.productId() + ", Review Id:" + body.reviewId());
    }
  }

  @Override
  public Flux<Review> getReviews(int productId) {

    if (productId < 1) {
      throw new InvalidInputException("Invalid productId: " + productId);
    }

    LOG.info("Will get reviews for product with id={}", productId);

    return Mono.fromCallable(() -> internalGetReviews(productId))
        .flatMapMany(Flux::fromIterable)
        .log(LOG.getName(), FINE)
        .subscribeOn(jdbcScheduler);
  }

  private List<Review> internalGetReviews(int productId) {

    List<ReviewEntity> entityList = repository.findByProductId(productId);
    List<Review> list = mapper.entityListToApiList(entityList).stream().map(this::setServiceAddress).toList();

    LOG.debug("Response size: {}", list.size());

    return list;
  }

  private Review setServiceAddress(Review e) {
    return new Review(e.productId(), e.reviewId(), e.author(), e.subject(), e.content(), serviceUtil.getServiceAddress());
  }

  @Override
  public Mono<Void> deleteReviews(int productId) {

    if (productId < 1) {
      throw new InvalidInputException("Invalid productId: " + productId);
    }

    return Mono.fromRunnable(() -> internalDeleteReviews(productId)).subscribeOn(jdbcScheduler).then();
  }

  private void internalDeleteReviews(int productId) {

    LOG.debug("deleteReviews: tries to delete reviews for the product with productId: {}", productId);

    repository.deleteAll(repository.findByProductId(productId));
  }

}
