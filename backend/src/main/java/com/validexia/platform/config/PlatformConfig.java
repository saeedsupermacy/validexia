package com.validexia.platform.config;

import com.validexia.platform.tenancy.TenantTransactionManager;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class PlatformConfig {

  /** The single transaction manager for the app: tenant-context aware, per M02-1 v2.6 §1. */
  @Bean
  public PlatformTransactionManager transactionManager(DataSource dataSource) {
    return new TenantTransactionManager(dataSource);
  }

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
