package ak83.microservices.core.review;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import ak83.api.core.recommendation.Recommendation;
import ak83.microservices.core.review.persistence.RecommendationEntity;
import ak83.microservices.core.review.services.RecommendationMapper;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MapperTests {

  private final RecommendationMapper mapper = Mappers.getMapper(RecommendationMapper.class);

  @Test
  void mapperTests() {

    assertNotNull(mapper);

    Recommendation api = new Recommendation(1, 2, "a", 4, "C", "adr");

    RecommendationEntity entity = mapper.apiToEntity(api);

    assertEquals(api.productId(), entity.getProductId());
    assertEquals(api.recommendationId(), entity.getRecommendationId());
    assertEquals(api.author(), entity.getAuthor());
    assertEquals(api.rate(), entity.getRating());
    assertEquals(api.content(), entity.getContent());

    Recommendation api2 = mapper.entityToApi(entity);

    assertEquals(api.productId(), api2.productId());
    assertEquals(api.recommendationId(), api2.recommendationId());
    assertEquals(api.author(), api2.author());
    assertEquals(api.rate(), api2.rate());
    assertEquals(api.content(), api2.content());
    assertNull(api2.serviceAddress());
  }

  @Test
  void mapperListTests() {

    assertNotNull(mapper);

    Recommendation api = new Recommendation(1, 2, "a", 4, "C", "adr");
    List<Recommendation> apiList = Collections.singletonList(api);

    List<RecommendationEntity> entityList = mapper.apiListToEntityList(apiList);
    assertEquals(apiList.size(), entityList.size());

    RecommendationEntity entity = entityList.getFirst();

    assertEquals(api.productId(), entity.getProductId());
    assertEquals(api.recommendationId(), entity.getRecommendationId());
    assertEquals(api.author(), entity.getAuthor());
    assertEquals(api.rate(), entity.getRating());
    assertEquals(api.content(), entity.getContent());

    List<Recommendation> api2List = mapper.entityListToApiList(entityList);
    assertEquals(apiList.size(), api2List.size());

    Recommendation api2 = api2List.getFirst();

    assertEquals(api.productId(), api2.productId());
    assertEquals(api.recommendationId(), api2.recommendationId());
    assertEquals(api.author(), api2.author());
    assertEquals(api.rate(), api2.rate());
    assertEquals(api.content(), api2.content());
    assertNull(api2.serviceAddress());
  }
}
