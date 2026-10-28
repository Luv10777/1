package com.wuyao.growth.copywriting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.brand.BrandDtos;
import com.wuyao.growth.brand.BrandService;
import com.wuyao.growth.common.gateway.AiGateway;
import com.wuyao.growth.common.gateway.ModelAlias;
import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.store.StoreAccessService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CopyAlmanacServiceTest {
    private final CopyAlmanacRepository almanacs = mock(CopyAlmanacRepository.class);
    private final CopyBatchRepository batches = mock(CopyBatchRepository.class);
    private final StoreAccessService storeAccess = mock(StoreAccessService.class);
    private final BrandService brands = mock(BrandService.class);
    private final TaskService tasks = mock(TaskService.class);
    private final AiGateway gateway = mock(AiGateway.class);
    private final CopyAlmanacService service = new CopyAlmanacService(almanacs, batches, storeAccess, brands, tasks, gateway, new ObjectMapper());
    private final LocalDate today = LocalDate.now(CopyAlmanacService.ZONE);

    private CopyAlmanac row(String status) {
        var almanac = new CopyAlmanac(); almanac.setId(5L); almanac.setStoreId(9L); almanac.setDay(today); almanac.setStatus(status);
        return almanac;
    }

    @Test void theFirstOpenOfTheDayRegistersOneAlmanacAndQueuesIt() {
        when(gateway.configured(ModelAlias.COPY_WRITER)).thenReturn(true);
        when(brands.brandForStore(9L)).thenReturn(Optional.of(new BrandDtos.StoreBrand(3L, "餐饮 / 茶饮")));
        var created = row("GENERATING");
        when(almanacs.findByStoreIdAndDayAndIndustry(9L, today, "餐饮 / 茶饮")).thenReturn(Optional.empty(), Optional.of(created));
        when(almanacs.insertIfAbsent(eq(7L), eq(9L), eq(today), eq("餐饮 / 茶饮"), anyString())).thenReturn(1);
        var task = new Task(); task.setId(50L);
        when(tasks.submit(eq(CopyAlmanacHandler.TYPE), eq("DEFAULT"), eq(Map.of("almanacId", 5L)), eq("copy-almanac-5-0"), eq(1L))).thenReturn(task);
        var view = TenantContext.runAs(7L, () -> service.today(9L, 1L));
        assertThat(view.status()).isEqualTo("GENERATING");
        assertThat(view.fallback()).isTrue();
        assertThat(view.industry()).isEqualTo("餐饮 / 茶饮");
        assertThat(view.industryStatus()).isEqualTo("KNOWN");
        assertThat(view.avoid()).contains("暗示保健功效");
        assertThat(created.getTaskId()).isEqualTo(50L);
        verify(storeAccess).requireAccess(9L, 1L);
    }

    @Test void anotherOpenAtTheSameMomentDoesNotQueueAgain() {
        when(gateway.configured(ModelAlias.COPY_WRITER)).thenReturn(true);
        when(brands.brandForStore(9L)).thenReturn(Optional.empty());
        when(almanacs.findByStoreIdAndDayAndIndustry(9L, today, CopyAlmanacPrompt.GENERAL)).thenReturn(Optional.empty(), Optional.of(row("GENERATING")));
        when(almanacs.insertIfAbsent(anyLong(), anyLong(), any(), anyString(), anyString())).thenReturn(0);
        var view = TenantContext.runAs(7L, () -> service.today(9L, 1L));
        assertThat(view.industryStatus()).isEqualTo("NO_BRAND");
        assertThat(view.industry()).isNull();
        verifyNoInteractions(tasks);
    }

    @Test void aReadyAlmanacIsShownAsGenerated() {
        when(gateway.configured(ModelAlias.TEXT_WRITER)).thenReturn(true);
        when(brands.brandForStore(9L)).thenReturn(Optional.of(new BrandDtos.StoreBrand(3L, "其他")));
        var ready = row("READY");
        ready.setGood(List.of("晒一碗热汤")); ready.setAvoid(List.of("硬广刷屏")); ready.setQuote("天凉了");
        ready.setTopics(List.of(Map.of("title", "今天的第一碗热汤", "platform", "MOMENTS", "style", "文艺治愈", "idea", "写写热汤")));
        when(almanacs.findByStoreIdAndDayAndIndustry(9L, today, CopyAlmanacPrompt.GENERAL)).thenReturn(Optional.of(ready));
        var view = TenantContext.runAs(7L, () -> service.today(9L, 1L));
        assertThat(view.fallback()).isFalse();
        assertThat(view.industryStatus()).isEqualTo("MISSING");
        assertThat(view.brandId()).isEqualTo(3L);
        assertThat(view.good()).containsExactly("晒一碗热汤");
        assertThat(view.topics()).hasSize(1);
        verifyNoInteractions(tasks);
    }

    @Test void aFailureIsRetriedAfterAWhile() {
        when(gateway.configured(ModelAlias.COPY_WRITER)).thenReturn(true);
        when(brands.brandForStore(9L)).thenReturn(Optional.empty());
        var failed = row("FAILED"); failed.setUpdatedAt(Instant.now().minusSeconds(3600));
        when(almanacs.findByStoreIdAndDayAndIndustry(9L, today, CopyAlmanacPrompt.GENERAL)).thenReturn(Optional.of(failed));
        when(almanacs.lock(5L)).thenReturn(Optional.of(failed));
        var task = new Task(); task.setId(51L);
        when(tasks.submit(eq(CopyAlmanacHandler.TYPE), eq("DEFAULT"), eq(Map.of("almanacId", 5L)), startsWith("copy-almanac-5-"), eq(1L))).thenReturn(task);
        TenantContext.runAs(7L, () -> service.today(9L, 1L));
        assertThat(failed.getStatus()).isEqualTo("GENERATING");
        assertThat(failed.getTaskId()).isEqualTo(51L);
    }

    @Test void withoutAModelTheCardStillShowsSafeAdvice() {
        when(brands.brandForStore(9L)).thenReturn(Optional.empty());
        when(almanacs.findByStoreIdAndDayAndIndustry(anyLong(), any(), anyString())).thenReturn(Optional.empty());
        var view = TenantContext.runAs(7L, () -> service.today(9L, 1L));
        assertThat(view.status()).isEqualTo("UNAVAILABLE");
        assertThat(view.good()).isNotEmpty();
        assertThat(view.topics()).isEmpty();
        verifyNoInteractions(tasks);
    }

    @Test void onlyTheTaskThatWasAskedCanFillIt() {
        var generating = row("GENERATING"); generating.setTaskId(50L);
        when(almanacs.lock(5L)).thenReturn(Optional.of(generating));
        var result = Map.<String, Object>of("good", List.of("晒一碗热汤"), "avoid", List.of("刷屏"), "quote", "", "topics", List.of(), "journalHint", "");
        assertThat(service.ready(5L, 49L, result)).isFalse();
        assertThat(service.ready(5L, 50L, result)).isTrue();
        assertThat(generating.getStatus()).isEqualTo("READY");
    }
}
