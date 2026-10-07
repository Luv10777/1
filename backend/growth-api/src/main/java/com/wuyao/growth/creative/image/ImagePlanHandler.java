package com.wuyao.growth.creative.image;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

@Component @RequiredArgsConstructor
public class ImagePlanHandler implements TaskHandler {
 private final ImageCreationService service;
 private final ImagePlanner planner;
 private final ObjectStorage storage;
 private final ImageRenderer renderer;
 public String type(){return "IMAGE_PLAN";}
 public Map<String,Object> handle(Task task){
   long id=((Number)task.getPayload().get("creationId")).longValue();
   if(!service.beginPlan(id,task))return Map.of("reused",true);
   var c=service.snapshot(id);
   var refs=new ArrayList<Map<String,Object>>();
   var source=service.references(id);
   String referenceHash=null;
   boolean subjectChosen=false;
   for(int n=0;n<source.size();n++){
     byte[] bytes=storage.read(source.get(n).storageKey(),20*1024*1024);
     var decoded=renderer.decode(bytes);
     boolean subject="SUBJECT".equals(c.getRequest().references().get(n).role());
     if(referenceHash==null || (subject && !subjectChosen)) referenceHash=ImageFingerprint.of(decoded);
     if(subject) subjectChosen=true;
     // Reference thumbnails reduce planner latency; generation still receives original images.
     int max=1280;double factor=Math.min(1,(double)max/Math.max(decoded.getWidth(),decoded.getHeight()));
     var thumb=new java.awt.image.BufferedImage(Math.max(1,(int)(decoded.getWidth()*factor)),
       Math.max(1,(int)(decoded.getHeight()*factor)),java.awt.image.BufferedImage.TYPE_INT_RGB);
     var g=thumb.createGraphics();try{g.drawImage(decoded,0,0,thumb.getWidth(),thumb.getHeight(),null);}finally{g.dispose();}
     refs.add(Map.of("role",c.getRequest().references().get(n).role(),
       "dataUrl","data:image/png;base64,"+Base64.getEncoder().encodeToString(renderer.png(thumb))));
   }
   if(referenceHash!=null) service.saveReferenceHash(id,task,referenceHash);
   var plan=planner.plan(c,refs,service.posterContext(id,referenceHash));
   service.savePlan(id,task,plan);
   return Map.of("creationId",id,"promptVersion",ImagePlanner.version(c.getRequest().workflow()));
 }
}
