package com.meterly.billing;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noCodeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.math.BigDecimal;
import java.util.List;

/**
 * PDD principle #1 as build-breaking rules: no binary floating point anywhere in billing-service, and a
 * pricing domain that is plain Java (no framework, no I/O).
 */
@AnalyzeClasses(packages = "com.meterly.billing", importOptions = ImportOption.DoNotIncludeTests.class)
class BillingArchitectureTest {

    private static final String BILLING = "com.meterly.billing..";
    private static final String DOMAIN = "com.meterly.billing.domain..";

    private static final DescribedPredicate<JavaClass> FLOATING_POINT = DescribedPredicate.describe(
            "float, double, Float or Double",
            type -> type.isEquivalentTo(float.class) || type.isEquivalentTo(double.class)
                    || type.isEquivalentTo(Float.class) || type.isEquivalentTo(Double.class));

    private static final DescribedPredicate<List<JavaClass>> ANY_FLOATING_POINT = DescribedPredicate.describe(
            "any float, double, Float or Double parameter",
            parameters -> parameters.stream().anyMatch(FLOATING_POINT));

    @ArchTest
    static final ArchRule noFloatingPointFields = noFields()
            .that().areDeclaredInClassesThat().resideInAPackage(BILLING)
            .should().haveRawType(FLOATING_POINT);

    @ArchTest
    static final ArchRule noFloatingPointReturnTypes = noMethods()
            .that().areDeclaredInClassesThat().resideInAPackage(BILLING)
            .should().haveRawReturnType(FLOATING_POINT);

    @ArchTest
    static final ArchRule noFloatingPointParameters = noCodeUnits()
            .that().areDeclaredInClassesThat().resideInAPackage(BILLING)
            .should().haveRawParameterTypes(ANY_FLOATING_POINT);

    @ArchTest
    static final ArchRule noBigDecimalConversionsThroughDouble = noClasses()
            .that().resideInAPackage(BILLING)
            .should().callConstructor(BigDecimal.class, double.class)
            .orShould().callMethod(BigDecimal.class, "valueOf", double.class)
            .orShould().callMethod(BigDecimal.class, "doubleValue")
            .orShould().callMethod(BigDecimal.class, "floatValue");

    @ArchTest
    static final ArchRule pricingDomainIsPlainJava = noClasses()
            .that().resideInAPackage(DOMAIN)
            .should().dependOnClassesThat().resideOutsideOfPackages(DOMAIN, "java.lang..", "java.math..", "java.util..");
}
