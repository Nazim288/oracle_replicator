package com.oraclereplicator.replicator.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "flink.job")
public class FlinkProperty {
    public void setParallelism(int parallelism) {
        this.parallelism = parallelism;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public void setRetryDelayMs(long retryDelayMs) {
        this.retryDelayMs = retryDelayMs;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public int getParallelism() {
        return parallelism;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    public String getCron() {
        return cron;
    }

    private int parallelism;           // количество потоков одновременных
    private int maxRetries = 3;        // количество повторных попыток подключения
    private long retryDelayMs = 1000;  // задержка между попытками в миллисекундах
    private String cron;               // время запусков флинк джобы
}
