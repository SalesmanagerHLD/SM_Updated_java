package com.salesmanager.crm.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The JVM default zone is forced to UTC (CrmApplication), so {@code LocalDate.now()} gives the
 * wrong calendar day for Indian users between 00:00 and 05:30 IST. Anything that derives a
 * business date (invoice date, financial year) must use this Clock instead.
 */
@Configuration
public class BillingClockConfig {

    @Bean
    public Clock billingClock(@Value("${app.billing.zone:Asia/Kolkata}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
