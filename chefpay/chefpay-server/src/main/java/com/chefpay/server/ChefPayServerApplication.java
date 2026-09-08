package com.chefpay.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * ChefPay backend entry point. This is the "Restaurant Server" from the deployment diagram - the
 * single process every client (JavaFX POS, tablet/mobile web, kitchen display, manager
 * dashboard) connects to over REST + WebSocket. Entities/repositories live in chefpay-core
 * (outside this module's own package), hence the explicit scan annotations.
 */
@SpringBootApplication
@ComponentScan(basePackages = {"com.chefpay.server", "com.chefpay.core"})
@EntityScan(basePackages = "com.chefpay.core.domain")
@EnableJpaRepositories(basePackages = "com.chefpay.core.repository")
// Round 10: drives AiNightlySummaryScheduler's daily @Scheduled job (Feature F).
@EnableScheduling
public class ChefPayServerApplication {

    public static void main(String[] args) {
        ensureSqliteDataDirectoryExists();
        SpringApplication.run(ChefPayServerApplication.class, args);
    }

    /**
     * The sqlite-jdbc driver can create the .db file itself but will NOT create missing parent
     * directories - it just fails with "path to '...': '...' does not exist". The default `dev`
     * profile points at ./data/chefpay.db (see application.yml), and that data/ folder doesn't
     * exist on a fresh checkout (empty directories aren't tracked by git/zip), so the very first
     * run on a clean machine would otherwise fail here. Creating it up front, before Spring even
     * starts building the DataSource, fixes that for every environment without touching the
     * datasource config itself. Harmless no-op for the postgres/mysql profiles (CHEFPAY_DB_PATH
     * unset in those cases, so this just creates an unused ./data next to wherever it's run from).
     */
    private static void ensureSqliteDataDirectoryExists() {
        String dbPath = System.getenv().getOrDefault("CHEFPAY_DB_PATH",
                System.getProperty("CHEFPAY_DB_PATH", "./data/chefpay.db"));
        try {
            Path parent = Paths.get(dbPath).toAbsolutePath().normalize().getParent();
            if (parent != null) {
                File dir = parent.toFile();
                if (!dir.exists() && !dir.mkdirs()) {
                    System.err.println("Warning: could not create SQLite data directory " + dir
                            + " - startup will likely fail at the datasource step below.");
                }
            }
        } catch (Exception e) {
            // Never let this convenience step block startup outright; if something is
            // genuinely wrong with the path, the normal Hibernate/JDBC error will explain it.
            System.err.println("Warning: failed while ensuring SQLite data directory exists: " + e.getMessage());
        }
    }
}
