package com.meterly.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterly.billing.config.BillingProperties;
import com.meterly.testsupport.MeterlyPostgres;
import com.meterly.testsupport.MeterlyRedpanda;
import com.meterly.testsupport.TestJwtConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Starts the application with scheduling on (as in production) to prove the jobs are actually registered
 * and their {@code @Scheduled} placeholders resolve. The schedules are pushed far out so nothing fires
 * while the test runs.
 */
@SpringBootTest(properties = {
        "meterly.billing.scheduling-enabled=true",
        "meterly.billing.close-cron=0 0 2 1 1 *",
        "meterly.billing.outbox.relay-interval=PT30M"
})
@Import(TestJwtConfiguration.class)
class SchedulingWiringIT {

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    @Autowired
    private BillingProperties properties;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        MeterlyPostgres.registerDataSource(registry);
        MeterlyRedpanda.registerProperties(registry);
    }

    @Test
    void registersThePeriodCloseCronAndTheOutboxRelay() {
        var tasks = scheduledTasks.getScheduledTasks().stream().map(ScheduledTask::getTask).toList();

        assertThat(tasks).filteredOn(CronTask.class::isInstance)
                .extracting(task -> ((CronTask) task).getExpression())
                .contains("0 0 2 1 1 *");
        assertThat(tasks).filteredOn(FixedDelayTask.class::isInstance)
                .extracting(task -> ((FixedDelayTask) task).getIntervalDuration())
                .contains(Duration.ofMinutes(30));
        assertThat(properties.outbox().batchSize()).isEqualTo(100);
        assertThat(properties.schedulingEnabled()).isTrue();
    }
}
