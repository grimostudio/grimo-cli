package io.github.samzhu.grimo;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModulithStructureTest {

    @Test
    void verifyModuleStructure() {
        var modules = ApplicationModules.of(GrimoApplication.class);
        modules.verify();
    }

    @Test
    void printModuleStructure() {
        var modules = ApplicationModules.of(GrimoApplication.class);
        modules.forEach(System.out::println);
    }
}
