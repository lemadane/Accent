package accent.sql.spring;

import accent.sql.Sql;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

/** Connects Accent SQL to Spring-managed transactions without requiring Spring in the compiler runtime. */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
@ConditionalOnClass(name = "org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy")
@ConditionalOnSingleCandidate(DataSource.class)
public class AccentSqlAutoConfiguration {
    @Bean
    public InitializingBean accentSqlDataSourceInitializer(DataSource dataSource) {
        return () -> Sql.setDataSource(dataSource instanceof TransactionAwareDataSourceProxy
                ? dataSource : new TransactionAwareDataSourceProxy(dataSource));
    }
}
