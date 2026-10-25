package com.example.agend_ai.service.audio;

import org.springframework.web.multipart.MultipartFile;

public interface AudioProcessor {
    AudioPayload normalize(MultipartFile audio);
}