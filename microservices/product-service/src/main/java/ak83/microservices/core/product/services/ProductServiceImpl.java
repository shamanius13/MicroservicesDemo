package ak83.microservices.core.product.services;

import ak83.microservices.core.product.persistence.ProductEntity;
import ak83.microservices.core.product.persistence.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.RestController;
import ak83.api.core.product.Product;
import ak83.api.core.product.ProductService;
import ak83.api.exceptions.InvalidInputException;
import ak83.api.exceptions.NotFoundException;
import ak83.util.http.ServiceUtil;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Random;

import static java.util.logging.Level.FINE;

@RestController
public class ProductServiceImpl implements ProductService {

  private static final Logger LOG = LoggerFactory.getLogger(ProductServiceImpl.class);

  private final ServiceUtil serviceUtil;
  private final ProductRepository repository;
  private final ProductMapper mapper;

  public ProductServiceImpl(ProductRepository repository, ProductMapper mapper, ServiceUtil serviceUtil) {
    this.repository = repository;
    this.mapper = mapper;
    this.serviceUtil = serviceUtil;
  }

  @Override
  public Mono<Product> createProduct(Product body) {

    if (body.productId() < 1) {
      throw new InvalidInputException("Invalid productId: " + body.productId());
    }

    ProductEntity entity = mapper.apiToEntity(body);

    return repository.save(entity)
        .log(LOG.getName(), FINE)
        .onErrorMap(
            DuplicateKeyException.class,
            ex -> new InvalidInputException("Duplicate key, Product Id: " + body.productId()))
        .map(mapper::entityToApi);
  }

  @Override
  public Mono<Product> getProduct(int productId, int delay, int faultPercent) {

    if (productId < 1) {
      throw new InvalidInputException("Invalid productId: " + productId);
    }

    LOG.info("Will get product info for id={}", productId);

    return repository.findByProductId(productId)
        .map(e -> throwErrorIfBadLuck(e, faultPercent))
        .delayElement(Duration.ofSeconds(delay))
        .switchIfEmpty(Mono.error(new NotFoundException("No product found for productId: " + productId)))
        .log(LOG.getName(), FINE)
        .map(mapper::entityToApi)
        .map(this::setServiceAddress);
  }

  @Override
  public Mono<Void> deleteProduct(int productId) {

    if (productId < 1) {
      throw new InvalidInputException("Invalid productId: " + productId);
    }

    LOG.debug("deleteProduct: tries to delete an entity with productId: {}", productId);
    return repository.findByProductId(productId).log(LOG.getName(), FINE).map(repository::delete).flatMap(e -> e);
  }

  private Product setServiceAddress(Product e) {
    return new Product(e.productId(), e.name(), e.weight(), serviceUtil.getServiceAddress());
  }


  private ProductEntity throwErrorIfBadLuck(ProductEntity entity, int faultPercent) {

    if (faultPercent == 0) {
      return entity;
    }

    int randomThreshold = getRandomNumber(1, 100);

    if (faultPercent < randomThreshold) {
      LOG.debug("We got lucky, no error occurred, {} < {}", faultPercent, randomThreshold);
    } else {
      LOG.info("Bad luck, an error occurred, {} >= {}", faultPercent, randomThreshold);
      throw new RuntimeException("Something went wrong...");
    }

    return entity;
  }

  private final Random randomNumberGenerator = new Random();

  private int getRandomNumber(int min, int max) {

    if (max < min) {
      throw new IllegalArgumentException("Max must be greater than min");
    }

    return randomNumberGenerator.nextInt((max - min) + 1) + min;
  }

}
