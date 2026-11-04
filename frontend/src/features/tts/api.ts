import { request, requestBlob } from '../../api/http';
import type { TtsVoicesResponseDto } from '../../api/types';

export async function fetchVoices(): Promise<TtsVoicesResponseDto> {
  return request<TtsVoicesResponseDto>('/api/tts/voices');
}

export async function previewVoice(voice: string): Promise<Blob> {
  return requestBlob('/api/tts/preview', {
    method: 'POST',
    json: { voice },
    timeoutMs: 15_000,
  });
}
