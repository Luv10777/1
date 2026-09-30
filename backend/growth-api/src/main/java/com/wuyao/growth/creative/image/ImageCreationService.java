package com.wuyao.growth.creative.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.common.gateway.*;
import com.wuyao.growth.common.ratelimit.TenantRateLimiter;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.*;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.security.MessageDigest;
import java.util.*;

@Slf4j
@Service @RequiredArgsConstructor
public class ImageCreationService {
 private final ImageCreationRepository creations;
 private final ImageItemRepository items;
 private final AssetService assets;
 private final TaskService tasks;
 private final ObjectStorage storage;
 private final AiGateway gateway;
 private final ImageModelProperties config;
 private final ObjectMapper json;
 private final TenantRateLimiter rateLimiter;

 @Value("${growth.image.tenant-max-concurrent:20}")
 private int tenantMaxConcurrent;
 @Value("${growth.image.global-max-concurrent:200}")
 private int globalMaxConcurrent;

 public Map<String,Object> capabilities() {
   boolean planner=gateway.configured(config.selectedTextAlias());
   boolean generator=gateway.configured(ModelAlias.IMAGE_PRIMARY);
   var ratios=new LinkedHashMap<String,List<String>>();
   for(String quality:config.getQualities()) ratios.put(quality,List.of("1:1","3:4","4:3","9:16","16:9","2:3","3:2").stream().filter(r->{
     return ImageQuality.supportsOutput(quality,r,config);
   }).toList());
   return Map.of("configured",planner&&generator,"plannerConfigured",planner,"generatorConfigured",generator,
       "qualities",config.getQualities(),"qualityRatios",ratios,
       "sizeHint",ImageQuality.usesGptImage2(config)?"1K/2K 支持方图、横图和竖图；4K 仅支持 16:9 横屏或 9:16 竖屏。":"",
       "message",planner&&generator?"":"图片创作服务尚未配置，请联系管理员");
 }
 @Transactional
 public ImageDtos.View create(ImageDtos.Create request,Long userId) {return createVersion(request,userId,null,null);}
 private ImageDtos.View createVersion(ImageDtos.Create req,Long userId,Long parent,String variation) {
   Long tenantId = TenantContext.require();
   creations.lockRequest(tenantId + ":" + req.requestKey());
       String hash=variation==null?hash(req,parent):hash(List.of(req,variation),parent);
       var old=creations.findByRequestKey(req.requestKey());
       if(old.isPresent()) {
         if(!hash.equals(old.get().getRequestHash())) throw bad("同一提交标识不能用于不同的创作内容");
         return view(old.get());
       }
   requireConfigured(req);
   if(req.workflow().equals("POSTER") && req.count()!=1) throw bad("海报每次生成 1 张");
   if(req.brief().isBlank() && req.references().isEmpty()) throw bad("说一句需求，或上传商品照片");
   if(req.workflow().equals("PRODUCT_SET") && req.references().stream().noneMatch(r->r.role().equals("SUBJECT")))
     throw bad("产品套图需要至少一张商品照片");
   if(req.templateId()!=null && !Set.of("romantic","newyear","seasonal").contains(req.templateId()))
     throw bad("模板不存在");
   for(var ref:req.references()) assets.imageReference(ref.assetId());
   ImageQuality.dimensions(req.quality(),req.ratio());
   acquirePermit(tenantId);
   try {
     var c=new ImageCreation(); c.setTenantId(tenantId);c.setCreatedBy(userId);c.setConcurrencyPermitHeld(true);
     c.setParentId(parent);c.setVariation(variation);c.setRequest(req);c.setRequestKey(req.requestKey());c.setRequestHash(hash);
     creations.saveAndFlush(c);
     var task=tasks.submit("IMAGE_PLAN","DEFAULT",Map.of("creationId",c.getId()),"image-plan-"+c.getId(),userId);
     c.setTaskId(task.getId());
     log.info("租户 {} 创建图片任务: creationId={} workflow={} count={}",
       tenantId, c.getId(), req.workflow(), req.count());
     return view(c);
   } catch (RuntimeException e) {
     rateLimiter.releaseImageGeneration(tenantId);
     throw e;
   }
 }
 private void requireConfigured(ImageDtos.Create req) {
   if(!gateway.configured(config.selectedTextAlias())||!gateway.configured(ModelAlias.IMAGE_PRIMARY))
     throw BizException.of(ErrorCode.IMAGE_NOT_CONFIGURED,"图片创作模型尚未配置，请联系管理员");
   if(!config.getQualities().contains(req.quality()))
     throw BizException.of(ErrorCode.IMAGE_QUALITY_UNSUPPORTED,"当前图片模型尚不支持该画质");
   if(!ImageQuality.supportsOutput(req.quality(),req.ratio(),config))
     throw BizException.of(ErrorCode.IMAGE_QUALITY_UNSUPPORTED,"当前中转站不支持该比例的图片输出，请调整比例");
 }
 @Transactional
 public ImageDtos.View revise(Long id,ImageDtos.Revision revision,Long userId) {
   var c=find(id);var r=c.getRequest();
   String brief=r.brief()+"\n本次修改："+revision.instruction();
   if(brief.length()>2000) throw bad("修改历史过长，请新建创作");
   return createVersion(new ImageDtos.Create(revision.requestKey(),r.workflow(),brief,r.references(),r.ratio(),
     r.quality(),r.count(),r.platform(),r.imageType(),r.purpose(),r.style(),r.templateId()),userId,id,revision.variation());
 }
 @Transactional(readOnly=true)
 public ImageDtos.View get(Long id) {return view(find(id));}

