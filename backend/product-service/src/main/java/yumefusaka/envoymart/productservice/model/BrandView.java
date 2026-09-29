package yumefusaka.envoymart.productservice.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BrandView {

    private Long id;
    private String name;
    private String logo;
}
