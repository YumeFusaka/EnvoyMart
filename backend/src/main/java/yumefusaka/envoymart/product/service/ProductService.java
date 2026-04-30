package yumefusaka.envoymart.product.service;

import yumefusaka.envoymart.product.model.entity.ProductEntity;
import yumefusaka.envoymart.product.model.response.ProductResponse;

import java.util.List;

public interface ProductService {

    List<ProductResponse> listProducts(String keyword, String category);

    ProductResponse getProduct(Long id);

    List<ProductResponse> recommendProducts(String query, int limit);

    ProductEntity requireEntity(Long id);

    void decreaseStock(Long productId, int quantity);
}
