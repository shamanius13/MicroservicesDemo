package ak83.api.core.recommendation;

public record Recommendation(int productId, int recommendationId, String author, int rate, String content,
                             String serviceAddress) {

}
