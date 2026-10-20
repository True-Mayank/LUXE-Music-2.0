
package com.luxe.music;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int PICK_MUSIC_FOLDER = 7001;
    private static final String PREFS = "luxe_native_storage";
    private static final String TREE_KEY = "music_tree_uri";
    private static final String HOST = "luxe.local";

    private WebView webView;
    private final Map<String, Uri> audioUriMap = new HashMap<>();
    private final ExecutorService scanner =
            Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private boolean pageReady = false;
    private boolean receiverRegistered = false;

    private final BroadcastReceiver playbackReceiver =
            new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!PlaybackKeepAliveService.ACTION_STATE.equals(
                    intent.getAction())) {
                return;
            }

            try {
                JSONObject state = new JSONObject();
                state.put("id", intent.getStringExtra("id"));
                state.put("title", intent.getStringExtra("title"));
                state.put("artist", intent.getStringExtra("artist"));
                state.put("playing",
                        intent.getBooleanExtra("playing", false));
                state.put("position",
                        intent.getLongExtra("position", 0));
                state.put("duration",
                        intent.getLongExtra("duration", 0));
                state.put("queueIndex",
                        intent.getIntExtra("queueIndex", -1));
                state.put("queueSize",
                        intent.getIntExtra("queueSize", 0));

                runJs(
                        "if(window.LuxeAndroidNativeState)"
                        + "window.LuxeAndroidNativeState("
                        + state.toString() + ");"
                );
            } catch (Exception ignored) {
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF09090B);
        setContentView(webView);

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setDatabaseEnabled(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(true);

        webView.addJavascriptInterface(
                new AndroidBridge(),
                "LuxeAndroid"
        );

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public WebResourceResponse shouldInterceptRequest(
                    WebView view, String url) {

                try {
                    Uri uri = Uri.parse(url);

                    if (HOST.equalsIgnoreCase(uri.getHost())
                            && uri.getPath() != null
                            && uri.getPath().startsWith("/audio/")) {

                        String token = uri.getLastPathSegment();
                        Uri source = audioUriMap.get(token);

                        if (source == null) {
                            return new WebResourceResponse(
                                    "text/plain",
                                    "UTF-8",
                                    404,
                                    "Not Found",
                                    null,
                                    null
                            );
                        }

                        InputStream stream =
                                getContentResolver().openInputStream(source);

                        if (stream == null) {
                            return new WebResourceResponse(
                                    "text/plain",
                                    "UTF-8",
                                    404,
                                    "Not Found",
                                    null,
                                    null
                            );
                        }

                        String mime =
                                getContentResolver().getType(source);

                        if (mime == null) {
                            mime = "audio/mpeg";
                        }

                        return new WebResourceResponse(
                                mime, null, stream
                        );
                    }
                } catch (Exception ignored) {
                }

                return super.shouldInterceptRequest(view, url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                pageReady = true;
                restoreSavedFolder();
                PlaybackKeepAliveService.requestState(MainActivity.this);
            }
        });

        // FIX: Specify the receiver's export status on Android 13+.
        IntentFilter filter = new IntentFilter(
                PlaybackKeepAliveService.ACTION_STATE
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                    playbackReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
            );
        } else {
            registerReceiver(playbackReceiver, filter);
        }

        receiverRegistered = true;

        webView.loadUrl("file:///android_asset/index.html");

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                    new String[]{
                            Manifest.permission.POST_NOTIFICATIONS
                    },
                    9001
            );
        }
    }

    private void restoreSavedFolder() {
        String raw = prefs.getString(TREE_KEY, null);

        if (raw == null) {
            return;
        }

        try {
            Uri tree = Uri.parse(raw);

            getContentResolver().takePersistableUriPermission(
                    tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );

            scanAndSend(tree, false);
        } catch (Exception e) {
            sendNativeError(
                    "Saved music folder is no longer accessible."
            );
        }
    }

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);

        intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        );

        startActivityForResult(intent, PICK_MUSIC_FOLDER);
    }

    @Override
    protected void onActivityResult(
            int requestCode, int resultCode, Intent data) {

        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != PICK_MUSIC_FOLDER
                || resultCode != RESULT_OK
                || data == null
                || data.getData() == null) {
            return;
        }

        Uri tree = data.getData();

        try {
            int flags = data.getFlags()
                    & Intent.FLAG_GRANT_READ_URI_PERMISSION;

            getContentResolver().takePersistableUriPermission(
                    tree, flags
            );
        } catch (Exception ignored) {
        }

        prefs.edit()
                .putString(TREE_KEY, tree.toString())
                .apply();

        scanAndSend(tree, true);
    }

    private void scanAndSend(
            Uri treeUri, boolean refreshMetadata) {

        scanner.execute(() -> {
            try {
                List<Entry> entries = queryAudioFiles(treeUri);

                synchronized (audioUriMap) {
                    audioUriMap.clear();

                    for (Entry entry : entries) {
                        audioUriMap.put(
                                token(entry.uri.toString()),
                                entry.uri
                        );
                    }
                }

                JSONArray songs = new JSONArray();

                for (Entry entry : entries) {
                    JSONObject song = new JSONObject();

                    song.put("id", entry.uri.toString());
                    song.put("name", entry.name);
                    song.put("mime", entry.mime);
                    song.put("size", entry.size);
                    song.put("lastModified", entry.lastModified);
                    song.put(
                            "url",
                            "https://" + HOST + "/audio/"
                                    + token(entry.uri.toString())
                    );
                    song.put("title", parseTitle(entry.name));
                    song.put("artist", parseArtist(entry.name));
                    song.put("album", "Local Music");
                    song.put("artwork", JSONObject.NULL);

                    songs.put(song);
                }

                JSONObject payload = new JSONObject();
                payload.put("folderUri", treeUri.toString());
                payload.put("count", songs.length());
                payload.put("songs", songs);

                sendLibraryToJavascript(payload);
                enrichSongs(entries, refreshMetadata);

            } catch (Exception e) {
                sendNativeError("Unable to scan the music folder.");
            }
        });
    }

    private List<Entry> queryAudioFiles(Uri tree) {
        List<Entry> out = new ArrayList<>();

        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree)
        );

        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };

        try (Cursor cursor = getContentResolver().query(
                children,
                projection,
                null,
                null,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME + " ASC"
        )) {
            if (cursor == null) {
                return out;
            }

            int idCol = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameCol = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeCol = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeCol = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_SIZE);
            int modCol = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED);

            while (cursor.moveToNext()) {
                if (idCol < 0 || nameCol < 0 || mimeCol < 0) {
                    continue;
                }

                String name = cursor.getString(nameCol);
                String mime = cursor.getString(mimeCol);

                if (!isAudio(name, mime)) {
                    continue;
                }

                String documentId = cursor.getString(idCol);

                Uri file = DocumentsContract.buildDocumentUriUsingTree(
                        tree, documentId
                );

                out.add(new Entry(
                        file,
                        name,
                        mime,
                        sizeCol >= 0 ? cursor.getLong(sizeCol) : 0,
                        modCol >= 0 ? cursor.getLong(modCol) : 0
                ));
            }
        } catch (Exception ignored) {
        }

        return out;
    }

    private void enrichSongs(
            List<Entry> entries, boolean refreshMetadata) {

        for (Entry entry : entries) {
            android.media.MediaMetadataRetriever retriever = null;

            try {
                retriever = new android.media.MediaMetadataRetriever();
                retriever.setDataSource(this, entry.uri);

                String title = retriever.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_TITLE);
                String artist = retriever.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST);
                String album = retriever.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM);

                if (refreshMetadata
                        && (title != null || artist != null || album != null)) {

                    JSONObject metadata = new JSONObject();
                    metadata.put("id", entry.uri.toString());
                    metadata.put("title", title);
                    metadata.put("artist", artist);
                    metadata.put("album", album);

                    sendSongMetadataUpdated(metadata);
                }

                byte[] picture = retriever.getEmbeddedPicture();

                if (picture != null && picture.length > 0) {
                    sendNativeArtworkUpdated(
                            entry.uri.toString(),
                            createArtworkDataUri(picture)
                    );
                }

            } catch (Exception ignored) {
            } finally {
                if (retriever != null) {
                    try {
                        retriever.release();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        sendNativeStatus("Artwork and metadata scan complete.");
    }

    private String createArtworkDataUri(byte[] data) {
        Bitmap bitmap = null;

        try {
            bitmap = BitmapFactory.decodeByteArray(
                    data, 0, data.length
            );

            if (bitmap == null) {
                return "";
            }

            int max = 500;

            float scale = Math.min(
                    1f,
                    max / (float) Math.max(
                            bitmap.getWidth(), bitmap.getHeight())
            );

            if (scale < 1f) {
                Bitmap scaled = Bitmap.createScaledBitmap(
                        bitmap,
                        Math.max(1, (int) (bitmap.getWidth() * scale)),
                        Math.max(1, (int) (bitmap.getHeight() * scale)),
                        true
                );

                if (scaled != bitmap) {
                    bitmap.recycle();
                }

                bitmap = scaled;
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();

            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output);

            return "data:image/jpeg;base64,"
                    + Base64.encodeToString(
                    output.toByteArray(), Base64.NO_WRAP
            );

        } catch (Exception e) {
            return "";
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    private boolean isAudio(String name, String mime) {
        if (mime != null && mime.toLowerCase().startsWith("audio/")) {
            return true;
        }

        return name != null && name.matches(
                "(?i).+\\.(mp3|m4a|flac|wav|aac|ogg|opus|webm|oga)$"
        );
    }

    private String parseTitle(String name) {
        String value = name.replaceFirst("(?i)\\.[^.]+$", "");
        int position = value.indexOf(" - ");

        return position >= 0
                ? value.substring(position + 3).trim()
                : value;
    }

    private String parseArtist(String name) {
        String value = name.replaceFirst("(?i)\\.[^.]+$", "");
        int position = value.indexOf(" - ");

        return position >= 0
                ? value.substring(0, position).trim()
                : "Unknown Artist";
    }

    private String token(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            byte[] hash = digest.digest(
                    value.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );

            StringBuilder result = new StringBuilder();

            for (byte b : hash) {
                result.append(String.format("%02x", b));
            }

            return result.toString();

        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private void runJs(String js) {
        runOnUiThread(() -> {
            if (pageReady && webView != null) {
                webView.evaluateJavascript(js, null);
            }
        });
    }

    private void sendLibraryToJavascript(JSONObject payload) {
        runJs(
                "window.LUXEAndroid&&window.LUXEAndroid.onFolderSelected("
                        + JSONObject.quote(payload.toString()) + ");"
        );
    }

    private void sendNativeArtworkUpdated(String id, String artwork) {
        runJs(
                "window.LUXEAndroid&&window.LUXEAndroid.onNativeArtworkUpdated("
                        + JSONObject.quote(id) + ","
                        + JSONObject.quote(artwork) + ");"
        );
    }

    private void sendSongMetadataUpdated(JSONObject song) {
        runJs(
                "window.LUXEAndroid&&window.LUXEAndroid.onNativeSongMetadataUpdated("
                        + JSONObject.quote(song.toString()) + ");"
        );
    }

    private void sendNativeStatus(String message) {
        runJs(
                "window.LUXEAndroid&&window.LUXEAndroid.onNativeStatus("
                        + JSONObject.quote(message) + ");"
        );
    }

    private void sendNativeError(String message) {
        runJs(
                "window.LUXEAndroid&&window.LUXEAndroid.onError("
                        + JSONObject.quote(message) + ");"
        );
    }

    private void sendFolderCleared() {
        runJs("window.LUXEAndroid&&window.LUXEAndroid.onFolderCleared();");
    }

    public class AndroidBridge {

        @JavascriptInterface
        public boolean isAndroidNative() {
            return true;
        }

        @JavascriptInterface
        public void pickMusicFolder() {
            runOnUiThread(MainActivity.this::pickFolder);
        }

        @JavascriptInterface
        public void rescanMusicFolder() {
            String raw = prefs.getString(TREE_KEY, null);

            if (raw != null) {
                scanAndSend(Uri.parse(raw), true);
            }
        }

        @JavascriptInterface
        public void forgetMusicFolder() {
            String raw = prefs.getString(TREE_KEY, null);

            if (raw != null) {
                try {
                    getContentResolver().releasePersistableUriPermission(
                            Uri.parse(raw),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );
                } catch (Exception ignored) {
                }
            }

            prefs.edit().remove(TREE_KEY).apply();

            synchronized (audioUriMap) {
                audioUriMap.clear();
            }

            sendFolderCleared();
        }

        @JavascriptInterface
        public void nativePlayQueue(
                String queueJson, int index, boolean repeatMode) {

            Intent intent = new Intent(
                    MainActivity.this,
                    PlaybackKeepAliveService.class
            );

            intent.setAction(PlaybackKeepAliveService.ACTION_PLAY_QUEUE);
            intent.putExtra("queue", queueJson);
            intent.putExtra("index", index);
            intent.putExtra("repeat", repeatMode);

            startService(intent);
        }

        @JavascriptInterface
        public void nativePlay() {
            startService(new Intent(
                    MainActivity.this,
                    PlaybackKeepAliveService.class
            ).setAction(PlaybackKeepAliveService.ACTION_PLAY));
        }

        @JavascriptInterface
        public void nativePause() {
            startService(new Intent(
                    MainActivity.this,
                    PlaybackKeepAliveService.class
            ).setAction(PlaybackKeepAliveService.ACTION_PAUSE));
        }

        @JavascriptInterface
        public void nativeNext() {
            startService(new Intent(
                    MainActivity.this,
                    PlaybackKeepAliveService.class
            ).setAction(PlaybackKeepAliveService.ACTION_NEXT));
        }

        @JavascriptInterface
        public void nativePrevious() {
            startService(new Intent(
                    MainActivity.this,
                    PlaybackKeepAliveService.class
            ).setAction(PlaybackKeepAliveService.ACTION_PREVIOUS));
        }

        @JavascriptInterface
        public void nativeStop() {
            startService(new Intent(
                    MainActivity.this,
                    PlaybackKeepAliveService.class
            ).setAction(PlaybackKeepAliveService.ACTION_STOP));
        }

        @JavascriptInterface
        public void nativeSeek(long positionMs) {
            startService(new Intent(
                    MainActivity.this,
                    PlaybackKeepAliveService.class
            ).setAction(PlaybackKeepAliveService.ACTION_SEEK)
                    .putExtra("position", positionMs));
        }

        @JavascriptInterface
        public void nativeSetRepeat(boolean value) {
            startService(new Intent(
                    MainActivity.this,
                    PlaybackKeepAliveService.class
            ).setAction(PlaybackKeepAliveService.ACTION_SET_REPEAT)
                    .putExtra("repeat", value));
        }
    }

    private static class Entry {
        final Uri uri;
        final String name;
        final String mime;
        final long size;
        final long lastModified;

        Entry(
                Uri uri, String name, String mime,
                long size, long lastModified) {

            this.uri = uri;
            this.name = name;
            this.mime = mime;
            this.size = size;
            this.lastModified = lastModified;
        }
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(playbackReceiver);
            } catch (Exception ignored) {
            }

            receiverRegistered = false;
        }

        scanner.shutdownNow();

        if (webView != null) {
            webView.destroy();
            webView = null;
        }

        super.onDestroy();
    }
}
