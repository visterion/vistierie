package de.vesterion.vistierie.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import java.lang.reflect.Field;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the retention contract. The 14-day window is not observable on day one, so it is
 * asserted against the parsed configuration instead of being believed.
 *
 * <p>The FILE appender is configured with a {@link SizeAndTimeBasedRollingPolicy} rather than a
 * plain time-based policy: {@code totalSizeCap} only counts already-archived files, so with a
 * plain time-based policy today's active file has no size bound until midnight and a retry
 * storm could fill the host disk before the cap ever fires.
 */
class LogbackRetentionTest {

  @TempDir Path tempLogDir;

  private LoggerContext configure() throws Exception {
    LoggerContext context = new LoggerContext();
    // Pin the log directory to a scratch path so this test never writes into a
    // production log directory picked up from an ambient VISTIERIE_LOG_DIR.
    context.putProperty("VISTIERIE_LOG_DIR", tempLogDir.toString());
    JoranConfigurator configurator = new JoranConfigurator();
    configurator.setContext(context);
    configurator.doConfigure(
        getClass().getClassLoader().getResourceAsStream("logback-spring.xml"));
    return context;
  }

  @Test
  void keepsFourteenDaysOfDailyFilesWithinASizeCap() throws Exception {
    LoggerContext context = configure();

    var appender = (RollingFileAppender<?>) context.getLogger("ROOT").getAppender("FILE");
    assertThat(appender).as("appender FILE must exist").isNotNull();

    var policy = (SizeAndTimeBasedRollingPolicy<?>) appender.getRollingPolicy();
    assertThat(policy.getMaxHistory()).isEqualTo(14);
    assertThat(policy.getFileNamePattern())
        .contains("%d{yyyy-MM-dd}")
        .contains("%i")
        .endsWith(".log.gz");

    // totalSizeCap lives on the TimeBasedRollingPolicy superclass; read it via reflection
    // (no try/catch around the reflective lookup -- a swallowed NoSuchFieldException would
    // make this assertion silently pass and look like coverage it isn't).
    Field capField = TimeBasedRollingPolicy.class.getDeclaredField("totalSizeCap");
    capField.setAccessible(true);
    var totalSizeCap = (ch.qos.logback.core.util.FileSize) capField.get(policy);
    assertThat(totalSizeCap.getSize()).isEqualTo(2L * 1024 * 1024 * 1024);

    Field maxFileSizeField =
        SizeAndTimeBasedRollingPolicy.class.getDeclaredField("maxFileSize");
    maxFileSizeField.setAccessible(true);
    var maxFileSize = (ch.qos.logback.core.util.FileSize) maxFileSizeField.get(policy);
    assertThat(maxFileSize.getSize()).isEqualTo(100L * 1024 * 1024);
  }

  @Test
  void keepsTheConsoleAppender() throws Exception {
    LoggerContext context = configure();

    assertThat(context.getLogger("ROOT").getAppender("CONSOLE"))
        .as("appender CONSOLE must survive")
        .isNotNull();
  }
}
