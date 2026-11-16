package app.tablesignal.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TableSignalApplication {
  @org.springframework.context.annotation.Bean
  java.time.Clock clock() {
    return java.time.Clock.systemUTC();
  }

  public static void main(String[] args) {
    SpringApplication.run(TableSignalApplication.class, args);
  }
}
