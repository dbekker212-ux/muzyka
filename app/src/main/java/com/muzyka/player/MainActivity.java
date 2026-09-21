package com.muzyka.player;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;

/**
 * Оболочка: WebView показывает assets/index.html по адресу https://appassets.androidplatform.net/.
 * Музыка выбирается системным пикером файлов (или сканируется через MediaStore) и хранится в IndexedDB внутри WebView.
 * Реальное воспроизведение звука выполняет PlayerService (foreground-сервис с уведомлением),
 * чтобы музыка продолжала играть в фоне и управлялась из шторки уведомлений — включая
 * бесшовный переход на заранее подготовленный следующий трек, не дожидаясь JS.
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final int REQ_FILES = 1001;
    private static final int REQ_AUDIO_PERM = 1002;
    private static final int REQ_NOTIF_PERM = 1003;

    private WebView web;
    private ValueCallback<Uri[]> filesCallback;

    private PlayerService playerService;
    private boolean serviceBound = false;
    private Runnable pendingPlayAction;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            playerService = ((PlayerService.LocalBinder) service).getService();
            serviceBound = true;
            playerService.setCallback(serviceCallback);
            if (pendingPlayAction != null) {
                Runnable r = pendingPlayAction;
                pendingPlayAction = null;
                r.run();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            playerService = null;
            serviceBound = false;
        }
    };

    private final PlayerService.Callback serviceCallback = new PlayerService.Callback() {
        @Override
        public void onProgress(double curSec, double durSec) {
            runOnUiThread(() -> evalJs("window.__onNativeProgress && window.__onNativeProgress(" + curSec + "," + durSec + ")"));
        }

        @Override
        public void onStateChanged(boolean playing) {
            runOnUiThread(() -> evalJs("window.__onNativeState && window.__onNativeState(" + playing + ")"));
        }

        @Override
        public void onEnded() {
            runOnUiThread(() -> evalJs("window.__onNativeEnded && window.__onNativeEnded()"));
        }

        @Override
        public void onAutoAdvanced(String newId) {
            runOnUiThread(() -> evalJs("window.__onNativeAutoAdvance && window.__onNativeAutoAdvance(" + JSONObject.quote(String.valueOf(newId)) + ")"));
        }

        @Override
        public void onError(String message) {
            runOnUiThread(() -> evalJs("window.__onNativeError && window.__onNativeError()"));
        }

        @Override
        public void onRemoteNext() {
            runOnUiThread(() -> evalJs("window.__onRemoteNext && window.__onRemoteNext()"));
        }

        @Override
        public void onRemotePrev() {
            runOnUiThread(() -> evalJs("window.__onRemotePrev && window.__onRemotePrev()"));
        }
    };

    private void evalJs(String js) {
        if (web != null) web.evaluateJavascript(js, null);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF_PERM);
            }
        }

        // Привязываемся к сервису сразу при старте — если музыка уже играла в фоне,
        // JS сможет узнать об этом сразу после загрузки страницы (см. checkPlaybackState).
        bindPlayerServiceEarly();

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#262624"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setBuiltInZoomControls(false);
        s.setSupportZoom(false);

        web.addJavascriptInterface(new WebAppInterface(), "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme();
                if (!"http".equals(scheme) && !"https".equals(scheme)) {
                    return null; // blob:, data: и т.п. обрабатывает сам WebView
                }
                if (!HOST.equals(u.getHost())) {
                    return empty(403, "Forbidden"); // никаких внешних запросов
                }
                String path = u.getPath();
                if (path == null || path.isEmpty() || path.equals("/")) path = "/index.html";

                if (path.startsWith("/mediastore/")) {
                    try {
                        long id = Long.parseLong(path.substring("/mediastore/".length()));
                        Uri contentUri = android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                        InputStream in = getContentResolver().openInputStream(contentUri);
                        String type = getContentResolver().getType(contentUri);
                        return new WebResourceResponse(type != null ? type : "audio/mpeg", null, in);
                    } catch (Exception e) {
                        return empty(404, "Not Found");
                    }
                }

                try {
                    InputStream in = getAssets().open(path.substring(1));
                    return new WebResourceResponse(mimeFor(path), "UTF-8", in);
                } catch (IOException e) {
                    return empty(404, "Not Found");
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !HOST.equals(request.getUrl().getHost());
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filesCallback != null) filesCallback.onReceiveValue(null);
                filesCallback = callback;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("audio/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(intent, "Выберите музыку"), REQ_FILES);
                } catch (Exception e) {
                    filesCallback = null;
                    callback.onReceiveValue(null);
                    return false;
                }
                return true;
            }
        });

        web.loadUrl("https://" + HOST + "/index.html");
    }

    private boolean bindRequested = false;

    private void bindPlayerServiceEarly() {
        if (bindRequested) return;
        bindRequested = true;
        try { bindService(new Intent(this, PlayerService.class), connection, Context.BIND_AUTO_CREATE); } catch (Exception ignored) {}
    }

    private void ensureServiceBoundAndPlay(Runnable action) {
        // Всегда переводим сервис в "started"-состояние: иначе, будучи только привязанным,
        // он может быть уничтожен системой сразу после unbindService() в onDestroy — а нам
        // как раз нужно, чтобы он продолжал жить и играть музыку после закрытия экрана.
        try { startService(new Intent(this, PlayerService.class)); } catch (Exception ignored) {}
        if (serviceBound && playerService != null) {
            action.run();
        } else {
            pendingPlayAction = action;
            if (!bindRequested) {
                bindRequested = true;
                try { bindService(new Intent(this, PlayerService.class), connection, Context.BIND_AUTO_CREATE); } catch (Exception ignored) {}
            }
        }
    }

    private void requestScanPermissionThenScan() {
        String perm = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
            performScan();
        } else {
            requestPermissions(new String[]{perm}, REQ_AUDIO_PERM);
        }
    }

    private void performScan() {
        new Thread(() -> {
            JSONArray arr = new JSONArray();
            Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            String[] proj = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.SIZE};
            String sel = MediaStore.Audio.Media.IS_MUSIC + " != 0";
            try (Cursor c = getContentResolver().query(collection, proj, sel, null, null)) {
                if (c != null) {
                    int idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                    int nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
                    int sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
                    while (c.moveToNext()) {
                        JSONObject o = new JSONObject();
                        try {
                            o.put("id", c.getLong(idCol));
                            o.put("name", c.getString(nameCol));
                            o.put("size", c.getLong(sizeCol));
                            arr.put(o);
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
            final String json = arr.toString();
            runOnUiThread(() -> evalJs("window.__onScanResult && window.__onScanResult(" + json + ")"));
        }).start();
    }

    private void reportPlaybackState() {
        JSONObject o = new JSONObject();
        try {
            boolean alive = playerService != null && playerService.hasActiveTrack();
            o.put("hasSession", alive);
            o.put("playing", playerService != null && playerService.isPlaying());
            o.put("posMs", playerService != null ? playerService.getCurrentPositionMs() : 0);
            o.put("durMs", playerService != null ? playerService.getDurationMs() : 0);
            String cid = playerService != null ? playerService.getCurrentId() : null;
            o.put("id", cid != null ? cid : JSONObject.NULL);
        } catch (Exception ignored) {}
        evalJs("window.__onPlaybackStateChecked && window.__onPlaybackStateChecked(" + o.toString() + ")");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO_PERM) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                performScan();
            } else {
                evalJs("window.__onScanResult && window.__onScanResult([])");
            }
        }
    }

    private File newTrackFile() throws IOException {
        return File.createTempFile("track", ".dat", getCacheDir());
    }

    private class WebAppInterface {
        @JavascriptInterface
        public void playTrack(final String id, final String title, final String artist,
                               final String coverB64, final String audioB64, final String mime, final int posMs) {
            runOnUiThread(() -> {
                try {
                    byte[] audioBytes = Base64.decode(audioB64, Base64.DEFAULT);
                    File f = newTrackFile();
                    try (FileOutputStream fos = new FileOutputStream(f)) {
                        fos.write(audioBytes);
                    }
                    final Bitmap cover = decodeCover(coverB64);
                    ensureServiceBoundAndPlay(() -> playerService.playFile(f.getAbsolutePath(), id, title, artist, cover, posMs));
                } catch (Exception e) {
                    evalJs("window.__onNativeError && window.__onNativeError()");
                }
            });
        }

        @JavascriptInterface
        public void prepareNext(final String id, final String title, final String artist,
                                 final String coverB64, final String audioB64, final String mime) {
            runOnUiThread(() -> {
                try {
                    byte[] audioBytes = Base64.decode(audioB64, Base64.DEFAULT);
                    File f = newTrackFile();
                    try (FileOutputStream fos = new FileOutputStream(f)) {
                        fos.write(audioBytes);
                    }
                    final Bitmap cover = decodeCover(coverB64);
                    ensureServiceBoundAndPlay(() -> playerService.prepareNext(f.getAbsolutePath(), id, title, artist, cover));
                } catch (Exception ignored) { /* предзагрузка необязательна — просто не будет бесшовного перехода */ }
            });
        }

        @JavascriptInterface
        public void clearNext() {
            runOnUiThread(() -> { if (playerService != null) playerService.clearNext(); });
        }

        @JavascriptInterface
        public void resume() {
            runOnUiThread(() -> { if (playerService != null) playerService.resume(); });
        }

        @JavascriptInterface
        public void pause() {
            runOnUiThread(() -> { if (playerService != null) playerService.pause(); });
        }

        @JavascriptInterface
        public void seekTo(final int ms) {
            runOnUiThread(() -> { if (playerService != null) playerService.seekTo(ms); });
        }

        @JavascriptInterface
        public void stop() {
            runOnUiThread(() -> { if (playerService != null) playerService.stopSelfAll(); });
        }

        @JavascriptInterface
        public void scanLibrary() {
            runOnUiThread(MainActivity.this::requestScanPermissionThenScan);
        }

        @JavascriptInterface
        public void checkPlaybackState() {
            runOnUiThread(() -> {
                if (serviceBound && playerService != null) {
                    reportPlaybackState();
                } else {
                    pendingPlayAction = MainActivity.this::reportPlaybackState;
                    if (!bindRequested) {
                        bindRequested = true;
                        try {
                            bindService(new Intent(MainActivity.this, PlayerService.class), connection, Context.BIND_AUTO_CREATE);
                        } catch (Exception e) {
                            reportPlaybackState();
                        }
                    }
                }
            });
        }
    }

    private static Bitmap decodeCover(String coverB64) {
        if (coverB64 == null || coverB64.isEmpty()) return null;
        try {
            byte[] cb = Base64.decode(coverB64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(cb, 0, cb.length);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILES && filesCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            filesCallback.onReceiveValue(result);
            filesCallback = null;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Сначала даём странице закрыть плеер или меню; иначе сворачиваем приложение (музыка продолжает играть).
        web.evaluateJavascript("window.__onBack ? window.__onBack() : false", value -> {
            if (!"true".equals(value)) moveTaskToBack(true);
        });
    }

    @Override
    protected void onDestroy() {
        if (serviceBound) {
            try { unbindService(connection); } catch (Exception ignored) {}
            serviceBound = false;
        }
        // Сервис намеренно не останавливаем — музыка должна продолжать играть в фоне.
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private static WebResourceResponse empty(int code, String reason) {
        return new WebResourceResponse("text/plain", "UTF-8", code, reason, new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
    }

    private static String mimeFor(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }
}
