import { registerPlugin } from '@capacitor/core';
import { isCapacitorAndroid } from '../platform/runtime';

// src/services/localMusicCapacitorAdapter.ts
// Android (Capacitor) adapter for Folia's local music library.
//
// The core services speak the File System Access API, which does not exist in
// the Android WebView. This module provides handle-shaped classes backed by a
// native SAF plugin + a loopback streaming server, so localMusicService.ts
// works unmodified. All byte IO runs natively (see LocalMediaPlugin.java);
// the JS layer never base64-transcodes anything.

// ---------------------------------------------------------------------
// Native plugin definition
// ---------------------------------------------------------------------

interface LocalMediaEntry {
    path: string;
    name: string;
    size: number;
    lastModified: number;
    isDir: boolean;
}

interface LocalMediaPluginDef {
    pickDir(): Promise<{ treeUri: string; name: string }>;
    ensureServer(): Promise<{ port: number }>;
    listTree(options: { treeUri: string }): Promise<{ entries: LocalMediaEntry[] }>;
    statPath(options: { treeUri: string; path: string }): Promise<LocalMediaEntry>;
}

const LocalMedia = registerPlugin<LocalMediaPluginDef>('LocalMedia');

// ---------------------------------------------------------------------
// Server port cache
// ---------------------------------------------------------------------

let cachedServerPort: number | null = null;

async function getServerPort(): Promise<number> {
    if (cachedServerPort === null) {
        const { port } = await LocalMedia.ensureServer();
        cachedServerPort = port;
    }
    return cachedServerPort;
}

function mediaUrl(treeUri: string, relPath: string): string {
    // port resolved lazily by callers; kept sync via cached value
    if (cachedServerPort === null) {
        throw new Error('[LocalMediaAdapter] Server not started');
    }
    return `http://127.0.0.1:${cachedServerPort}/media?uri=${encodeURIComponent(treeUri)}&path=${encodeURIComponent(relPath)}`;
}

// ---------------------------------------------------------------------
// Tree index: one native full-tree listing per treeUri, shared by all
// handles of that tree. values() forces a refresh; getDirectoryHandle /
// getFileHandle reuse the cache (importFolder always calls values() first).
// ---------------------------------------------------------------------

interface FakeEntry {
    path: string;
    name: string;
    size: number;
    lastModified: number;
    isDir: boolean;
}

const treeIndexCache = new Map<string, Promise<Map<string, FakeEntry>>>();

async function loadTreeIndex(treeUri: string, forceRefresh = false): Promise<Map<string, FakeEntry>> {
    if (!forceRefresh) {
        const cached = treeIndexCache.get(treeUri);
        if (cached) return cached;
    }
    const load = (async () => {
        const { entries } = await LocalMedia.listTree({ treeUri });
        const map = new Map<string, FakeEntry>();
        for (const entry of entries) {
            map.set(entry.path, entry);
        }
        return map;
    })();
    treeIndexCache.set(treeUri, load);
    load.catch(() => treeIndexCache.delete(treeUri));
    return load;
}

export function invalidateTreeIndex(treeUri?: string): void {
    if (treeUri) treeIndexCache.delete(treeUri);
    else treeIndexCache.clear();
}

// ---------------------------------------------------------------------
// Fake handles
//
// Plain-data fields survive IndexedDB structured clone; methods live on
// the prototype and are restored by reviveLocalMusicHandle().
// ---------------------------------------------------------------------

export class FakeFileHandle {
    __foliaFake = true;
    kind = 'file' as const;
    name: string;
    treeUri: string;
    relPath: string;
    size: number;
    lastModified: number;

    constructor(init: { name: string; treeUri: string; relPath: string; size: number; lastModified: number }) {
        this.name = init.name;
        this.treeUri = init.treeUri;
        this.relPath = init.relPath;
        this.size = init.size;
        this.lastModified = init.lastModified;
    }

    async getFile(): Promise<File> {
        const port = await getServerPort();
        const url = `http://127.0.0.1:${port}/media?uri=${encodeURIComponent(this.treeUri)}&path=${encodeURIComponent(this.relPath)}`;
        const response = await fetch(url);
        if (!response.ok) {
            throw new Error(`[LocalMediaAdapter] Server responded ${response.status} for ${this.relPath}`);
        }
        const blob = await response.blob();
        const type = blob.type && blob.type !== 'application/octet-stream' ? blob.type : undefined;
        return type
            ? new File([blob], this.name, { lastModified: this.lastModified, type })
            : new File([blob], this.name, { lastModified: this.lastModified });
    }
}

