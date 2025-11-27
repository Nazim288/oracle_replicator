package com.oraclereplicator.replicator;

import com.oraclereplicator.replicator.log.SvoiCustomLogger;
import com.oraclereplicator.replicator.log.SvoiSeverityEnum;
import com.oraclereplicator.replicator.logrepository.Log;
import com.oraclereplicator.replicator.logrepository.LogPartitionRepository;
import com.oraclereplicator.replicator.logrepository.LogRepository;
import com.oraclereplicator.replicator.utils.Utils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.InetAddress;
import java.net.UnknownHostException;

@Slf4j
@RequiredArgsConstructor
@EnableJpaRepositories
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class OraclebdreplicatorApplication {
    private final SvoiCustomLogger svoiCustomLogger;
    private final LogPartitionRepository logPartitionRepository;
    private final LogRepository logRepository;
    private final ConfigurableEnvironment configurableEnvironment;

    @PostConstruct
    public void startupApplication() {
        logPartitionRepository.createTodayPartition();
        svoiCustomLogger.sendInternal("startService", "Start Service", "Started service", SvoiSeverityEnum.ONE);
        checkConfigChanges();
    }
    public static void main(String[] args) {
        SpringApplication.run(OraclebdreplicatorApplication.class, args);
    }
    private void checkConfigChanges() {
        String props = Utils.getSources(configurableEnvironment.getPropertySources());
        String propsHash = Utils.getHash(props, "SHA-256");
        String localHostName = getHostName();

        Log logEntity = logRepository.findLatestByType("checkConfig", localHostName);
        if (logEntity == null) {
            svoiCustomLogger.sendInternal("checkConfig", "Check Config", propsHash, SvoiSeverityEnum.ONE);
        } else {
            String prevHash = StringUtils.trim(
                    StringUtils.substringBetween(logEntity.getLog(), "msg=", "deviceProcessName=")
            );
            if (!StringUtils.equals(prevHash, propsHash)) {
                svoiCustomLogger.sendInternal("checkConfig", "Check Config", propsHash, SvoiSeverityEnum.ONE);
            }
        }
    }

    private String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return InetAddress.getLoopbackAddress().getHostName();
        }
    }

    @PreDestroy
    public void shutdownApplication() {
        svoiCustomLogger.sendInternal("stopService", "Stop Service", "Stopped service", SvoiSeverityEnum.ONE);
    }

}
