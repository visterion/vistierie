package de.vesterion.vistierie.audit;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "vistierie.audit")
public class AuditProperties {

    @Min(0) @Max(3650)
    private int bodyRetentionDays = 30;

    public int getBodyRetentionDays() { return bodyRetentionDays; }
    public void setBodyRetentionDays(int v) { this.bodyRetentionDays = v; }
}