 @Transactional
 public ImageDtos.View cancel(Long id) {
   var c=creations.lock(id).orElseThrow(()->BizException.of(ErrorCode.NOT_FOUND,"创作不存在"));
   if(Set.of("SUCCEEDED","FAILED","CANCELLED").contains(c.getStatus())) return view(c);
   var children=items.findByCreationIdOrderByOrdinal(c.getId());
   if(!children.isEmpty() && children.stream().allMatch(i -> Set.of("SUCCEEDED","FAILED").contains(effective(i.getStatus(),i.getTaskId()))))
     return view(c);
   if(c.getTaskId()!=null) tasks.cancel(c.getTaskId());
   c.setStatus("CANCELLED"); c.setError("已取消本次图片生成");
   for(var item:children) {
     if(!Set.of("SUCCEEDED","FAILED","CANCELLED").contains(item.getStatus())) {
       item.setStatus("CANCELLED"); item.setError("已取消本次图片生成");
       if(item.getTaskId()!=null) tasks.cancel(item.getTaskId());
     }
   }
   releasePermitIfTerminal(c);
   return view(c);
 }
 @Transactional(readOnly=true)
 public List<ImageDtos.View> thread(Long id) {
   var versions=new ArrayList<ImageCreation>();
   collectVersions(root(find(id)),versions);
   versions.sort(Comparator.comparing(ImageCreation::getId));
   return versions.stream().map(this::view).toList();
 }
 @Transactional
 public String rename(Long id,String title) {
   String value=title.trim();
   if(value.isEmpty()) throw bad("作品名称不能为空");
   var root=root(find(id));
   root.setTitle(value);
   return value;
 }
 private ImageCreation root(ImageCreation creation) {
   return creation.getParentId()==null?creation:root(find(creation.getParentId()));
 }
 private void collectVersions(ImageCreation creation,List<ImageCreation> versions) {
   versions.add(creation);
   for(var child:creations.findByParentIdOrderByIdDesc(creation.getId())) collectVersions(child,versions);
 }
 private String title(ImageCreation root) {
   if(root.getTitle()!=null && !root.getTitle().isBlank()) return root.getTitle();
   String initial=root.getRequest().brief().lines().findFirst().orElse("").trim();
   return initial.isEmpty()?"图片创作":initial.substring(0,Math.min(initial.length(),100));
 }
 @Transactional(readOnly=true)
 public PageResult<ImageDtos.History> history(String workflow,int page,int size) {
   checkPage(page,size);
   if(!Set.of("POSTER","PRODUCT_SET").contains(workflow)) throw bad("不支持的工作流");
   var result=creations.findRootByRequestWorkflowOrderByIdDesc(workflow,PageRequest.of(page,size));
   return PageResult.of(result,result.stream().map(c->{
     var latest=latestVersion(c);
     var v=view(latest);
     String previewUrl=v.items().stream().map(ImageDtos.ItemView::url).filter(Objects::nonNull).findFirst().orElse(null);
     return new ImageDtos.History(latest.getId(),latest.getRequest().workflow(),title(c),latest.getRequest().brief(),
       latest.getRequest().quality(),c.getCreatedAt(),v.status(),v.completed(),v.count(),previewUrl);
   }).toList());
 }
 private ImageCreation latestVersion(ImageCreation creation) {
   var versions=new ArrayList<ImageCreation>();
   collectVersions(creation,versions);
   return versions.stream().max(Comparator.comparing(ImageCreation::getId)).orElse(creation);
 }
 @Transactional(readOnly=true)
 public PageResult<ImageDtos.Work> works(int page,int size) {
   checkPage(page,size);
   var result=items.findByStatusAndOutputKeyIsNotNullOrderByIdDesc("SUCCEEDED",PageRequest.of(page,size));
   return PageResult.of(result,result.stream().map(i->{
     var c=find(i.getCreationId());var d=ImageQuality.dimensions(c.getRequest().quality(),c.getRequest().ratio());
     return new ImageDtos.Work(i.getId(),c.getId(),c.getRequest().workflow(),title(root(c)),
       storage.presignGet(i.getOutputKey(),Duration.ofMinutes(30)),c.getRequest().quality(),
       i.getActualWidth()==null?d.width():i.getActualWidth(),
       i.getActualHeight()==null?d.height():i.getActualHeight(),i.getCreatedAt());
   }).toList());
 }
 @Transactional
 public ImageDtos.View retry(Long id,Long itemId,Long expectedTaskId,Long userId) {
   var c=find(id);
   if(itemId==null) {
     c=creations.lock(id).orElseThrow();
     if(Objects.equals(c.getTaskId(),expectedTaskId) && "INTERRUPTED".equals(effective(c.getStatus(),c.getTaskId()))) {
       c.setStatus("QUEUED");c.setError(null);
       c.setTaskId(tasks.submit("IMAGE_PLAN","DEFAULT",Map.of("creationId",id),
           "image-plan-retry-"+id+"-"+expectedTaskId,userId).getId());
     }
   } else {
     var i=items.lock(itemId).orElseThrow(()->bad("图片不存在"));
     if(!i.getCreationId().equals(id)) throw bad("图片不属于这次创作");
     if(!Objects.equals(i.getTaskId(),expectedTaskId)) return view(c);
     String status=effective(i.getStatus(),i.getTaskId());
      if(!Set.of("FAILED","INTERRUPTED","UPSTREAM_UNKNOWN").contains(status)) throw bad("当前图片无需重试");
      if(Set.of("FAILED","UPSTREAM_UNKNOWN").contains(i.getStatus())) {
       i.setGeneration(i.getGeneration()+1);i.setProviderJobId(null);i.setRawKey(null);i.setPollRound(0);
       i.setProviderImageUrl(null);i.setPersistedAt(null);i.setOutputKey(null);
       i.setProviderCode(null);
     }
     i.setPollRound(0);
     i.setStatus("QUEUED");i.setError(null);
      c.setStatus("GENERATING");c.setError(null);
     enqueue(i,c,"retry-"+expectedTaskId,0);
   }
   return view(c);
 }
 @Transactional
 public ImageDtos.View editText(Long id,Long itemId,ImageDtos.TextEdit edit,Long userId) {
   var original=find(id);
   var source=items.findById(itemId).orElseThrow(()->bad("图片不存在"));
   if(!source.getCreationId().equals(id)||!"SUCCEEDED".equals(source.getStatus())) throw bad("请先完成这张图片");
   var r=original.getRequest();
   String brief="修改文字："+edit.headline()+"\n"+edit.caption();
   var req=new ImageDtos.Create(edit.requestKey(),r.workflow(),brief,r.references(),r.ratio(),r.quality(),1,r.platform(),r.imageType(),r.purpose(),r.style(),r.templateId());
   creations.lockRequest(TenantContext.require()+":"+edit.requestKey());
   String hash=hash(req,itemId);
   var previous=creations.findByRequestKey(edit.requestKey());
   if(previous.isPresent()) {
     if(!hash.equals(previous.get().getRequestHash())) throw bad("提交标识已被使用");
     return view(previous.get());
   }
   requireConfigured(req);
   Long tenantId=TenantContext.require();
   acquirePermit(tenantId);
   var spec=new ImageDtos.Spec(source.getSpec().role(),"参考原图修改文字，尽量保持商品、构图、色彩与风格；去除旧标题和说明，按新的文案重新设计文字。",edit.headline(),edit.caption(),source.getId());
   var c=new ImageCreation();c.setTenantId(tenantId);c.setCreatedBy(userId);c.setConcurrencyPermitHeld(true);
   c.setParentId(id);c.setRequestKey(edit.requestKey());c.setRequestHash(hash);c.setRequest(req);
   c.setPlan(new ImageDtos.Plan("参考原图修改文字，局部画面可能变化","",original.getPlan().visualDirection(),List.of(spec)));c.setStatus("GENERATING");creations.saveAndFlush(c);
   var i=new ImageItem();i.setTenantId(c.getTenantId());i.setCreationId(c.getId());i.setSpec(spec);
   items.saveAndFlush(i);enqueue(i,c,"text",0);
   return view(c);
 }
 @Transactional
 public ImageDtos.View regenerate(Long id,Long itemId,String requestKey,String variation,Long userId) {
   var original=find(id);
   var originals=items.findByCreationIdOrderByOrdinal(id);
   if(originals.isEmpty() || originals.stream().anyMatch(i->!"SUCCEEDED".equals(i.getStatus()))
       || originals.stream().noneMatch(i->i.getId().equals(itemId))) throw bad("整组完成后可以单独重做一张");
   if("POSTER".equals(original.getRequest().workflow())) {
     String direction=variation==null?"LAYOUT":variation;
     if(!Set.of("LAYOUT","SCENE","MESSAGE").contains(direction)) throw bad("不支持的海报换版方向");
     var r=original.getRequest();
     var req=new ImageDtos.Create(requestKey,r.workflow(),r.brief(),r.references(),r.ratio(),r.quality(),
       1,r.platform(),r.imageType(),r.purpose(),r.style(),r.templateId());
     return createVersion(req,userId,id,direction);
   }
   if(variation!=null) throw bad("产品套图不支持海报换版方向");
   creations.lockRequest(TenantContext.require()+":"+requestKey);
   String fingerprint=hash(List.of("regenerate",id,itemId),id);
   var previous=creations.findByRequestKey(requestKey);
   if(previous.isPresent()) {
     if(!fingerprint.equals(previous.get().getRequestHash())) throw bad("提交标识已被使用");
     return view(previous.get());
   }
   requireConfigured(original.getRequest());
   Long tenantId=TenantContext.require();
   acquirePermit(tenantId);
   var c=new ImageCreation();c.setTenantId(tenantId);c.setCreatedBy(userId);c.setConcurrencyPermitHeld(true);
   c.setParentId(id);c.setRequestKey(requestKey);c.setRequestHash(fingerprint);
   c.setRequest(original.getRequest());c.setPlan(original.getPlan());c.setStatus("GENERATING");creations.saveAndFlush(c);
   for(var source:originals) {
     var i=new ImageItem();i.setTenantId(c.getTenantId());i.setCreationId(c.getId());
     i.setOrdinal(source.getOrdinal());i.setSpec(source.getSpec());
     if(!source.getId().equals(itemId)) {
       i.setRawKey(source.getRawKey());i.setOutputKey(source.getOutputKey());i.setStatus("SUCCEEDED");
       i.setProviderCode(source.getProviderCode());i.setModel(source.getModel());
     }
     items.saveAndFlush(i);
     if(source.getId().equals(itemId)) enqueue(i,c,"regenerate",0);
   }
   return view(c);
 }
 private String hash(Object request,Long parent) {
   try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
       .digest(json.writeValueAsBytes(Arrays.asList(request,parent))));}
   catch(Exception e) {throw new IllegalStateException(e);}
 }
 private void checkPage(int p,int s) {if(p<0||s<1||s>50) throw bad("分页参数无效");}
 private BizException bad(String text) {return BizException.of(ErrorCode.BAD_REQUEST,text);}
 private void acquirePermit(Long tenantId) {
   if(!rateLimiter.tryAcquireImageGeneration(tenantId,tenantMaxConcurrent,globalMaxConcurrent))
     throw BizException.of(ErrorCode.RATE_LIMITED,"当前有较多图片任务正在生成，请稍后重试");
 }
 private void releasePermitIfTerminal(ImageCreation creation) {
   if (!creation.isConcurrencyPermitHeld()) return;
   var children=items.findByCreationIdOrderByOrdinal(creation.getId());
    boolean terminal = Set.of("FAILED","NEEDS_INPUT","CANCELLED","UPSTREAM_UNKNOWN").contains(creation.getStatus())
        || (!children.isEmpty() && children.stream().allMatch(i -> Set.of("SUCCEEDED","FAILED","CANCELLED","UPSTREAM_UNKNOWN").contains(effective(i.getStatus(),i.getTaskId()))));
   if (terminal) cleanupFailedObjects(children);
   if (terminal && creations.clearConcurrencyPermit(creation.getId()) == 1)
     rateLimiter.releaseImageGeneration(creation.getTenantId());
 }
 private void cleanupFailedObjects(List<ImageItem> children) {
   for (var item : children) {
     if (Set.of("FAILED", "CANCELLED").contains(item.getStatus())) {
       if (item.getRawKey() != null) storage.delete(item.getRawKey());
       if (item.getOutputKey() != null) storage.delete(item.getOutputKey());
     }
   }
 }
 ImageCreation find(Long id) {
   return creations.findById(id).orElseThrow(()->BizException.of(ErrorCode.NOT_FOUND,"创作不存在"));
 }
 @Transactional(readOnly=true)
 public ImageCreation snapshot(Long id) {return find(id);}
 @Transactional(readOnly=true)
 public ImageItem itemSnapshot(Long id) {return items.findById(id).orElseThrow();}
 @Transactional(readOnly=true)
 public List<AssetService.ImageReference> references(Long creationId) {
   return find(creationId).getRequest().references().stream().map(r->assets.imageReference(r.assetId())).toList();
 }
 @Transactional
 public void saveReferenceHash(Long id,Task task,String hash) {
   if(!tasks.ownsExecution(task)) return;
   var c=creations.lock(id).orElseThrow();
   if(Objects.equals(c.getTaskId(),task.getId())) c.setReferenceHash(hash);
 }
 @Transactional(readOnly=true)
 public Map<String,Object> posterContext(Long id,String referenceHash) {
   var c=find(id);
   if(!"POSTER".equals(c.getRequest().workflow())) return Map.of();
   var context=new LinkedHashMap<String,Object>();
   if(c.getVariation()!=null) {
     context.put("variation",c.getVariation());
     var parent=find(c.getParentId());
     if(parent.getPlan()!=null && !parent.getPlan().items().isEmpty())
       context.put("previousPoster",posterPlanSummary(parent.getPlan()));
   }
   var candidates=creations.recentPosterPlans(c.getTenantId(),id).stream()
     .filter(previous->!previous.getPlan().items().isEmpty()).toList();
   var matching=referenceHash==null?List.<ImageCreation>of():candidates.stream()
     .filter(previous->previous.getReferenceHash()!=null
       && ImageFingerprint.distance(referenceHash,previous.getReferenceHash())<=6).limit(4).toList();
   var recent=(matching.isEmpty()?candidates.stream().limit(3):matching.stream())
     .map(previous->Map.of("layoutCues",posterLayoutCues(previous.getPlan().visualDirection()),
       "cameraLanguage",Objects.toString(previous.getPlan().items().getFirst().cameraLanguage(),""))).toList();
   if(!recent.isEmpty()) context.put("recentPostersToAvoid",recent);
   return context;
 }
 private Map<String,String> posterPlanSummary(ImageDtos.Plan plan) {
   var item=plan.items().getFirst();
   return Map.of("visualDirection",plan.visualDirection(),"headline",item.headline(),"caption",item.caption());
 }
 private String posterLayoutCues(String direction) {
   var cues=Arrays.stream(direction.split("[;；]")).map(String::trim)
     .filter(part->part.matches("(?i)^(composition language|camera language|typography direction)\\s*:.*"))
     .toList();
   return cues.isEmpty()?direction.substring(0,Math.min(300,direction.length())):String.join("; ",cues);
 }
 @Transactional
 public boolean beginPlan(Long id,Task task) {
   if(!tasks.ownsExecution(task)) return false;
   var c=creations.lock(id).orElseThrow();
   if(!Objects.equals(c.getTaskId(),task.getId()) || c.getPlan()!=null) return false;
   c.setStatus("PLANNING");return true;
 }
 @Transactional
 public void savePlan(Long id,Task task,ImageDtos.Plan plan) {
   if(!tasks.ownsExecution(task)) return;
   var c=creations.lock(id).orElseThrow();
   if(!Objects.equals(c.getTaskId(),task.getId())||c.getPlan()!=null)return;
   c.setPlan(plan);c.setStatus(plan.question().isBlank()?"GENERATING":"NEEDS_INPUT");
   for(int n=0;n<plan.items().size();n++) {
     var i=new ImageItem();i.setTenantId(c.getTenantId());i.setCreationId(id);i.setOrdinal(n);
     i.setSpec(plan.items().get(n));items.saveAndFlush(i);enqueue(i,c,"initial",0);
   }
   releasePermitIfTerminal(c);
 }
 private void enqueue(ImageItem i,ImageCreation c,String suffix,int delay) {
   var t=tasks.submit("IMAGE_RENDER","IMAGE",Map.of("itemId",i.getId()),
     "image-"+i.getId()+"-"+i.getGeneration()+"-"+suffix,c.getCreatedBy());
   t.setRunAfter(Instant.now().plusSeconds(delay));i.setTaskId(t.getId());
 }
 @Transactional
 public boolean beginItem(Long id,Task task) {
   if(!tasks.ownsExecution(task))return false;
   var i=items.lock(id).orElseThrow();
   if(!Objects.equals(i.getTaskId(),task.getId())||Set.of("SUCCEEDED","FAILED","CANCELLED").contains(i.getStatus()))return false;
   i.setStatus(i.getRawKey()==null?"GENERATING":"SAVING");return true;
 }
 /** Persist intent before a synchronous call: OpenAI compatibility does not guarantee idempotency. */
 @Transactional
 public boolean reserveSynchronousSubmission(Long id,Task task) {
   return reserveProviderSubmission(id, task);
 }
 @Transactional
 public boolean reserveProviderSubmission(Long id,Task task) {
   if(!tasks.ownsExecution(task))return false;
   var i=items.lock(id).orElseThrow();
   if(!Objects.equals(i.getTaskId(),task.getId()))return false;
   if(i.getProviderCode()!=null) {
     if("SUBMITTING".equals(i.getProviderCode()) || "OPENAI_SUBMITTING".equals(i.getProviderCode())) {
       i.setStatus("FAILED");i.setError("上次提交状态尚未确认。请先核对中转站记录；重新生成可能产生新的费用。");
     }
     return false;
   }
   i.setProviderCode("SUBMITTING");return true;
 }
 @Transactional
 public void failSynchronousSubmission(Long id,Task task,String message) {
   if(!tasks.ownsExecution(task))return;
   var i=items.lock(id).orElseThrow();
   if(!Objects.equals(i.getTaskId(),task.getId()))return;
   i.setStatus("FAILED");i.setError(message+"。请先核对中转站记录，重新生成可能产生新的费用。");
   var c=find(i.getCreationId());
   syncCreationStatus(c);
   releasePermitIfTerminal(c);
 }
  /**
   * A synchronous provider can finish a paid operation after the response
   * connection is lost. Do not turn that ambiguous outcome into FAILED or
   * submit the same request automatically.
   */
  @Transactional
  public void markUpstreamUnknown(Long id,Task task,String message) {
    if(!tasks.ownsExecution(task))return;
    var i=items.lock(id).orElseThrow();
    if(!Objects.equals(i.getTaskId(),task.getId()))return;
    i.setStatus("UPSTREAM_UNKNOWN");
    i.setError(message+"。请先核对中转站记录，确认未生成后再重新生成。");
    var c=find(i.getCreationId());
    syncCreationStatus(c);
    releasePermitIfTerminal(c);
  }
 @Transactional
 public void saveProvider(Long id,Task task,ProviderResult result,String rawKey) {
   if(!tasks.ownsExecution(task))return;
   var i=items.lock(id).orElseThrow();
   if(!Objects.equals(i.getTaskId(),task.getId()))return;
   String status=String.valueOf(result.output().get("status"));
   if(result.providerJobId()!=null)i.setProviderJobId(result.providerJobId());
   i.setProviderCode(result.providerCode());i.setModel(String.valueOf(result.output().getOrDefault("_model","")));
   Object usage=result.output().get("usage");
   if(usage instanceof Map<?,?>) i.setUsage(json.convertValue(usage,new com.fasterxml.jackson.core.type.TypeReference<>(){}));
   if(status.equals("FAILED")) {
     i.setStatus("FAILED");i.setError("图片模型未能完成这张图，可以重试");
     var c=find(i.getCreationId());
     syncCreationStatus(c);
     releasePermitIfTerminal(c);
     return;
   }
   if(rawKey!=null) {i.setRawKey(rawKey);i.setStatus("SAVING");return;}
   i.setPollRound(i.getPollRound()+1);
   if(i.getPollRound()>config.getMaxPolls()) {
     // Do not re-submit: retain job ID and permit query-only recovery.
     throw new IllegalStateException("生成时间较长，恢复时将继续查询原任务");
   }
   i.setStatus("GENERATING");
   enqueue(i,find(i.getCreationId()),"poll-"+i.getPollRound(),config.getPollSeconds());
 }
