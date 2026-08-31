package vn.danang.polaris.controller;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.danang.polaris.dto.ProductResponse;

@RestController
@RequestMapping("/api/v1/products")

public class ProductController {
    
    private final Clock clock;
    public ProductController(Clock clock){
        this.clock = clock;
    }

    @GetMapping()
    public List<ProductResponse> list(@PageableDefault(page=0, size=20, sort="id", direction=Sort.Direction.ASC) Pageable pageable) {
        int size = pageable.getPageSize();
        return IntStream.range(0, size)
            .mapToObj(i -> ProductResponse.getProductDefault(clock))
            .collect(Collectors.toList());
    }

    @GetMapping("{id}")
    public ProductResponse getProduct(@PathVariable UUID id){
        return ProductResponse.getProductDefault(clock);
    }
}
