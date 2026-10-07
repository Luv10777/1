package com.wuyao.growth.team;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 所有接口只有老板能调用，判断在 {@link TeamService} 里。 */
@RestController
@RequestMapping("/api/team/members")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;

    @GetMapping
    public ApiResponse<List<TeamDtos.MemberView>> list(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(teamService.list(me.userId()));
    }

    @PostMapping
    public ApiResponse<TeamDtos.MemberView> add(@Valid @RequestBody TeamDtos.CreateRequest request,
                                                @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(teamService.add(request, me.userId()));
    }

    @PutMapping("/{id}")
    public ApiResponse<TeamDtos.MemberView> update(@PathVariable Long id,
                                                   @Valid @RequestBody TeamDtos.UpdateRequest request,
                                                   @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(teamService.update(id, request, me.userId()));
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<TeamDtos.MemberView> disable(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(teamService.setEnabled(id, false, me.userId()));
    }

    @PostMapping("/{id}/enable")
    public ApiResponse<TeamDtos.MemberView> enable(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(teamService.setEnabled(id, true, me.userId()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal me) {
        teamService.remove(id, me.userId());
        return ApiResponse.ok();
    }
}
