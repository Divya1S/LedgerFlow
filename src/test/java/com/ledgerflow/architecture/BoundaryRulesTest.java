package com.ledgerflow.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Mechanical enforcement of the modular-monolith boundaries (ADR-009):
 *
 *  1. A context's persistence layer is private: only the context itself
 *     touches its repositories.
 *  2. Controllers are entry points, not an API between contexts.
 *  3. The async contexts (fraud, notification) stay decoupled from the
 *     money-path contexts: they consume Kafka events, never call in.
 */
class BoundaryRulesTest {

    private static final String[] CONTEXTS =
            {"identity", "account", "transfer", "payment", "ledger", "transactionquery", "outbox",
             "fraud", "notification"};

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.ledgerflow");
    }

    @Test
    void persistenceLayersArePrivateToTheirContext() {
        for (String context : CONTEXTS) {
            noClasses()
                    .that().resideOutsideOfPackage("com.ledgerflow." + context + "..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.ledgerflow." + context + ".persistence..")
                    .because("a context's repositories are its own; cross-context access goes through domain services")
                    .check(classes);
        }
    }

    @Test
    void controllersAreNotAnInterContextApi() {
        for (String context : CONTEXTS) {
            noClasses()
                    .that().resideOutsideOfPackage("com.ledgerflow." + context + "..")
                    .and().resideOutsideOfPackage("com.ledgerflow.common..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.ledgerflow." + context + ".api..")
                    .because("controllers are HTTP entry points, not integration points between contexts")
                    .check(classes);
        }
    }

    @Test
    void asyncConsumersNeverCallIntoTheMoneyPath() {
        noClasses()
                .that().resideInAnyPackage("com.ledgerflow.fraud..", "com.ledgerflow.notification..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.ledgerflow.transfer..", "com.ledgerflow.payment..",
                        "com.ledgerflow.ledger..", "com.ledgerflow.account..")
                .because("async contexts consume committed events; they must be extractable and unable to corrupt money")
                .check(classes);
    }
}
