package com.soscreate.universalmodsync;

import java.util.List;

/** Immutable metadata extracted from a Forge/Fabric mod archive. */
public record ModDescriptor(
        String fileName,
        String loader,
        String modId,
        String version,
        List<String> dependencies,
        String environment,
        String compatibility
) { }
