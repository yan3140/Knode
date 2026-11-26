package com.knode.search.consumer;

import com.knode.search.consumer.config.SearchConsumerProperties;
import com.knode.search.consumer.config.SearchConsumerRuntimeConfig;
import com.knode.search.rebuild.config.SearchRebuildProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchConsumerConfigValidationTest {

    @Test
    void allowsAllDisabledDefaultsWithoutDatabaseUrl() {
        SearchConsumerProperties props = new SearchConsumerProperties();
        assertThatCode(() -> invokeValidate(props, new SearchRebuildProperties())).doesNotThrowAnyException();
    }

    @Test
    void rejectsEnablingStreamIndexerOrRebuildWhenConsumerDisabled() {
        SearchConsumerProperties streamOnly = new SearchConsumerProperties();
        streamOnly.getStream().setEnabled(true);
        assertThatThrownBy(() -> invokeValidate(streamOnly, new SearchRebuildProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("knode.search.consumer.enabled");

        SearchConsumerProperties indexerOnly = new SearchConsumerProperties();
        indexerOnly.getIndexer().setEnabled(true);
        assertThatThrownBy(() -> invokeValidate(indexerOnly, new SearchRebuildProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("knode.search.consumer.enabled");

        SearchRebuildProperties rebuildOnly = new SearchRebuildProperties();
        rebuildOnly.setEnabled(true);
        assertThatThrownBy(() -> invokeValidate(new SearchConsumerProperties(), rebuildOnly))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("knode.search.consumer.enabled");
    }

    @Test
    void rejectsRebuildWithoutIndexerOrInvalidRebuildBounds() {
        SearchConsumerProperties consumerOnly = new SearchConsumerProperties();
        consumerOnly.setEnabled(true);
        consumerOnly.getDatasource().setUrl("jdbc:h2:mem:test");

        SearchRebuildProperties rebuildEnabled = new SearchRebuildProperties();
        rebuildEnabled.setEnabled(true);
        assertThatThrownBy(() -> invokeValidate(consumerOnly, rebuildEnabled))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("indexer.enabled=true");

        consumerOnly.getIndexer().setEnabled(true);
        SearchRebuildProperties badPageSize = new SearchRebuildProperties();
        badPageSize.setEnabled(true);
        badPageSize.setSnapshotPageSize(101);
        assertThatThrownBy(() -> invokeValidate(consumerOnly, badPageSize))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("snapshot-page-size");

        SearchRebuildProperties workerWithoutRebuild = new SearchRebuildProperties();
        workerWithoutRebuild.setWorkerEnabled(true);
        assertThatThrownBy(() -> invokeValidate(consumerOnly, workerWithoutRebuild))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rebuild.enabled=true");

        SearchRebuildProperties validRebuild = new SearchRebuildProperties();
        validRebuild.setEnabled(true);
        assertThatCode(() -> invokeValidate(consumerOnly, validRebuild)).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingDatabaseUrlOrInvalidRefreshPolicyWhenEnabled() {
        SearchConsumerProperties missingUrl = new SearchConsumerProperties();
        missingUrl.setEnabled(true);
        assertThatThrownBy(() -> invokeValidate(missingUrl, new SearchRebuildProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("datasource.url");

        SearchConsumerProperties badRefresh = new SearchConsumerProperties();
        badRefresh.setEnabled(true);
        badRefresh.getDatasource().setUrl("jdbc:h2:mem:test");
        badRefresh.getIndexer().setEnabled(true);
        badRefresh.getIndexer().setRefreshPolicy("false");
        assertThatThrownBy(() -> invokeValidate(badRefresh, new SearchRebuildProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refresh-policy");
    }

    private static void invokeValidate(SearchConsumerProperties props, SearchRebuildProperties rebuildProps) throws Exception {
        SearchConsumerRuntimeConfig config = new SearchConsumerRuntimeConfig(props, rebuildProps);
        Method method = SearchConsumerRuntimeConfig.class.getDeclaredMethod("validateConfiguration");
        method.setAccessible(true);
        try {
            method.invoke(config);
        } catch (java.lang.reflect.InvocationTargetException ex) {
            if (ex.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw ex;
        }
    }
}
