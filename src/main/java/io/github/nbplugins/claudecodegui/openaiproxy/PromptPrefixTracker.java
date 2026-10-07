package io.github.nbplugins.claudecodegui.openaiproxy;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Diagnoses prompt-cache misses: remembers a hash fingerprint of the last
 * upstream request per cache key and describes how much of the next request's
 * prefix is unchanged. Only hashes and counts are kept and reported — never
 * conversation content.
 *
 * <p>The fingerprint follows the order in which the provider renders the prompt:
 * tools, then the system prompt ({@code instructions} for Codex), then each
 * conversation item ({@code input[]} for Codex, {@code messages[]} for Chat
 * Completions).
 */
final class PromptPrefixTracker {

    private static final int MAX_KEYS = 64;

    private record Fingerprint(String tools, String instructions, List<String> items) {}

    private final Map<String, Fingerprint> lastByKey = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Fingerprint> eldest) {
            return size() > MAX_KEYS;
        }
    };

    /**
     * Records {@code request} under {@code cacheKey} and returns a one-line
     * comparison with the previous request recorded under the same key.
     *
     * @param cacheKey    the prompt cache key; {@code null} keys are not tracked. The request's
     *                    {@code model} is part of the tracking key
     * @param request     the translated upstream request (Codex or Chat Completions shape)
     * @return e.g. {@code "tools=same instructions=same items_common_prefix=7/9"}, or
     *         {@code "first request"} when nothing was recorded for this key yet
     */
    synchronized String recordAndDescribe(String cacheKey, JsonNode request) {
        if (cacheKey == null) {
            return "no cache key";
        }
        Fingerprint current = fingerprint(request);
        // Side requests (title, classifier...) share the session key but run on another model
        // with another prompt; keep each model's chain separate so they don't mask each other.
        Fingerprint previous = lastByKey.put(cacheKey + '\n' + request.path("model").asText(""), current);
        if (previous == null) {
            return "first request, items=" + current.items().size();
        }
        int common = 0;
        int n = Math.min(previous.items().size(), current.items().size());
        while (common < n && previous.items().get(common).equals(current.items().get(common))) {
            common++;
        }
        return "tools=" + (previous.tools().equals(current.tools()) ? "same" : "CHANGED")
                + " instructions=" + (previous.instructions().equals(current.instructions()) ? "same" : "CHANGED")
                + " items_common_prefix=" + common + "/" + current.items().size()
                + " (previous had " + previous.items().size() + ")";
    }

    private static Fingerprint fingerprint(JsonNode request) {
        String tools = hash(request.path("tools").toString());
        String instructions;
        JsonNode items;
        if (request.has("input")) {
            instructions = hash(request.path("instructions").asText(""));
            items = request.path("input");
        } else {
            // Chat Completions: the leading system message plays the role of instructions
            JsonNode messages = request.path("messages");
            boolean leadingSystem = messages.size() > 0
                    && "system".equals(messages.get(0).path("role").asText());
            instructions = leadingSystem ? hash(messages.get(0).toString()) : hash("");
            List<JsonNode> rest = new ArrayList<>();
            for (int i = leadingSystem ? 1 : 0; i < messages.size(); i++) {
                rest.add(messages.get(i));
            }
            return new Fingerprint(tools, instructions, hashAll(rest));
        }
        List<JsonNode> list = new ArrayList<>();
        items.forEach(list::add);
        return new Fingerprint(tools, instructions, hashAll(list));
    }

    private static List<String> hashAll(List<JsonNode> nodes) {
        List<String> out = new ArrayList<>(nodes.size());
        for (JsonNode node : nodes) {
            out.add(hash(node.toString()));
        }
        return out;
    }

    private static String hash(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
