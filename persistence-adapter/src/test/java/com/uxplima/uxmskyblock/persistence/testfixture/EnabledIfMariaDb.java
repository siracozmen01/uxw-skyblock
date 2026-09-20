package com.uxplima.uxmskyblock.persistence.testfixture;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Signals that the annotated test class or test method should be executed only
 * when MariaDB integration tests are enabled via {@code -Dskyblock.test.database=mariadb} or {@code all}.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@EnabledIf("com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture#isMariaDbEnabled")
public @interface EnabledIfMariaDb {}
