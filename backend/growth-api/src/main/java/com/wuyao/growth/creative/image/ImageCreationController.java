package com.wuyao.growth.creative.image;
import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/api/image-creations") @RequiredArgsConstructor
public class ImageCreationController {
 private final ImageCreationService service;
 @GetMapping("/capabilities") public ApiResponse<Map<String,Object>> capabilities(){return ApiResponse.ok(service.capabilities());}
 @PostMapping public ApiResponse<ImageDtos.View> create(@Valid @RequestBody ImageDtos.Create req,@AuthenticationPrincipal AuthPrincipal me) {
   return ApiResponse.ok(service.create(req,me.userId()));
 }
 @GetMapping("/{id}") public ApiResponse<ImageDtos.View> get(@PathVariable Long id){return ApiResponse.ok(service.get(id));}
 @PostMapping("/{id}/cancel") public ApiResponse<ImageDtos.View> cancel(@PathVariable Long id){return ApiResponse.ok(service.cancel(id));}
 @GetMapping("/{id}/thread") public ApiResponse<java.util.List<ImageDtos.View>> thread(@PathVariable Long id){
   return ApiResponse.ok(service.thread(id));
 }
 @PutMapping("/{id}/title") public ApiResponse<String> rename(@PathVariable Long id,
   @Valid @RequestBody ImageDtos.Rename req){return ApiResponse.ok(service.rename(id,req.title()));}
 @GetMapping public ApiResponse<PageResult<ImageDtos.History>> history(@RequestParam String workflow,
   @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){
   return ApiResponse.ok(service.history(workflow,page,size));
 }
 @GetMapping("/works") public ApiResponse<PageResult<ImageDtos.Work>> works(@RequestParam(defaultValue="0") int page,
   @RequestParam(defaultValue="20") int size){return ApiResponse.ok(service.works(page,size));}
 @PostMapping("/{id}/revisions") public ApiResponse<ImageDtos.View> revise(@PathVariable Long id,
   @Valid @RequestBody ImageDtos.Revision req,@AuthenticationPrincipal AuthPrincipal me) {
   return ApiResponse.ok(service.revise(id,req,me.userId()));
 }
 @PostMapping("/{id}/answers") public ApiResponse<ImageDtos.View> answer(@PathVariable Long id,
   @Valid @RequestBody ImageDtos.Revision req,@AuthenticationPrincipal AuthPrincipal me) {
   return ApiResponse.ok(service.revise(id,req,me.userId()));
 }
 public record Retry(@NotNull Long taskId) {}
 public record Regenerate(@jakarta.validation.constraints.NotBlank
   @jakarta.validation.constraints.Pattern(regexp="[a-zA-Z0-9-]{8,80}") String requestKey,
   @jakarta.validation.constraints.Pattern(regexp="LAYOUT|SCENE|MESSAGE") String variation) {}
 @PostMapping("/{id}/items/{itemId}/regenerate") public ApiResponse<ImageDtos.View> regenerate(
   @PathVariable Long id,@PathVariable Long itemId,@Valid @RequestBody Regenerate req,
   @AuthenticationPrincipal AuthPrincipal me){
   return ApiResponse.ok(service.regenerate(id,itemId,req.requestKey(),req.variation(),me.userId()));
 }
 @PostMapping("/{id}/retry") public ApiResponse<ImageDtos.View> retryPlan(@PathVariable Long id,
   @Valid @RequestBody Retry req,@AuthenticationPrincipal AuthPrincipal me){
   return ApiResponse.ok(service.retry(id,null,req.taskId(),me.userId()));
 }
 @PostMapping("/{id}/items/{itemId}/retry") public ApiResponse<ImageDtos.View> retry(@PathVariable Long id,@PathVariable Long itemId,
   @Valid @RequestBody Retry req,@AuthenticationPrincipal AuthPrincipal me){
   return ApiResponse.ok(service.retry(id,itemId,req.taskId(),me.userId()));
 }
 @PostMapping("/{id}/items/{itemId}/text") public ApiResponse<ImageDtos.View> text(@PathVariable Long id,@PathVariable Long itemId,
   @Valid @RequestBody ImageDtos.TextEdit req,@AuthenticationPrincipal AuthPrincipal me){
   return ApiResponse.ok(service.editText(id,itemId,req,me.userId()));
 }
}
