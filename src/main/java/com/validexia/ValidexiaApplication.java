package com.validexia;

import com.validexia.platform.config.ClerkProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ClerkProperties.class)
public class ValidexiaApplication {

  public static void main(String[] args) {
    SpringApplication.run(ValidexiaApplication.class, args);
  }
}
