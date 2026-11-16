package com.validexia;

import com.validexia.ai.AiProperties;
import com.validexia.documents.application.DocumentStorageProperties;
import com.validexia.platform.config.ClerkProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
  ClerkProperties.class,
  AiProperties.class,
  DocumentStorageProperties.class
})
public class ValidexiaApplication {

  public static void main(String[] args) {
    SpringApplication.run(ValidexiaApplication.class, args);
  }
}
