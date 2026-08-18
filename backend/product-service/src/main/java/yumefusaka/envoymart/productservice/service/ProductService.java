package yumefusaka.envoymart.productservice.service;

import yumefusaka.envoymart.productservice.model.ProductResponse;
import yumefusaka.envoymart.productservice.model.StockDeductRequest;

import java.util.List;

public interface ProductService {

    List<ProductResponse> listProducts(String keyword, String category);

    ProductResponse getProduct(Long id);

    List<ProductResponse> recommendProducts(String query, int limit);

    void deductStock(StockDeductRequest request);
}
