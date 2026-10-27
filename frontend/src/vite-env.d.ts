/// <reference types="vite-plugin-svgr/client" />

interface ImportMetaEnv {
  /** Адрес API, если он не на том же домене (обычно пусто) */
  readonly VITE_API_BASE?: string;
}
