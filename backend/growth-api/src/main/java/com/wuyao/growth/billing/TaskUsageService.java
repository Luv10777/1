package com.wuyao.growth.billing;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.common.web.PageResult;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TaskUsageService {
    private final TaskUsageStore store;
    private static final Set<String> MODULES=Set.of("IMAGE_GENERATION","VIDEO_GENERATION","DIGITAL_HUMAN_VIDEO","VIDEO_ANALYSIS","VIDEO_PLAN");
    private static final Set<String> STATUSES=Set.of("RUNNING","SUCCEEDED","PARTIAL","FAILED","CANCELLED","NEEDS_INPUT","UPSTREAM_UNKNOWN");

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public TaskUsageDtos.Logs logs(TaskUsageDtos.Filter filter) {
        if(filter.page()<0 || filter.page()>100000 || filter.size()<1 || filter.size()>100) throw bad("分页参数无效");
        if(filter.from()!=null && filter.until()!=null && !filter.from().isBefore(filter.until())) throw bad("结束时间须晚于开始时间");
        var parameters=new HashMap<String,Object>(); parameters.put("tenant",TenantContext.require());
        var where=new StringBuilder("WHERE 1=1");
        if(filter.from()!=null) { where.append(" AND created_at>=:from"); parameters.put("from",filter.from()); }
        if(filter.until()!=null) { where.append(" AND created_at<:until"); parameters.put("until",filter.until()); }
        if(filter.module()!=null && !filter.module().isBlank()) {
            if(!MODULES.contains(filter.module())) throw bad("不支持的任务模块");
            where.append(" AND module=:module"); parameters.put("module",filter.module());
        }
        if(filter.status()!=null && !filter.status().isBlank()) {
            if(!STATUSES.contains(filter.status())) throw bad("不支持的任务状态");
            where.append(" AND status=:status"); parameters.put("status",filter.status());
        }
        if(filter.model()!=null && !filter.model().isBlank()) {
            if(filter.model().length()>200) throw bad("模型名称过长");
            where.append(" AND POSITION(REGEXP_REPLACE(LOWER(:model),'[[:space:]_.-]','','g') IN "
                +"REGEXP_REPLACE(LOWER(COALESCE(model,'')||' '||COALESCE(provider_model,'')),'[[:space:]_.-]','','g'))>0");
            parameters.put("model",filter.model().trim());
        }
        var total=store.report("SELECT COUNT(*) AS total, COALESCE(SUM(charged),0) AS charged, COALESCE(SUM(held),0) AS held, "
            +"COALESCE(SUM(released),0) AS released, COALESCE(SUM(succeeded),0) AS succeeded, COALESCE(SUM(failed),0) AS failed",
            where.toString(),parameters).getFirst();
        long count=number(total,"total");
        parameters.put("limit",filter.size()); parameters.put("offset",Math.multiplyExact(filter.page(),filter.size()));
        var records=store.report("SELECT *",where+" ORDER BY created_at DESC,id DESC LIMIT :limit OFFSET :offset",parameters)
            .stream().map(this::log).toList();
        return new TaskUsageDtos.Logs(new PageResult<>(records,filter.page(),filter.size(),count,(int)((count+filter.size()-1)/filter.size())),
            new TaskUsageDtos.Summary(count,number(total,"charged"),number(total,"held"),number(total,"released"),number(total,"succeeded"),number(total,"failed")));
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public TaskUsageDtos.Detail detail(String id) {
        if(!id.matches("(image|video|analysis|plan)-[1-9][0-9]{0,18}")) throw bad("任务标识无效");
        long tenant=TenantContext.require();
        var rows=store.report("SELECT *","WHERE id=:id",java.util.Map.of("tenant",tenant,"id",id));
        if(rows.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND,"任务记录不存在");
        var row=rows.getFirst(); var task=log(row);
        var items=task.module().equals("IMAGE_GENERATION") ? store.items(task.taskId(),tenant).stream().map(i->new TaskUsageDtos.Item(
            number(i,"id"),(int)number(i,"ordinal")+1,string(i,"title"),"一方志Image",string(i,"status"),
            nullableNumber(i,"charged"),Boolean.TRUE.equals(i.get("reused")),string(i,"error"))).toList() : List.<TaskUsageDtos.Item>of();
        String reservation=string(row,"reservation_id");
        var events=reservation==null ? List.<TaskUsageDtos.Event>of() : store.events(reservation,tenant).stream().map(e->
            new TaskUsageDtos.Event(number(e,"id"),string(e,"type"),number(e,"credits"),string(e,"description"),instant(e,"created_at"))).toList();
        return new TaskUsageDtos.Detail(task,items,events);
    }

    private TaskUsageDtos.Log log(Tuple r) {
        int quantity=(int)number(r,"quantity"), succeeded=(int)number(r,"succeeded"), failed=(int)number(r,"failed"), cancelled=(int)number(r,"cancelled");
        return new TaskUsageDtos.Log(string(r,"id"),number(r,"task_id"),string(r,"module"),string(r,"workflow"),string(r,"title"),
            string(r,"model"),string(r,"status"),instant(r,"created_at"),quantity,succeeded,failed,cancelled,
            Math.max(0,quantity-succeeded-failed-cancelled),string(r,"unit"),nullableNumber(r,"charged"),number(r,"held"),number(r,"released"),
            string(r,"resolution"),r.get("duration_seconds")==null?null:(int)number(r,"duration_seconds"),string(r,"error"),nullableNumber(r,"elapsed_millis"),nullableNumber(r,"preview_elapsed_millis"));
    }
    private static long number(Tuple r,String key) { return ((Number)r.get(key)).longValue(); }
    private static Long nullableNumber(Tuple r,String key) { return r.get(key)==null?null:number(r,key); }
    private static String string(Tuple r,String key) { return (String)r.get(key); }
    private static Instant instant(Tuple r,String key) {
        Object value=r.get(key);
        if(value instanceof Instant i) return i;
        if(value instanceof OffsetDateTime d) return d.toInstant();
        return ((Timestamp)value).toInstant();
    }
    private static BizException bad(String message) { return BizException.of(ErrorCode.BAD_REQUEST,message); }
}
