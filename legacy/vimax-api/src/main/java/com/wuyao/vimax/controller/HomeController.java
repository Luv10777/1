package com.wuyao.vimax.controller;

import com.wuyao.vimax.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 根路径控制器
 */
@RestController
@RequestMapping("/")
@RequiredArgsConstructor
public class HomeController {

    @GetMapping
    public ApiResponse<Map<String, Object>> home() {
        Map<String, Object> info = new HashMap<>();
        info.put("name", "梧曜星枢 ViMax API");
        info.put("version", "0.0.1-SNAPSHOT");
        info.put("description", "企业级 AI 视频工作流改造项目");
        info.put("endpoints", Map.of(
                "health", "/actuator/health",
                "merchants", "/merchants/{merchantId}/facts",
                "snapshots", "/merchants/{merchantId}/snapshots",
                "assets", "/assets"
        ));

        return ApiResponse.success(info);
    }
}
