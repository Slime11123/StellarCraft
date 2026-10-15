package dev.stellarcraft.world;

import java.util.Set;

/** Remember provenance before the current native registry suppresses vanilla AI. */
final class NativeMobAiState {
    private static final String PREFIX="stellarcraft.ai.original.";
    private NativeMobAiState() {}
    static String captureTag(Set<String> tags,boolean previouslyNative,boolean noAi) {
        if(tags.stream().anyMatch(tag->tag.startsWith(PREFIX))) return null;
        // An old native entity's saved NoAI may already have been written by us.
        return PREFIX+(previouslyNative?"unknown":noAi?"noai_true":"noai_false");
    }
}
