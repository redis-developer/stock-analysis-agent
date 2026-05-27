package com.redis.stockanalysisagent.cache.controller;

import com.redis.stockanalysisagent.cache.CacheInspectionService;
import com.redis.stockanalysisagent.session.ChatSessionAccess;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/chat/cache")
public class ChatCacheController {

    private final CacheInspectionService cacheInspectionService;
    private final ChatSessionAccess sessionAccess;

    public ChatCacheController(
            CacheInspectionService cacheInspectionService,
            ChatSessionAccess sessionAccess
    ) {
        this.cacheInspectionService = cacheInspectionService;
        this.sessionAccess = sessionAccess;
    }

    @GetMapping
    public ResponseEntity<CacheInspectionService.CacheContents> cache(HttpServletRequest request) {
        if (sessionAccess.sessionManagementEnabled()) {
            sessionAccess.requireSessionUserId(request.getSession(false));
        }

        return ResponseEntity.ok(cacheInspectionService.inspect());
    }

    @DeleteMapping("/{cacheName}/entries")
    public ResponseEntity<Void> deleteCacheEntry(
            @PathVariable String cacheName,
            @RequestParam String key,
            HttpServletRequest request
    ) {
        if (sessionAccess.sessionManagementEnabled()) {
            sessionAccess.requireSessionUserId(request.getSession(false));
        }

        boolean deleted = cacheInspectionService.deleteEntry(cacheName, key);
        if (!deleted) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cache entry was not found.");
        }

        return ResponseEntity.noContent().build();
    }
}