export class FakeDirectoryHandle {
    __foliaFake = true;
    kind = 'directory' as const;
    name: string;
    treeUri: string;
    relPath: string;

    constructor(init: { name: string; treeUri: string; relPath: string }) {
        this.name = init.name;
        this.treeUri = init.treeUri;
        this.relPath = init.relPath;
    }

    async *values(): AsyncGenerator<FakeFileHandle | FakeDirectoryHandle> {
        // Cached listing (refreshed at import entry points via invalidateTreeIndex,
        // so every recursive values() here does NOT re-list the whole tree).
        const index = await loadTreeIndex(this.treeUri);
        const prefix = this.relPath ? `${this.relPath}/` : '';
        for (const [path, entry] of index) {
            if (!path.startsWith(prefix)) continue;
            const sub = path.slice(prefix.length);
            if (sub.includes('/')) continue; // deeper level, own dir handles them
            if (entry.isDir) {
                yield new FakeDirectoryHandle({ name: entry.name, treeUri: this.treeUri, relPath: path });
            } else {
                yield new FakeFileHandle({
                    name: entry.name,
                    treeUri: this.treeUri,
                    relPath: path,
                    size: entry.size,
                    lastModified: entry.lastModified
                });
            }
        }
    }

    async getDirectoryHandle(name: string): Promise<FakeDirectoryHandle> {
        const index = await loadTreeIndex(this.treeUri);
        const path = this.relPath ? `${this.relPath}/${name}` : name;
        const entry = index.get(path);
        if (!entry || !entry.isDir) {
            throw new Error(`[LocalMediaAdapter] Directory not found: ${path}`);
        }
        return new FakeDirectoryHandle({ name: entry.name, treeUri: this.treeUri, relPath: path });
    }

    async getFileHandle(name: string): Promise<FakeFileHandle> {
        const index = await loadTreeIndex(this.treeUri);
        const path = this.relPath ? `${this.relPath}/${name}` : name;
        const entry = index.get(path);
        if (!entry || entry.isDir) {
            throw new Error(`[LocalMediaAdapter] File not found: ${path}`);
        }
        return new FakeFileHandle({
            name: entry.name,
            treeUri: this.treeUri,
            relPath: path,
            size: entry.size,
            lastModified: entry.lastModified
        });
    }

    async queryPermission(): Promise<PermissionState> {
        return 'granted';
    }

    async requestPermission(): Promise<PermissionState> {
        return 'granted';
    }

    async isSameEntry(other: unknown): Promise<boolean> {
        return other instanceof FakeDirectoryHandle
            ? other.treeUri === this.treeUri
            : Boolean(other && (other as FakeDirectoryHandle).treeUri === this.treeUri
                && (other as FakeDirectoryHandle).__foliaFake === true);
    }
}

// ---------------------------------------------------------------------
// Revival: IndexedDB structured clone strips prototypes
// ---------------------------------------------------------------------

function hasFakeShape(value: unknown): value is Record<string, unknown> {
    return Boolean(value) && typeof value === 'object'
        && (value as Record<string, unknown>).__foliaFake === true;
}

export function reviveLocalMusicHandle<T>(value: T): T {
    if (!hasFakeShape(value)) return value;
    const proto = value.kind === 'directory' ? FakeDirectoryHandle.prototype : FakeFileHandle.prototype;
    return Object.assign(Object.create(proto), value) as unknown as T;
}

export function isFakeDirectoryHandle(value: unknown): value is FakeDirectoryHandle {
    return hasFakeShape(value) && value.kind === 'directory';
}

export function isFakeFileHandle(value: unknown): value is FakeFileHandle {
    return hasFakeShape(value) && value.kind === 'file';
}

// ---------------------------------------------------------------------
// Public entry point used by localMusicService.getImportDirectoryHandle()
// ---------------------------------------------------------------------

export const supportsCapacitorLocalMusic = (): boolean => isCapacitorAndroid();

export async function pickDirectoryCapacitor(): Promise<FakeDirectoryHandle> {
    const { treeUri, name } = await LocalMedia.pickDir();
    invalidateTreeIndex(treeUri);
    return new FakeDirectoryHandle({ name, treeUri, relPath: '' });
}
