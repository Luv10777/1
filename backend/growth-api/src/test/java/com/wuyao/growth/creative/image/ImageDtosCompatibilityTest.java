package com.wuyao.growth.creative.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImageDtosCompatibilityTest {
    @Test
    void readsPlansSavedBeforeRefinementWasRemoved() throws Exception {
        String saved = """
            {"summary":"旧方案","question":"","visualDirection":"商品主图",
             "items":[{"role":"主图","prompt":"展示商品","headline":"","caption":"",
                       "refinement":{"prompt":"旧版精修提示词"}}]}
            """;

        ImageDtos.Plan plan = new ObjectMapper().readValue(saved, ImageDtos.Plan.class);

        assertThat(plan.items()).hasSize(1);
        assertThat(plan.items().getFirst().prompt()).isEqualTo("展示商品");
    }

    @Test
    void rejectsTheRemovedWhiteBackgroundImageType() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new ImageDtos.Create("test-request-123", "PRODUCT_SET", "商品主图",
                    List.of(), "1:1", "1K", 1, "TAOBAO", "WHITE_BG", "商品主图", "真实自然", null);

            assertThat(factory.getValidator().validate(request))
                    .anyMatch(violation -> "imageType".equals(violation.getPropertyPath().toString()));
        }
    }
}
