package com.wuyao.growth.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.security.JwtService;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.video.VideoDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.config.import=", "growth.worker.enabled=false", "logging.level.root=WARN",
    "growth.voice.sample-storage=minio"})
@AutoConfigureMockMvc
@Testcontainers
class TaskUsageIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("task_usage_test").withUsername("growth_owner").withPassword("test_owner_password");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username",()->"growth_app"); r.add("spring.datasource.password",()->"growth_dev_local");
        r.add("spring.flyway.url",POSTGRES::getJdbcUrl); r.add("spring.flyway.user",POSTGRES::getUsername);
        r.add("spring.flyway.password",POSTGRES::getPassword); r.add("spring.flyway.placeholders.app_db_password",()->"growth_dev_local");
        r.add("growth.jwt.secret",()->"test-only-random-signing-secret-0123456789abcdef");
    }
    @Autowired TaskUsageService usage;
    @Autowired BillingService billing;
    @Autowired JwtService jwt;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    JdbcTemplate owner;
    long tenant, otherTenant, user;

    @BeforeEach void setUp() {
        owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        owner.execute("TRUNCATE tenants RESTART IDENTITY CASCADE");
        tenant=owner.queryForObject("INSERT INTO tenants(name) VALUES ('Task owner') RETURNING id",Long.class);
        otherTenant=owner.queryForObject("INSERT INTO tenants(name) VALUES ('Other owner') RETURNING id",Long.class);
        BillingTestFixtures.activateMembership(owner,tenant,otherTenant);
        owner.update("UPDATE billing_accounts SET purchased_available=10000");
        user=owner.queryForObject("INSERT INTO users(tenant_id,username,name) VALUES (?,'task-owner','Task owner') RETURNING id",Long.class,tenant);
    }

    @Test void partialImagesReconcileWithRealCapturesAndReleasesDownToEachImage() {
        String reservation=reserve("IMAGE",12);
        long id=image(tenant,"partial",4,null,reservation,"GENERATING",null);
        long first=item(id,0,"SUCCEEDED",1L), second=item(id,1,"SUCCEEDED",1L), third=item(id,2,"SUCCEEDED",1L);
        long failed=item(id,3,"FAILED",1L);
        for(long image:List.of(first,second,third)) capture(reservation,3,"image-delivered:"+image+":0");
        TenantContext.runAs(tenant,()->{ billing.release(reservation,"image-failed-test","Failed image refund"); return null; });

        var report=logs(null,null,null,null,null,0,20);
        assertThat(report.summary().chargedCredits()).isEqualTo(9);
        assertThat(report.summary().releasedCredits()).isEqualTo(3);
        assertThat(report.summary().heldCredits()).isZero();
        var row=report.records().items().getFirst();
        assertThat(row.quantity()).isEqualTo(4); assertThat(row.succeeded()).isEqualTo(3);
        assertThat(row.failed()).isEqualTo(1); assertThat(row.status()).isEqualTo("PARTIAL");
        var detail=TenantContext.runAs(tenant,()->usage.detail("image-"+id));
        assertThat(detail.items()).extracting(TaskUsageDtos.Item::chargedCredits).containsExactly(3L,3L,3L,0L);
        assertThat(detail.items().getLast().id()).isEqualTo(failed);
        assertThat(detail.events()).extracting(TaskUsageDtos.Event::type).containsExactly("RESERVE","CAPTURE","CAPTURE","CAPTURE","RELEASE");
    }

    @Test void singleImageRegenerationCountsOnlyNewOutputAndNeverChargesCopiedImages() {
        long parent=image(tenant,"original",3,null,null,"SUCCEEDED",null);
        String reservation=reserve("IMAGE",3);
        long id=image(tenant,"regenerated",3,parent,reservation,"SUCCEEDED","original");
        item(id,0,"SUCCEEDED",null); long generated=item(id,1,"SUCCEEDED",1L); item(id,2,"SUCCEEDED",null);
        capture(reservation,3,"image-delivered:"+generated+":0");
        var detail=TenantContext.runAs(tenant,()->usage.detail("image-"+id));
        assertThat(detail.task().quantity()).isEqualTo(1);
        assertThat(detail.task().succeeded()).isEqualTo(1);
        assertThat(detail.task().chargedCredits()).isEqualTo(3);
        assertThat(detail.items()).extracting(TaskUsageDtos.Item::reused).containsExactly(true,false,true);
        assertThat(detail.items()).extracting(TaskUsageDtos.Item::chargedCredits).containsExactly(0L,3L,0L);
    }

    @Test void digitalHumanUsesPersistedProviderModelAndActualChargeInsteadOfQuoteOrCurrentPrices() {
        String reservation=reserve("VIDEO",100);
        video(tenant,"digital","SUCCEEDED",reservation,"DIGITAL_HUMAN");
        capture(reservation,37,"video-delivered:test");
        TenantContext.runAs(tenant,()->{ billing.release(reservation,"video-refund-test","Video settlement"); return null; });
        var report=logs(null,null,"DIGITAL_HUMAN_VIDEO","Seedance 2.0",null,0,20);
        var row=report.records().items().getFirst();
        assertThat(row.model()).isEqualTo("seedance-2.0");
        assertThat(row.chargedCredits()).isEqualTo(37);
        assertThat(row.releasedCredits()).isEqualTo(63);
        assertThat(row.durationSeconds()).isEqualTo(10);
        assertThat(report.summary().chargedCredits()).isEqualTo(37);
    }

    @Test void pendingFailedCancelledAndLegacyRecordsDoNotInventConsumption() {
        String held=reserve("IMAGE",6), refund=reserve("VIDEO",50);
        image(tenant,"pending",2,null,held,"QUEUED",null);
        video(tenant,"cancelled","CANCELLED",refund,null);
        TenantContext.runAs(tenant,()->{ billing.release(refund,"cancel-refund-test","Cancelled video"); return null; });
        long legacy=video(tenant,"legacy","SUCCEEDED",null,null);
        long failed=image(tenant,"plan-failed",3,null,null,"INTERRUPTED",null);
        var report=logs(null,null,null,null,null,0,20);
        assertThat(report.summary().chargedCredits()).isZero(); assertThat(report.summary().heldCredits()).isEqualTo(6);
        assertThat(report.summary().releasedCredits()).isEqualTo(50);
        assertThat(report.records().items()).filteredOn(r->r.id().equals("video-"+legacy)).singleElement()
            .satisfies(r->assertThat(r.chargedCredits()).isNull());
        assertThat(report.records().items()).filteredOn(r->r.id().equals("image-"+failed)).singleElement()
            .satisfies(r->{assertThat(r.failed()).isEqualTo(3); assertThat(r.status()).isEqualTo("FAILED");});
    }

    @Test void paginationSummaryAndTimeFiltersUseTheWholeFilteredSet() {
        for(int index=0;index<3;index++) {
            String reservation=reserve("VIDEO",10);
            long id=video(tenant,"paged-"+index,"SUCCEEDED",reservation,"VIDEO");
            capture(reservation,10,"video-delivered:"+id);
            owner.update("UPDATE video_workflows SET created_at=?::timestamptz WHERE id=?","2026-10-15T0"+index+":00:00Z",id);
        }
        var page=logs(null,null,null,null,null,1,1);
        assertThat(page.records().total()).isEqualTo(3); assertThat(page.records().totalPages()).isEqualTo(3);
        assertThat(page.summary().chargedCredits()).isEqualTo(30);
        assertThat(page.records().items()).hasSize(1);
        var range=logs(Instant.parse("2026-10-15T01:00:00Z"),Instant.parse("2026-10-15T02:00:00Z"),"VIDEO_GENERATION",null,"SUCCEEDED",0,20);
        assertThat(range.records().total()).isEqualTo(1); assertThat(range.summary().chargedCredits()).isEqualTo(10);
        assertThat(logs(null,null,null,"%",null,0,20).records().items()).isEmpty();
    }

    @Test void authenticatedEndpointsRespectTenantIsolationAndDoNotMutateBilling() throws Exception {
        String otherReservation=TenantContext.runAs(otherTenant,()->billing.reserve("private-video","VIDEO",400,"Other tenant hold"));
        TenantContext.runAs(otherTenant,()->{ billing.capture(otherReservation,400,"video-delivered:private","Other tenant charge"); return null; });
        long mine=video(tenant,"mine","SUCCEEDED",null,null), hidden=video(otherTenant,"hidden","SUCCEEDED",otherReservation,null);
        String token=jwt.issueAccessToken(user,tenant,null);
        long before=owner.queryForObject("SELECT count(*) FROM billing_ledger",Long.class);
        mvc.perform(get("/api/task-center/logs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/task-center/logs").header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.records.total").value(1))
            .andExpect(jsonPath("$.data.records.items[0].id").value("video-"+mine))
            .andExpect(jsonPath("$.data.summary.chargedCredits").value(0));
        mvc.perform(get("/api/task-center/logs/video-"+hidden).header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
        mvc.perform(get("/api/task-center/logs").param("page","-1").header("Authorization","Bearer "+token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/task-center/logs").param("module","INVALID").header("Authorization","Bearer "+token)).andExpect(status().isBadRequest());
        assertThat(owner.queryForObject("SELECT count(*) FROM billing_ledger",Long.class)).isEqualTo(before);
    }

    @Test void retryKeepsPreviousRefundsAndUsesOnlyTheLatestActualDeliveryCharge() {
        String reservation=reserve("IMAGE",3);
        long id=image(tenant,"retried",1,null,reservation,"SUCCEEDED",null);
        long output=item(id,0,"SUCCEEDED",1L);
        TenantContext.runAs(tenant,()->{
            billing.release(reservation,"first-attempt-failed","First failure refund");
            billing.reserveMore(reservation,3,"second-attempt","Retry hold"); return null;
        });
        capture(reservation,3,"image-delivered:"+output+":1");
        var detail=TenantContext.runAs(tenant,()->usage.detail("image-"+id));
        assertThat(detail.task().chargedCredits()).isEqualTo(3);
        assertThat(detail.task().releasedCredits()).isEqualTo(3);
        assertThat(detail.task().heldCredits()).isZero();
        assertThat(detail.items().getFirst().chargedCredits()).isEqualTo(3);
        assertThat(detail.events()).extracting(TaskUsageDtos.Event::type).containsExactly("RESERVE","RELEASE","RESERVE","CAPTURE");
    }

    @Test void videoAnalysisAppearsInTheSameLedgerAndUnknownUpstreamImagesAreNotReportedAsFailed() {
        String reservation=reserve("VIDEO_ANALYSIS",1);
        long asset=owner.queryForObject("INSERT INTO assets(tenant_id,name,type,mime_type,storage_key,status) "
            +"VALUES (?,'source.mp4','VIDEO','video/mp4','test/source.mp4','READY') RETURNING id",Long.class,tenant);
        owner.update("INSERT INTO video_analyses(tenant_id,request_key,name,mode,asset_id,status,billing_reservation_id) "
            +"VALUES (?,'analysis-test','Video analysis','ai',?,'SUCCEEDED',?)",tenant,asset,reservation);
        capture(reservation,1,"video-analysis:test:capture");
        var analysis=logs(null,null,"VIDEO_ANALYSIS",null,null,0,20);
        assertThat(analysis.records().items()).singleElement().satisfies(row->{
            assertThat(row.unit()).isEqualTo("次"); assertThat(row.chargedCredits()).isEqualTo(1);
            assertThat(row.succeeded()).isEqualTo(1);
        });
        long id=image(tenant,"unknown",1,null,null,"UPSTREAM_UNKNOWN",null);
        item(id,0,"UPSTREAM_UNKNOWN",1L);
        var unknown=TenantContext.runAs(tenant,()->usage.detail("image-"+id)).task();
        assertThat(unknown.status()).isEqualTo("UPSTREAM_UNKNOWN");
        assertThat(unknown.failed()).isZero(); assertThat(unknown.pending()).isEqualTo(1);
    }

    @Test void invalidRangesAreRejectedAndOldVideoRequestSerializationIsUnchanged() throws Exception {
        var instant=Instant.parse("2026-10-15T00:00:00Z");
        assertThatThrownBy(()->logs(instant,instant,null,null,null,0,20)).isInstanceOf(BizException.class);
        var request=new VideoDtos.Create("old-request","prompt",List.of(),null,"SEEDANCE_2_0","9:16",10,"720p",null);
        assertThat(json.writeValueAsString(request)).doesNotContain("sourceModule");
        var digital=new VideoDtos.Create("new-request","prompt",List.of(),null,"SEEDANCE_2_0","9:16",10,"720p",null,"DIGITAL_HUMAN");
        assertThat(json.writeValueAsString(digital)).contains("\"sourceModule\":\"DIGITAL_HUMAN\"");
    }

    private TaskUsageDtos.Logs logs(Instant from,Instant until,String module,String model,String status,int page,int size) {
        return TenantContext.runAs(tenant,()->usage.logs(new TaskUsageDtos.Filter(from,until,module,model,status,page,size)));
    }
    private String reserve(String capability,long amount) {
        return TenantContext.runAs(tenant,()->billing.reserve("test:"+UUID.randomUUID(),capability,amount,"Test hold"));
    }
    private void capture(String reservation,long amount,String event) {
        TenantContext.runAs(tenant,()->{ billing.capture(reservation,amount,event,"Test delivery"); return null; });
    }
    private long image(long tenantId,String key,int count,Long parent,String reservation,String status,String originalKey) {
        String request="{\"workflow\":\"PRODUCT_SET\",\"brief\":\"Test image\",\"quality\":\"2K\",\"count\":"+count+",\"requestKey\":\""+(originalKey==null?key:originalKey)+"\"}";
        return owner.queryForObject("INSERT INTO image_creations(tenant_id,request_key,request_hash,request,parent_id,billing_reservation_id,status) "
            +"VALUES (?,?,?,?::jsonb,?,?,?) RETURNING id",Long.class,tenantId,key,"a".repeat(64),request,parent,reservation,status);
    }
    private long item(long creation,int ordinal,String status,Long withTask) {
        Long task=null;
        if(withTask!=null) task=owner.queryForObject("INSERT INTO tasks(tenant_id,type,queue,payload,idempotency_key) "
            +"VALUES (?,'IMAGE_RENDER','DEFAULT','{}',?) RETURNING id",Long.class,tenant,UUID.randomUUID().toString());
        return owner.queryForObject("INSERT INTO image_items(tenant_id,creation_id,ordinal,spec,status,model,task_id) "
            +"VALUES (?,?,?,'{\"headline\":\"Test output\"}',?,'test-image-model',?) RETURNING id",Long.class,tenant,creation,ordinal,status,task);
    }
    private long video(long tenantId,String key,String status,String reservation,String source) {
        String request=source==null?"{\"prompt\":\"Test video\"}":"{\"prompt\":\"Test video\",\"sourceModule\":\""+source+"\"}";
        return owner.queryForObject("INSERT INTO video_workflows(tenant_id,request_key,request_hash,request,model,ratio,duration_seconds,resolution,status,billing_reservation_id,billing_quote) "
            +"VALUES (?,?,?,?::jsonb,'SEEDANCE_2_0','9:16',10,'720p',?,?, '{\"providerModel\":\"seedance-2.0\",\"credits\":100}') RETURNING id",
            Long.class,tenantId,key,"b".repeat(64),request,status,reservation);
    }
}
