package yumefusaka.envoymart.product.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.product.model.response.ProductResponse;
import yumefusaka.envoymart.product.service.ProductService;

import java.util.List;

@RestController
@RequestMapping("/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public Result<List<ProductResponse>> list(@RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String category) {
        return Result.success(productService.listProducts(keyword, category));
    }

    @GetMapping("/{id}")
    public Result<ProductResponse> detail(@PathVariable Long id) {
        return Result.success(productService.getProduct(id));
    }
}
