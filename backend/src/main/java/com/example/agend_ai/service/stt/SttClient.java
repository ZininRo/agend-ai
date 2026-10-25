package com.example.agend_ai.service.stt;

/** Provider-neutral speech-to-text adapter. Input is normalized mono Ogg/Opus. */
public interface SttClient {
    SttResult recognize(byte[] oggOpus);
}