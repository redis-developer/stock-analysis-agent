package com.redis.stockanalysisagent.cache;

import com.redis.stockanalysisagent.chat.WorkflowProgress;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ExternalDataCacheTests {

    @Test
    void recordsCacheMissAsApiAccess() {
        ExternalApiUsageService apiUsageService = mock(ExternalApiUsageService.class);
        ExternalDataCache externalDataCache = new ExternalDataCache(
                new ConcurrentMapCacheManager("quotes"),
                mock(WorkflowProgress.class),
                apiUsageService
        );

        String value = externalDataCache.getOrLoad("quotes", "AAPL", () -> "payload");

        assertThat(value).isEqualTo("payload");
        verify(apiUsageService).recordHitForCacheName("quotes");
        assertThat(externalDataCache.drainRecordedAccesses())
                .singleElement()
                .satisfies(access -> {
                    assertThat(access.cacheName()).isEqualTo("quotes");
                    assertThat(access.key()).isEqualTo("AAPL");
                    assertThat(access.source()).isEqualTo(ExternalDataAccess.SOURCE_API);
                    assertThat(access.durationMs()).isGreaterThanOrEqualTo(0);
                });
    }

    @Test
    void recordsCacheHitAsCacheAccess() {
        ExternalApiUsageService apiUsageService = mock(ExternalApiUsageService.class);
        ExternalDataCache externalDataCache = new ExternalDataCache(
                new ConcurrentMapCacheManager("quotes"),
                mock(WorkflowProgress.class),
                apiUsageService
        );
        externalDataCache.getOrLoad("quotes", "AAPL", () -> "payload");
        externalDataCache.drainRecordedAccesses();
        clearInvocations(apiUsageService);

        String value = externalDataCache.getOrLoad("quotes", "AAPL", () -> "fresh");

        assertThat(value).isEqualTo("payload");
        verify(apiUsageService, never()).recordHitForCacheName("quotes");
        assertThat(externalDataCache.drainRecordedAccesses())
                .singleElement()
                .satisfies(access -> {
                    assertThat(access.cacheName()).isEqualTo("quotes");
                    assertThat(access.key()).isEqualTo("AAPL");
                    assertThat(access.source()).isEqualTo(ExternalDataAccess.SOURCE_CACHE);
                    assertThat(access.durationMs()).isGreaterThanOrEqualTo(0);
                });
    }

    @Test
    void disabledCachingBypassesStoredValue() {
        ExternalApiUsageService apiUsageService = mock(ExternalApiUsageService.class);
        ExternalDataCache externalDataCache = new ExternalDataCache(
                new ConcurrentMapCacheManager("quotes"),
                mock(WorkflowProgress.class),
                apiUsageService
        );
        externalDataCache.getOrLoad("quotes", "AAPL", () -> "payload");
        externalDataCache.drainRecordedAccesses();
        clearInvocations(apiUsageService);
        externalDataCache.setCachingEnabled(false);

        String value = externalDataCache.getOrLoad("quotes", "AAPL", () -> "fresh");

        assertThat(value).isEqualTo("fresh");
        verify(apiUsageService).recordHitForCacheName("quotes");
        assertThat(externalDataCache.drainRecordedAccesses())
                .singleElement()
                .satisfies(access -> assertThat(access.source()).isEqualTo(ExternalDataAccess.SOURCE_API));
        externalDataCache.clearCachingEnabled();
    }
}
