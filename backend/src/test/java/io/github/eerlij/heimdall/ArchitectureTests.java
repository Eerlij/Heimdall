package io.github.eerlij.heimdall;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;
import java.time.LocalDateTime;
import java.util.Date;

@AnalyzeClasses(packages = "io.github.eerlij.heimdall", importOptions = DoNotIncludeTests.class)
class ArchitectureTests {

    @ArchTest
    static final ArchRule noFieldInjection = GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    @ArchTest
    static final ArchRule noJavaUtilLogging = GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    /** All timestamps are timestamptz in UTC: use Instant or OffsetDateTime. */
    @ArchTest
    static final ArchRule noLocalDateTimeOrDate =
            noClasses().should().dependOnClassesThat().belongToAnyOf(LocalDateTime.class, Date.class);
}
