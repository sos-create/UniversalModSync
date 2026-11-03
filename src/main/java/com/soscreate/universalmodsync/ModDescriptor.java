package com.soscreate.universalmodsync;

import java.util.List;

/** Immutable metadata extracted from a Forge/Fabric/Quilt mod archive. */
public record ModDescriptor(
        String fileName,
        String loader,
        String modId,
        String version,
        List<String> dependencies,
        String environment,
        String compatibility
) {
    public ModDescriptor {
        fileName = requireText(fileName, "unknown-file");
        loader = requireText(loader, "Unknown");
        modId = requireText(modId, "unknown");
        version = requireText(version, "unknown");
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        environment = requireText(environment, "unknown");
        compatibility = requireText(compatibility, "UNKNOWN");
    }

    private static String requireText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
