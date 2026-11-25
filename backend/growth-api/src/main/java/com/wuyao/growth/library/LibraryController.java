package com.wuyao.growth.library;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import com.wuyao.growth.common.web.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/library")
@RequiredArgsConstructor
public class LibraryController {
    private final LibraryService service;

    @GetMapping("/folders")
    public ApiResponse<LibraryDtos.Folders> folders(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.folders(me.userId()));
    }

    @PostMapping("/folders")
    public ApiResponse<LibraryDtos.Folder> createFolder(@Valid @RequestBody LibraryDtos.FolderRequest req,
                                                        @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.createFolder(req.name(), me.userId()));
    }

    @PutMapping("/folders/{id}")
    public ApiResponse<LibraryDtos.Folder> renameFolder(@PathVariable Long id, @Valid @RequestBody LibraryDtos.FolderRequest req,
                                                        @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.renameFolder(id, req.name(), me.userId()));
    }

    @DeleteMapping("/folders/{id}")
    public ApiResponse<Void> deleteFolder(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal me) {
        service.deleteFolder(id, me.userId());
        return ApiResponse.ok();
    }

    @GetMapping("/works")
    public ApiResponse<PageResult<LibraryDtos.Work>> works(@RequestParam(defaultValue = "all") String folder,
                                                           @RequestParam(required = false) String kind,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "20") int size,
                                                           @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.works(folder, kind, page, size, me.userId()));
    }

    @PostMapping("/works")
    public ApiResponse<LibraryDtos.Saved> save(@Valid @RequestBody LibraryDtos.SaveRequest req,
                                               @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.save(req, me.userId()));
    }

    @PutMapping("/works/{id}/folder")
    public ApiResponse<Void> move(@PathVariable Long id, @RequestBody LibraryDtos.MoveRequest req,
                                  @AuthenticationPrincipal AuthPrincipal me) {
        service.move(id, req.folderId(), me.userId());
        return ApiResponse.ok();
    }

    @DeleteMapping("/works/{id}")
    public ApiResponse<Void> remove(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal me) {
        service.remove(id, me.userId());
        return ApiResponse.ok();
    }

    @GetMapping("/saved")
    public ApiResponse<List<LibraryDtos.Saved>> saved(@RequestParam String kind, @RequestParam List<Long> sourceIds,
                                                      @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.saved(kind, sourceIds, me.userId()));
    }
}
