package com.wuyao.growth.common.web;

import com.wuyao.growth.iam.dto.AuthDtos;
import jakarta.validation.Valid;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ErrorResponseTest {
    final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders.standaloneSetup(new TestController())
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test void businessErrorsHaveMatchingHttpStatusAndRetainEnvelope() throws Exception {
        var cases = java.util.Map.of(ErrorCode.UNAUTHORIZED, 401, ErrorCode.ASSET_NOT_FOUND, 404,
                ErrorCode.SMS_TOO_FREQUENT, 429, ErrorCode.BAD_REQUEST, 400, ErrorCode.CONFLICT, 409,
                ErrorCode.IMAGE_PROVIDER_ERROR, 502, ErrorCode.STORAGE_UNAVAILABLE, 503, ErrorCode.FORBIDDEN, 403);
        for (var entry : cases.entrySet()) {
            mvc.perform(get("/error/" + entry.getKey().name()))
                    .andExpect(status().is(entry.getValue())).andExpect(jsonPath("$.code").value(entry.getKey().getCode()));
        }
    }
    @Test void invalidAndMalformedBodiesAre400InsteadOf200Or500() throws Exception {
        for (String body : java.util.List.of("{", "{}", "{\"phone\":\"not a phone\"}")) {
            mvc.perform(post("/body").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1400));
        }
        mvc.perform(get("/body")).andExpect(status().isMethodNotAllowed());
    }
    @RestController
    static class TestController {
        @GetMapping("/error/{code}") void error(@PathVariable ErrorCode code) { throw BizException.of(code, "error"); }
        @PostMapping("/body") void body(@Valid @RequestBody AuthDtos.SendCodeRequest request) {}
    }
}
