package com.wuyao.growth.creative.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

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
}
