package app.tablesignal.web;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Populate only a never-used local database. Restarts preserve its current state. */
@Component
@Profile("local")
class DemoBootstrap implements ApplicationRunner {
  private final JdbcTemplate db;
  private final DemoService demo;

  DemoBootstrap(JdbcTemplate db, DemoService demo) {
    this.db = db;
    this.demo = demo;
  }

  public void run(ApplicationArguments args) {
    if (db.queryForObject("select step from demo_clock where id=1", Integer.class) == -1)
      demo.showcase();
  }
}