/** Record the provider URL and enqueue its durable copy. */
 @Transactional
 public void saveProviderUrl(Long id,Task task,ProviderResult result,String imageUrl,String targetKey) {
   if(!tasks.ownsExecution(task))return;
   var i=items.lock(id).orElseThrow();
   if(!Objects.equals(i.getTaskId(),task.getId()))return;
   if(imageUrl==null || imageUrl.isBlank()) throw new IllegalArgumentException("上游未返回图片 URL");
   if(result.providerJobId()!=null)i.setProviderJobId(result.providerJobId());
   i.setProviderCode(result.providerCode());
   i.setModel(String.valueOf(result.output().getOrDefault("_model","")));
   Object usage=result.output().get("usage");
   if(usage instanceof Map<?,?>) i.setUsage(json.convertValue(usage,new com.fasterxml.jackson.core.type.TypeReference<>(){}));
   // The provider URL is only an intermediate source. The local task is not
   // successful until IMAGE_DOWNLOAD has persisted the object and recorded its
   // own key.
   i.setProviderImageUrl(imageUrl);
   i.setStatus("GENERATING");
   i.setError(null);
   var c=find(i.getCreationId());
   var download=tasks.submit("IMAGE_DOWNLOAD", "DEFAULT",
     Map.of("itemId",id,"imageUrl",String.valueOf(result.output().getOrDefault("imageSourceUrl",imageUrl)),"targetKey",targetKey),
     "image-download-"+id+"-"+i.getGeneration(), c.getCreatedBy());
   download.setPriority(-5);
 }
 @Transactional
 public void markImagePersisted(Long itemId,String storageKey,int width,int height,String imageHash) {
   var item=items.lock(itemId).orElseThrow(()->new IllegalStateException("图片项不存在"));
   var creation=find(item.getCreationId());
   String expected="t"+creation.getTenantId()+"/generated/"+itemId+"/"+item.getGeneration()+"/background.png";
   if(!expected.equals(storageKey) || !Set.of("GENERATING","SAVING","SUCCEEDED").contains(item.getStatus()))
     throw new IllegalStateException("图片下载结果已过期");
   item.setRawKey(storageKey);
   item.setOutputKey(storageKey);
   item.setActualWidth(width);
   item.setActualHeight(height);
   item.setPersistedAt(Instant.now());
   if(imageHash!=null && "POSTER".equals(creation.getRequest().workflow()) && creation.getReferenceHash()!=null
       && (creation.getParentId()==null || creation.getVariation()!=null)) {
     item.setSimilarityWarning(items.recentPosterFingerprints(creation.getTenantId(),itemId,creation.getRequest().ratio()).stream()
       .anyMatch(previous->ImageFingerprint.distance(creation.getReferenceHash(),(String)previous[0])<=6
         && ImageFingerprint.distance(imageHash,(String)previous[1])<=6));
   }
   item.setImageHash(imageHash);
   item.setStatus("SUCCEEDED");
   item.setError(null);
   syncCreationStatus(creation);
   releasePermitIfTerminal(creation);
 }
 @Transactional
 public void complete(Long id,Task task,String outputKey,String imageHash) {
   if(!tasks.ownsExecution(task))return;
   var i=items.lock(id).orElseThrow();
   if(!Objects.equals(i.getTaskId(),task.getId()))return;
   var c=find(i.getCreationId());
   if(imageHash!=null && "POSTER".equals(c.getRequest().workflow()) && c.getReferenceHash()!=null
       && (c.getParentId()==null || c.getVariation()!=null)) {
     boolean similar=items.recentPosterFingerprints(c.getTenantId(),id,c.getRequest().ratio()).stream()
       .anyMatch(previous->ImageFingerprint.distance(c.getReferenceHash(),(String)previous[0])<=6
         && ImageFingerprint.distance(imageHash,(String)previous[1])<=6);
     i.setSimilarityWarning(similar);
   }
   i.setImageHash(imageHash);
   i.setOutputKey(outputKey);i.setPersistedAt(Instant.now());i.setStatus("SUCCEEDED");i.setError(null);
   syncCreationStatus(c);
   releasePermitIfTerminal(c);
 }
 private void syncCreationStatus(ImageCreation creation) {
   var children=items.findByCreationIdOrderByOrdinal(creation.getId());
   if(children.isEmpty()) return;
   boolean allSucceeded=children.stream().allMatch(i -> "SUCCEEDED".equals(i.getStatus()));
   boolean allCancelled=children.stream().allMatch(i -> "CANCELLED".equals(i.getStatus()));
    boolean allTerminal=children.stream().allMatch(i -> Set.of("SUCCEEDED","FAILED","CANCELLED","UPSTREAM_UNKNOWN").contains(i.getStatus()));
    boolean anyUnknown=children.stream().anyMatch(i -> "UPSTREAM_UNKNOWN".equals(i.getStatus()));
    if(allSucceeded) creation.setStatus("SUCCEEDED");
    else if(allCancelled) creation.setStatus("CANCELLED");
    else if(anyUnknown && allTerminal) creation.setStatus("UPSTREAM_UNKNOWN");
    else if(allTerminal && children.stream().anyMatch(i -> "SUCCEEDED".equals(i.getStatus()))) creation.setStatus("PARTIAL");
    else if(allTerminal) creation.setStatus("FAILED");
 }
 @Transactional
 public void saveActualDimensions(Long id,Task task,int width,int height) {
   if(!tasks.ownsExecution(task))return;
   var i=items.lock(id).orElseThrow();
   if(!Objects.equals(i.getTaskId(),task.getId()))return;
   i.setActualWidth(width);i.setActualHeight(height);
 }
 private String effective(String state,Long taskId) {
    if(Set.of("SUCCEEDED","FAILED","NEEDS_INPUT","UPSTREAM_UNKNOWN").contains(state))return state;
   if(taskId!=null && tasks.statusForTenant(taskId)==TaskStatus.FAILED)return "INTERRUPTED";
   return state;
 }
 private ImageDtos.View view(ImageCreation c) {
   var d=ImageQuality.dimensions(c.getRequest().quality(),c.getRequest().ratio());
   var list=items.findByCreationIdOrderByOrdinal(c.getId());
   var views=list.stream().map(i->{
     String status=effective(i.getStatus(),i.getTaskId());
     String durableUrl=i.getOutputKey()==null?null:storage.presignGet(i.getOutputKey(),Duration.ofMinutes(30));
     // Never expose the provider's short-lived URL as the task result. It is
     // retained internally only so the download worker can recover it.
     String url=durableUrl;
     boolean downloadFailed=i.getProviderImageUrl()!=null && i.getPersistedAt()==null
       && tasks.statusByIdempotencyKey("image-download-"+i.getId()+"-"+i.getGeneration())==TaskStatus.FAILED;
     if(downloadFailed) status="FAILED";
     return new ImageDtos.ItemView(i.getId(),i.getOrdinal(),i.getSpec().role(),i.getSpec().headline(),i.getSpec().caption(),
       status,status.equals("INTERRUPTED")?"执行中断，可恢复原任务":downloadFailed?"图片保存失败，临时预览链接可能过期":i.getError(),url,
       i.getActualWidth()==null?d.width():i.getActualWidth(),
       i.getActualHeight()==null?d.height():i.getActualHeight(),i.getTaskId(),i.isSimilarityWarning(),
       null,i.getPersistedAt()!=null,downloadFailed);
   }).toList();
   int done=(int)views.stream().filter(i->i.status().equals("SUCCEEDED")).count();
   String state=effective(c.getStatus(),c.getTaskId());
   if(!views.isEmpty()) {
     boolean active=views.stream().anyMatch(i->Set.of("QUEUED","GENERATING","SAVING").contains(i.status()));
      boolean cancelled=views.stream().allMatch(i->"CANCELLED".equals(i.status()));
      boolean unknown=views.stream().anyMatch(i->"UPSTREAM_UNKNOWN".equals(i.status()));
      state=cancelled?"CANCELLED":done==views.size()?"SUCCEEDED":active?"GENERATING":unknown?"UPSTREAM_UNKNOWN":done>0?"PARTIAL":"FAILED";
   }
   return new ImageDtos.View(c.getId(),c.getParentId(),c.getRequest().workflow(),c.getRequest().brief(),
     c.getRequest().quality(),c.getRequest().ratio(),state,c.getPlan()==null?"":c.getPlan().summary(),
     c.getPlan()==null?"":c.getPlan().question(),state.equals("INTERRUPTED")?"规划任务中断，可恢复重试":c.getError(),
     done,c.getRequest().count(),views,c.getCreatedAt(),c.getTaskId());
 }
}
