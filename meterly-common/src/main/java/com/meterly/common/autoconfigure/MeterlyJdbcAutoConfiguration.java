package com.meterly.common.autoconfigure;

import com.meterly.common.tenant.TenantAwareTransactionManager;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionManager;

/**
 * Replaces Boot's default JDBC transaction manager with {@link TenantAwareTransactionManager} in every
 * service that has a DataSource, so RLS tenant binding cannot be forgotten by an individual service.
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class, before = DataSourceTransactionManagerAutoConfiguration.class)
@ConditionalOnClass({DataSource.class, DataSourceTransactionManager.class})
@ConditionalOnSingleCandidate(DataSource.class)
public class MeterlyJdbcAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(TransactionManager.class)
    public TenantAwareTransactionManager transactionManager(
            DataSource dataSource, ObjectProvider<TransactionManagerCustomizers> customizers) {
        TenantAwareTransactionManager manager = new TenantAwareTransactionManager(dataSource);
        customizers.ifAvailable(c -> c.customize(manager));
        return manager;
    }
}
