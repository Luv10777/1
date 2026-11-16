package com.wuyao.growth.backoffice;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/platform/security") @RequiredArgsConstructor
public class BackofficeController {
    private final BackofficeAccess access;
    private final BackofficeAudit audit;
    @GetMapping("/roles") public ApiResponse<List<BackofficeAccess.Assignment>> roles(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(access.assignments(me.userId()));
    }
    @PutMapping("/roles") public ApiResponse<List<BackofficeAccess.Assignment>> assign(@AuthenticationPrincipal AuthPrincipal me,
            @Valid @RequestBody BackofficeAccess.RoleRequest input, jakarta.servlet.http.HttpServletRequest request) {
        request.setAttribute("backoffice.action", "调整管理员 UID " + input.uid() + " 的角色为 " + input.roles());
        return ApiResponse.ok(access.assign(me.userId(),input));
    }
    @GetMapping("/operations") public ApiResponse<PageResult<BackofficeAudit.Entry>> operations(@AuthenticationPrincipal AuthPrincipal me,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="30") int days,
            @RequestParam(required=false) Long actorId,@RequestParam(required=false) String keyword,@RequestParam(required=false) String result) {
        return ApiResponse.ok(audit.list(me.userId(),page,size,days,actorId,keyword,result));
    }
}
