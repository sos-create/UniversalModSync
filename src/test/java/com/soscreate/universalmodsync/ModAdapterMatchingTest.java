package com.soscreate.universalmodsync;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ModAdapterMatchingTest {
    @Test
    void matchesExactArchiveFileNameIgnoringCase() {
        assertTrue(ModAdapter.targetsFile(
                Set.of("ExampleMod-1.2.0.jar"), "examplemod-1.2.0.JAR"));
    }

    @Test
    void doesNotMatchDifferentVersionOrDifferentArchive() {
        Set<String> target = Set.of("ExampleMod-1.2.0.jar");

        assertFalse(ModAdapter.targetsFile(target, "ExampleMod-1.3.0.jar"));
        assertFalse(ModAdapter.targetsFile(target, "AnotherMod-1.2.0.jar"));
    }

    @Test
    void doesNotAcceptPathsOrWildcards() {
        assertFalse(ModAdapter.targetsFile(
                Set.of("mods/ExampleMod-1.2.0.jar"), "ExampleMod-1.2.0.jar"));
        assertFalse(ModAdapter.targetsFile(
                Set.of("mods\\ExampleMod-1.2.0.jar"), "ExampleMod-1.2.0.jar"));
        assertFalse(ModAdapter.targetsFile(
                Set.of("ExampleMod-*.jar"), "ExampleMod-1.2.0.jar"));
    }

    @Test
    void handlesEmptyAndNullInputsSafely() {
        assertFalse(ModAdapter.targetsFile(null, "ExampleMod-1.2.0.jar"));
        assertFalse(ModAdapter.targetsFile(Set.of(), "ExampleMod-1.2.0.jar"));
        assertFalse(ModAdapter.targetsFile(Set.of("ExampleMod-1.2.0.jar"), null));
        assertFalse(ModAdapter.targetsFile(Set.of("ExampleMod-1.2.0.jar"), " "));
    }
}
