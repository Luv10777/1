package com.wuyao.growth.notice;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import com.wuyao.growth.common.web.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 当前登录账号的站内通知。 */
@RestController
@RequestMapping("/api/notices")
@RequiredArgsConstructor
public class NoticeController {
    private final NoticeService notices;

    @GetMapping
    public ApiResponse<PageResult<NoticeDtos.NoticeView>> list(@RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(defaultValue = "20") int size,
                                                               @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(notices.mine(me.userId(), page, size));
    }

    @GetMapping("/unread")
    public ApiResponse<NoticeDtos.UnreadView> unread(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(notices.unread(me.userId()));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<Void> read(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal me) {
        notices.markRead(id, me.userId());
        return ApiResponse.ok();
    }

    @PostMapping("/read-all")
    public ApiResponse<Void> readAll(@AuthenticationPrincipal AuthPrincipal me) {
        notices.markAllRead(me.userId());
        return ApiResponse.ok();
    }
}
