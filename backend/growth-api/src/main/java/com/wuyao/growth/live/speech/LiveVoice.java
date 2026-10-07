package com.wuyao.growth.live.speech;

import com.wuyao.growth.live.LiveDtos;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The console's voice identifier: {@code sample:<id>} for a cloned sample of the store, or
 * {@code builtin:<name>} for a provider voice. It is what the session configuration stores.
 */
public record LiveVoice(Long sampleId, String builtInVoice) {

    /** @return null when the value is not a voice this build understands */
    public static LiveVoice parse(String value) {
        if (value == null) return null;
        if (value.startsWith("sample:")) {
            try {
                long id = Long.parseLong(value.substring(7));
                return id > 0 ? new LiveVoice(id, null) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (value.startsWith("builtin:") && value.length() > 8 && value.length() <= 128) {
            return new LiveVoice(null, value.substring(8));
        }
        return null;
    }

    /**
     * Who speaks in a session: the host first, then the co-hosts in the order they were assigned.
     * Empty when no usable host is set. Automatic narration rotates through this list.
     */
    public static List<String> lineup(LiveDtos.Config config) {
        if (config == null || config.voiceRoles() == null) return List.of();
        String host = roles(config, "host").findFirst().orElse(null);
        if (host == null) return List.of();
        LinkedHashSet<String> lineup = new LinkedHashSet<>();
        lineup.add(host);
        roles(config, "cohost").forEach(lineup::add);
        // Sessions saved before co-hosts drove the rotation kept a separate list for it.
        if (lineup.size() == 1 && Boolean.TRUE.equals(config.rotateRoles()) && config.rotationSelection() != null) {
            config.rotationSelection().stream().filter(id -> parse(id) != null).forEach(lineup::add);
        }
        return List.copyOf(lineup);
    }

    /**
     * The co-host who answers viewers when the session is set up that way: the first one assigned.
     * Empty when the option is off or there is no co-host to take it, in which case whoever the
     * request names answers. A voice that only rotates under the old separate list never answers.
     */
    public static Optional<String> answerer(LiveDtos.Config config) {
        if (config == null || !Boolean.TRUE.equals(config.replyByCohost()) || lineup(config).isEmpty()) return Optional.empty();
        return roles(config, "cohost").findFirst();
    }

    /** Who narrates: everyone in the lineup except the co-host set aside to answer viewers. */
    public static List<String> narrators(LiveDtos.Config config) {
        String answerer = answerer(config).orElse(null);
        return lineup(config).stream().filter(voice -> !voice.equals(answerer)).toList();
    }

    private static Stream<String> roles(LiveDtos.Config config, String role) {
        return config.voiceRoles().stream()
                .filter(entry -> role.equals(entry.role()) && parse(entry.id()) != null)
                .map(LiveDtos.VoiceRole::id);
    }

    public static LiveVoice of(Long sampleId, String builtInVoice) {
        return sampleId != null ? new LiveVoice(sampleId, null) : new LiveVoice(null, builtInVoice);
    }

    public String encode() {
        return sampleId != null ? "sample:" + sampleId : "builtin:" + builtInVoice;
    }
}
