package cn.nobeladam.twin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TwinApplication {

    public static void main(String[] args) {
        SpringApplication.run(TwinApplication.class, args);
    }
}
