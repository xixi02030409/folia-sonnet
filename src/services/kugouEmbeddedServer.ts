import { registerPlugin } from '@capacitor/core';
import { isCapacitorAndroid } from '../platform/runtime';

// src/services/kugouEmbeddedServer.ts
//
// Android builds embed the MD3Music project's Rust KuGou API server
// (libkugou_server.so): a loopback HTTP server on 127.0.0.1 that implements
// the KuGouMusicApi route set with the concept (lite) protocol baked in.
// This makes the KuGou concept provider work with zero user configuration.
//
// getWebApiBase() in kugouTransport.ts reads EMBEDDED_API_BASE synchronously,
// so the port must be warmed up early (App start) via ensureEmbeddedKugouServer().

interface KugouServerPluginDef {
    start(): Promise<{ port: number }>;
    isRunning(): Promise<{ running: boolean }>;
    stop(): Promise<void>;
}

const KugouServer = registerPlugin<KugouServerPluginDef>('KugouServer');

let cachedPort: number | null = null;
let startPromise: Promise<number | null> | null = null;

// Empty when the embedded server is unavailable (desktop, or load failure on
// Android) so the manual API-base config flow remains the fallback.
export const EMBEDDED_API_BASE = '';

export const getEmbeddedApiBase = (): string => (
    cachedPort !== null && cachedPort > 0 ? `http://127.0.0.1:${cachedPort}` : ''
);

export const isEmbeddedKugouServerSupported = (): boolean => isCapacitorAndroid();

/**
 * Starts the embedded server once and caches its port. Safe to call multiple
 * times and from concurrent callers; later callers share the same promise.
 * Resolves with the port (also readable via getEmbeddedApiBase()) or null on
 * failure — callers treat null as "not available" and fall back to manual config.
 */
export const ensureEmbeddedKugouServer = (): Promise<number | null> => {
    if (!isCapacitorAndroid()) return Promise.resolve(null);
    if (cachedPort !== null && cachedPort > 0) return Promise.resolve(cachedPort);
    if (!startPromise) {
        startPromise = KugouServer.start()
            .then(({ port }) => {
                cachedPort = port > 0 ? port : null;
                return cachedPort;
            })
            .catch(() => {
                cachedPort = null;
                return null;
            });
    }
    return startPromise;
};
