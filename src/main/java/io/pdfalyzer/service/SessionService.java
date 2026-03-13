package io.pdfalyzer.service;

import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.pdfalyzer.model.PdfSession;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class SessionService {

    private final ConcurrentHashMap<String, PdfSession> sessions = new ConcurrentHashMap<>();

    @Value("${pdfalyzer.session.timeout-minutes:300}")
    private int timeoutMinutes;

    public PdfSession createSession(String filename, byte[] pdfBytes) {
        String id = UUID.randomUUID().toString();
        PdfSession session = new PdfSession(id, filename, pdfBytes);
        sessions.put(id, session);
        log.info("Created session {} for file '{}'", id, filename);
        return session;
    }

    public PdfSession getSession(String sessionId) {
        PdfSession session = sessions.get(sessionId);
        if (session == null) {
            throw new NoSuchElementException("Session not found: " + sessionId);
        }
        session.touch();
        return session;
    }

    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
        log.info("Removed session {}", sessionId);
    }

    @Scheduled(fixedRate = 60000)
    public void evictExpiredSessions() {
        long now = System.currentTimeMillis();
        long timeoutMs = timeoutMinutes * 60L * 1000L;
        sessions.entrySet().removeIf(entry -> {
            boolean expired = (now - entry.getValue().getLastAccessTime()) > timeoutMs;
            if (expired) {
                log.info("Evicting expired session {}", entry.getKey());
            }
            return expired;
        });
    }
}
