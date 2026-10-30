package com.example.agend_ai.service;

import com.example.agend_ai.entity.Item;
import com.example.agend_ai.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** MVP search over decrypted entities; bounded deployments only, no plaintext DB indexes. */
@Service @RequiredArgsConstructor
public class ItemSearchService {
    private final ItemRepository items;
    public record Candidate(Item item, double score) {}
    public List<Candidate> search(String rawQuery, int limit) {
        String query = normalize(rawQuery);
        if (query.isBlank()) return List.of();
        List<Candidate> found = new ArrayList<>();
        for (Item item : items.findAll()) {
            String title = normalize(item.getTitle());
            String description = normalize(item.getDescription());
            double score = title.equals(query) ? 1.0 : title.contains(query) ? 0.85 : description.contains(query) ? 0.70 : 0;
            if (score == 0) {
                Set<String> words = new HashSet<>(Arrays.asList(query.split("\\s+")));
                words.removeIf(w -> w.length() < 3);
                if (!words.isEmpty()) {
                    long matches = words.stream().filter(w -> title.contains(w) || description.contains(w)).count();
                    score = 0.65 * matches / words.size();
                }
            }
            if (score >= 0.18) found.add(new Candidate(item, score));
        }
        found.sort(Comparator.comparingDouble(Candidate::score).reversed());
        return found.stream().limit(Math.max(1, Math.min(limit,3))).toList();
    }
    private static String normalize(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).strip(); }
    public Optional<Item> confident(List<Candidate> candidates) {
        if (candidates.isEmpty()) return Optional.empty();
        Candidate first = candidates.get(0);
        if (first.score() < 0.45) return Optional.empty();
        if (candidates.size() == 1 || first.score() - candidates.get(1).score() >= 0.12) return Optional.of(first.item());
        return Optional.empty();
    }
}
