package top.izuna.foliamajor;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Capacitor plugin: native SAF directory access + loopback streaming server
 * for Folia's local music library on Android.
 *
 * All heavy IO runs here (native). The JS side only receives entry listings
 * and fetches byte ranges over http://127.0.0.1:<port>/media.
 */
@CapacitorPlugin(name = "LocalMedia")
public class LocalMediaPlugin extends Plugin {

    private LocalMediaServer server;

    // ------------------------------------------------------------------
    // pickDir: ACTION_OPEN_DOCUMENT_TREE, persistable read permission
    // ------------------------------------------------------------------
    @PluginMethod
    public void pickDir(PluginCall call) {
        Activity activity = bridge.getActivity();
        if (activity == null) {
            call.reject("Activity unavailable");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(call, intent, "onPickDirResult");
    }

    @ActivityCallback
    private void onPickDirResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.reject("User cancelled directory selection");
            return;
        }
        try {
            Uri treeUri = result.getData().getData();
            if (treeUri == null) {
                call.reject("No tree uri returned");
                return;
            }
            getContext().getContentResolver().takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);

            String rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
            // docId format: "primary:Music/Sub" -> display name "Music" or "Sub"
            String name = rootDocId;
            int colon = rootDocId.indexOf(':');
            if (colon >= 0 && colon + 1 < rootDocId.length()) {
                String pathPart = rootDocId.substring(colon + 1);
                name = pathPart.contains("/")
                        ? pathPart.substring(pathPart.lastIndexOf('/') + 1)
                        : pathPart;
            } else {
                name = "storage";
            }

            JSObject ret = new JSObject();
            ret.put("treeUri", treeUri.toString());
            ret.put("name", name);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("pickDir failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // ensureServer: start (or reuse) the loopback streaming server
    // ------------------------------------------------------------------
    @PluginMethod
    public void ensureServer(PluginCall call) {
        try {
            if (server == null || !server.isRunning()) {
                server = new LocalMediaServer(getContext());
                server.start();
            }
            JSObject ret = new JSObject();
            ret.put("port", server.getPort());
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("ensureServer failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // listTree: recursive listing of one SAF tree (native, fast).
    // Returns flat entry list with paths relative to the tree root.
    // ------------------------------------------------------------------
    @PluginMethod
    public void listTree(PluginCall call) {
        String treeUriStr = call.getString("treeUri");
        if (treeUriStr == null || treeUriStr.isEmpty()) {
            call.reject("treeUri is required");
            return;
        }
        bridge.execute(() -> {
            try {
                Uri treeUri = Uri.parse(treeUriStr);
                ContentResolver resolver = getContext().getContentResolver();
                String rootDocId = DocumentsContract.getTreeDocumentId(treeUri);

                JSONArray entries = new JSONArray();
                Deque<String> queue = new ArrayDeque<>();
                queue.add(rootDocId);

                String[] projection = {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                };

                int guard = 0;
                while (!queue.isEmpty()) {
                    if (++guard > 200000) break; // safety valve
                    String parentDocId = queue.poll();
                    Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
                    try (Cursor c = resolver.query(childrenUri, projection, null, null, null)) {
                        if (c == null) continue;
                        while (c.moveToNext()) {
                            String docId = c.getString(0);
                            String name = c.getString(1);
                            String mime = c.getString(2);
                            long size = c.isNull(3) ? 0L : c.getLong(3);
                            long lastModified = c.isNull(4) ? 0L : c.getLong(4);
                            boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);

                            String relPath = docId.length() > rootDocId.length() + 1
                                    ? docId.substring(rootDocId.length() + 1)
                                    : "";
                            if (relPath.isEmpty()) continue;

                            JSONObject entry = new JSONObject();
                            entry.put("path", relPath);
                            entry.put("name", name);
                            entry.put("size", size);
                            entry.put("lastModified", lastModified);
                            entry.put("isDir", isDir);
                            entries.put(entry);

                            if (isDir) queue.add(docId);
                        }
                    } catch (Exception perDir) {
                        // unreadable subtree: skip, keep walking
                    }
                }

                JSObject ret = new JSObject();
                ret.put("entries", entries);
                call.resolve(ret);
            } catch (Exception e) {
                call.reject("listTree failed: " + e.getMessage(), e);
            }
        });
    }

    // ------------------------------------------------------------------
    // statPath: single file/dir probe by relative path (recovery flows)
    // ------------------------------------------------------------------
    @PluginMethod
    public void statPath(PluginCall call) {
        String treeUriStr = call.getString("treeUri");
        String path = call.getString("path");
        if (treeUriStr == null || path == null || path.isEmpty()) {
            call.reject("treeUri and path are required");
            return;
        }
        try {
            Uri treeUri = Uri.parse(treeUriStr);
            String rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
            String docId = rootDocId + ":" + path;
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);

            Bundle out = new Bundle();
            Uri resolved = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getDocumentId(docUri));
            try (Cursor c = getContext().getContentResolver().query(
                    resolved,
                    new String[]{
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE,
                            DocumentsContract.Document.COLUMN_SIZE,
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED
                    },
                    null, null, null)) {
                if (c == null || !c.moveToFirst()) {
                    call.reject("not found: " + path);
                    return;
                }
                JSObject ret = new JSObject();
                ret.put("name", c.getString(0));
                ret.put("isDir", DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(1)));
                ret.put("size", c.isNull(2) ? 0L : c.getLong(2));
                ret.put("lastModified", c.isNull(3) ? 0L : c.getLong(3));
                call.resolve(ret);
            }
        } catch (Exception e) {
            call.reject("statPath failed: " + e.getMessage(), e);
        }
    }

    static String guessMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".opus")) return "audio/ogg";
        if (lower.endsWith(".aac")) return "audio/aac";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        String ext = MimeTypeMap.getFileExtensionFromUrl(fileName);
        if (ext != null) {
            String mapped = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mapped != null) return mapped;
        }
        return "application/octet-stream";
    }
}
