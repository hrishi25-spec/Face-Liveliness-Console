package io.mosip.liveness;

import io.mosip.liveness.app.config.AppConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PadLivenessApplication {

    public static void main(String[] args) {
        // Start extracting the OpenCV natives before Spring begins refreshing,
        // so the ~6 s of file I/O overlaps Hibernate DDL and Tomcat init rather
        // than running in front of them. Beans that need the library still block
        // on AppConfig.ensureOpenCvLoaded(), so this is purely a head start.
        AppConfig.warmOpenCvAsync();
        SpringApplication.run(PadLivenessApplication.class, args);
    }
}
