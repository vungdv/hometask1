package vn.danang.polaris.web;

import java.time.Clock;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.danang.polaris.dto.ProductResponse;
import vn.danang.polaris.repository.ProductRepository;

@RestController
@RequestMapping("/api/v1/products")

public class ProductController {
    
    private final Clock clock;
    private final ProductRepository productRepository;
    
    public ProductController(Clock clock, ProductRepository productRepository){
        this.clock = clock;
        this.productRepository = productRepository;
    }

   @GetMapping()
    public Page<ProductResponse> list(
            @PageableDefault(page = 0, size = 20, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {

        return productRepository.findAll(pageable)
                .map(ProductResponse::getProduct);
    }

    @GetMapping("{id}")
    public ProductResponse getProduct(@PathVariable UUID id){
        return ProductResponse.getProductDefault(clock);
    }
}
