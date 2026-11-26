package com.knode.search.consumer;

import com.knode.search.consumer.config.SearchConsumerProperties;
import com.knode.search.consumer.config.SearchConsumerRuntimeConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchConsumerConfigValidationTest {

    @Test
    void allowsAllDisabledDefaultsWithoutDatabaseUrl() {
        SearchConsumerProperties props = new SearchConsumerProperties();
        assertThatCode(() -> invokeValidate(props)).doesNotThrowAnyException();
    }

    @Test
    void rejectsEnablingStreamOrIndexerWhenConsumerDisabled() {
        SearchConsumerProperties streamOnly = new SearchConsumerProperties();
        streamOnly.getStream().setEnabled(true);
        assertThatThrownBy(() -> invokeValidate(streamOnly))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("knode.search.consumer.enabled");

        SearchConsumerProperties indexerOnly = new SearchConsumerProperties();
        indexerOnly.getIndexer().setEnabled(true);
        assertThatThrownBy(() -> invokeValidate(indexerOnly))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("knode.search.consumer.enabled");
    }

    @Test
    void rejectsMissingDatabaseUrlOrInvalidRefreshPolicyWhenEnabled() {
        SearchConsumerProperties missingUrl = new SearchConsumerProperties();
        missingUrl.setEnabled(true);
        assertThatThrownBy(() -> invokeValidate(missingUrl))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("datasource.url");

        SearchConsumerProperties badRefresh = new SearchConsumerProperties();
        badRefresh.setEnabled(true);
        badRefresh.getDatasource().setUrl("jdbc:h2:mem:test");
        badRefresh.getIndexer().setEnabled(true);
        badRefresh.getIndexer().setRefreshPolicy("false");
        assertThatThrownBy(() -> invokeValidate(badRefresh))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refresh-policy");
    }

    private static void invokeValidate(SearchConsumerProperties props) throws Exception {
        SearchConsumerRuntimeConfig config = new SearchConsumerRuntimeConfig(props);
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
