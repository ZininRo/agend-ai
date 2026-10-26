package com.example.agend_ai.service;

import com.example.agend_ai.common.DefaultUser;
import com.example.agend_ai.entity.Item;
import com.example.agend_ai.repository.ItemRepository;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** PostgreSQL FTS + pg_trgm search. Ranking is calculated only from database data. */
@Service
@RequiredArgsConstructor
public class ItemSearchService {
  private final NamedParameterJdbcTemplate jdbc;
  private final ItemRepository items;

  public record Candidate(Item item, double score) {}

  public List<Candidate> search(String rawQuery, int limit) {
    String query = rawQuery == null ? "" : rawQuery.strip();
    if (query.isEmpty()) return List.of();
    int safeLimit = Math.max(1, Math.min(3, limit));
    String sql = """
        select id,
               greatest(
                 similarity(lower(title), lower(:query)),
                 case when lower(title) like '%' || lower(:query) || '%' then 0.80 else 0 end,
                 case when to_tsvector('russian', title || ' ' || coalesce(description, ''))
                           @@ plainto_tsquery('russian', :query)
                      then 0.65 + least(ts_rank(
                           to_tsvector('russian', title || ' ' || coalesce(description, '')),
                           plainto_tsquery('russian', :query)), 0.35)
                      else 0 end
               ) as score
          from items
         where user_id = :userId
           and deleted_at is null
           and (
             to_tsvector('russian', title || ' ' || coalesce(description, ''))
               @@ plainto_tsquery('russian', :query)
             or similarity(lower(title), lower(:query)) >= 0.18
             or lower(title) like '%' || lower(:query) || '%'
           )
         order by score desc, updated_at desc
         limit :limit
        """;
    var params = new MapSqlParameterSource()
        .addValue("query", query)
        .addValue("userId", DefaultUser.ID)
        .addValue("limit", safeLimit);
    List<Map.Entry<UUID, Double>> ranked = jdbc.query(sql, params,
        (rs, row) -> Map.entry(rs.getObject("id", UUID.class), rs.getDouble("score")));
    if (ranked.isEmpty()) return List.of();
    Map<UUID, Item> byId = new HashMap<>();
    items.findAllById(ranked.stream().map(Map.Entry::getKey).toList()).forEach(i -> byId.put(i.getId(), i));
    return ranked.stream().filter(e -> byId.containsKey(e.getKey()))
        .map(e -> new Candidate(byId.get(e.getKey()), e.getValue())).toList();
  }

  /** A single/dominant result is safe enough for an action; close results require clarification. */
  public Optional<Item> confident(List<Candidate> candidates) {
    if (candidates.isEmpty()) return Optional.empty();
    Candidate first = candidates.get(0);
    if (first.score() < 0.45) return Optional.empty();
    if (candidates.size() == 1 || first.score() - candidates.get(1).score() >= 0.12)
      return Optional.of(first.item());
    return Optional.empty();
  }
}
