package com.shard.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Size;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String VIRTUAL_HOST = "shard.local";
    private static final int REQ_AUDIO_PERM = 1001;
    private static final int REQ_EXPORT_JSON = 1002;
    private static final int REQ_IMPORT_JSON = 1003;

    private WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean hasAudioFocus = false;
    private boolean noisyRegistered = false;

    private MediaPlayer mainPlayer;
    private long mainMediaId = -1;
    private boolean mainPrepared = false;
    private boolean mainPendingPlay = false;
    private int mainPendingSeekMs = 0;
    private int mainLoadToken = 0;

    private MediaPlayer previewPlayer;
    private long previewMediaId = -1;
    private boolean previewPrepared = false;
    private int previewStartMs = 0;
    private int previewLoadToken = 0;

    private String pendingExportJson = null;

    private final AudioManager.OnAudioFocusChangeListener focusListener = focusChange -> mainHandler.post(() -> {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            hasAudioFocus = false;
            if (mainPlayer != null && mainPrepared && mainPlayer.isPlaying()) {
                mainPlayer.pause();
                notifyPlayState(false);
            }
            if (previewPlayer != null && previewPrepared && previewPlayer.isPlaying()) {
                previewPlayer.pause();
                notifyPreviewState(false);
            }
        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (mainPlayer != null && mainPrepared) mainPlayer.setVolume(0.25f, 0.25f);
        } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            hasAudioFocus = true;
            if (mainPlayer != null && mainPrepared) mainPlayer.setVolume(1.0f, 1.0f);
        }
    });

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                mainHandler.post(() -> {
                    if (mainPlayer != null && mainPrepared && mainPlayer.isPlaying()) {
                        mainPlayer.pause();
                        notifyPlayState(false);
                    }
                    if (previewPlayer != null && previewPrepared && previewPlayer.isPlaying()) {
                        previewPlayer.pause();
                        notifyPreviewState(false);
                    }
                });
            }
        }
    };

    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            boolean active = false;
            try {
                if (mainPlayer != null && mainPrepared && mainPlayer.isPlaying()) {
                    double pos = mainPlayer.getCurrentPosition() / 1000.0;
                    double dur = mainPlayer.getDuration() / 1000.0;
                    evalJs("window.onShardProgress && window.onShardProgress('main'," + pos + "," + dur + ")");
                    active = true;
                }
                if (previewPlayer != null && previewPrepared && previewPlayer.isPlaying()) {
                    double pos = previewPlayer.getCurrentPosition() / 1000.0;
                    double dur = previewPlayer.getDuration() / 1000.0;
                    evalJs("window.onShardProgress && window.onShardProgress('preview'," + pos + "," + dur + ")");
                    active = true;
                }
            } catch (IllegalStateException ignored) {
            }
            if (active) {
                mainHandler.postDelayed(this, 250);
            }
        }
    };

    private void showCrashScreen(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        final String trace = "SHARD CRASH:\n\n" + sw.toString();

        mainHandler.post(() -> {
            try {
                ScrollView scroll = new ScrollView(this);
                scroll.setBackgroundColor(Color.BLACK);
                TextView tv = new TextView(this);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(12);
                tv.setPadding(32, 64, 32, 64);
                tv.setTextIsSelectable(true);
                tv.setText(trace);
                scroll.addView(tv);
                setContentView(scroll);
            } catch (Throwable ignored) {
            }
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

            try {
                IntentFilter noisyFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(noisyReceiver, noisyFilter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(noisyReceiver, noisyFilter);
                }
                noisyRegistered = true;
            } catch (Throwable ignored) {
            }

            webView = new WebView(this);
            webView.setBackgroundColor(Color.BLACK);
            webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
            webView.setVerticalScrollBarEnabled(false);
            webView.setHorizontalScrollBarEnabled(false);

            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(true);
            settings.setMediaPlaybackRequiresUserGesture(false);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setTextZoom(100);
            settings.setSupportZoom(false);
            settings.setBuiltInZoomControls(false);

            webView.addJavascriptInterface(new ShardBridge(), "ShardNative");
            webView.setWebViewClient(new ShardWebViewClient());

            setContentView(webView);
            setupSystemBars();

            webView.loadUrl("https://" + VIRTUAL_HOST + "/index.html");
        } catch (Throwable t) {
            showCrashScreen(t);
        }
    }

    private void setupSystemBars() {
        try {
            Window window = getWindow();
            window.setBackgroundDrawableResource(android.R.color.black);
            window.setStatusBarColor(Color.BLACK);
            window.setNavigationBarColor(Color.BLACK);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(true);
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    controller.setSystemBarsAppearance(
                            0,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    );
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                View decor = window.getDecorView();
                int flags = decor.getSystemUiVisibility();
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
                decor.setSystemUiVisibility(flags);
            }
        } catch (Throwable ignored) {
        }
    }

    private String getAudioPermissionName() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasAudioPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return checkSelfPermission(getAudioPermissionName()) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO_PERM) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            evalJs("window.onShardPermissionResult && window.onShardPermissionResult(" + granted + ")");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();

        if (requestCode == REQ_EXPORT_JSON && pendingExportJson != null) {
            final String payload = pendingExportJson;
            pendingExportJson = null;
            ioExecutor.execute(() -> {
                boolean ok = false;
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os != null) {
                        os.write(payload.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        ok = true;
                    }
                } catch (Exception ignored) {
                }
                final boolean saved = ok;
                mainHandler.post(() -> evalJs(
                        "window.onShardToast && window.onShardToast('" + (saved ? "Бэкап JSON сохранён" : "Ошибка сохранения файла") + "')"
                ));
            });
        } else if (requestCode == REQ_IMPORT_JSON) {
            ioExecutor.execute(() -> {
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    if (is == null) return;
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        bos.write(buf, 0, n);
                    }
                    String b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
                    mainHandler.post(() -> evalJs("window.onShardBackupImported && window.onShardBackupImported('" + b64 + "')"));
                } catch (Exception ignored) {
                    mainHandler.post(() -> evalJs("window.onShardToast && window.onShardToast('Не удалось прочитать файл')"));
                }
            });
        }
    }

    @Override
    public void onBackPressed() {
        if (webView == null) {
            moveTaskToBack(true);
            return;
        }
        webView.evaluateJavascript("window.handleAndroidBack ? window.handleAndroidBack() : false", value -> {
            if (!"true".equals(value)) {
                moveTaskToBack(true);
            }
        });
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(progressTicker);
        if (noisyRegistered) {
            try {
                unregisterReceiver(noisyReceiver);
            } catch (Exception ignored) {
            }
            noisyRegistered = false;
        }
        releaseMainPlayer();
        releasePreviewPlayer();
        abandonAudioFocus();
        ioExecutor.shutdownNow();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private boolean requestAudioFocus() {
        if (hasAudioFocus) return true;
        if (audioManager == null) return false;
        int res;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(focusListener)
                    .build();
            res = audioManager.requestAudioFocus(focusRequest);
        } else {
            res = audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        hasAudioFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        return hasAudioFocus;
    }

    private void abandonAudioFocus() {
        if (!hasAudioFocus || audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        } else {
            audioManager.abandonAudioFocus(focusListener);
        }
        hasAudioFocus = false;
    }

    private AudioAttributes buildMediaAttrs() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
    }

    private void ensureTickerRunning() {
        mainHandler.removeCallbacks(progressTicker);
        mainHandler.post(progressTicker);
    }

    private void notifyPlayState(boolean playing) {
        evalJs("window.onShardPlayStateChanged && window.onShardPlayStateChanged(" + playing + ")");
    }

    private void notifyPreviewState(boolean playing) {
        evalJs("window.onShardPreviewStateChanged && window.onShardPreviewStateChanged(" + playing + ")");
    }

    private void evalJs(String script) {
        if (webView == null) return;
        mainHandler.post(() -> {
            if (webView != null) {
                webView.evaluateJavascript(script, null);
            }
        });
    }

    private void releaseMainPlayer() {
        mainPrepared = false;
        mainMediaId = -1;
        if (mainPlayer != null) {
            try { mainPlayer.stop(); } catch (Exception ignored) {}
            try { mainPlayer.release(); } catch (Exception ignored) {}
            mainPlayer = null;
        }
    }

    private void releasePreviewPlayer() {
        previewPrepared = false;
        previewMediaId = -1;
        if (previewPlayer != null) {
            try { previewPlayer.stop(); } catch (Exception ignored) {}
            try { previewPlayer.release(); } catch (Exception ignored) {}
            previewPlayer = null;
        }
    }

    private void loadMainTrackInternal(long mediaId, int positionMs, boolean autoPlay) {
        if (mediaId <= 0) return;

        if (mainPlayer != null && mainPrepared && mainMediaId == mediaId) {
            if (positionMs >= 0) {
                try {
                    int dur = mainPlayer.getDuration();
                    int target = dur > 500 ? Math.min(positionMs, dur - 300) : positionMs;
                    mainPlayer.seekTo(Math.max(0, target));
                } catch (Exception ignored) {}
            }
            setMainPlayingInternal(autoPlay);
            return;
        }

        releaseMainPlayer();
        mainMediaId = mediaId;
        mainPendingPlay = autoPlay;
        mainPendingSeekMs = Math.max(0, positionMs);
        final int token = ++mainLoadToken;

        Uri contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId);
        MediaPlayer mp = new MediaPlayer();
        mainPlayer = mp;

        try {
            mp.setAudioAttributes(buildMediaAttrs());
            mp.setDataSource(getApplicationContext(), contentUri);
            mp.setOnPreparedListener(player -> {
                if (token != mainLoadToken || player != mainPlayer) return;
                mainPrepared = true;
                int dur = player.getDuration();
                if (mainPendingSeekMs > 0 && dur > 500) {
                    player.seekTo(Math.min(mainPendingSeekMs, dur - 300));
                }
                if (mainPendingPlay) {
                    if (requestAudioFocus()) {
                        player.start();
                        notifyPlayState(true);
                        ensureTickerRunning();
                    } else {
                        notifyPlayState(false);
                    }
                } else {
                    notifyPlayState(false);
                }
            });
            mp.setOnCompletionListener(player -> {
                if (token != mainLoadToken || player != mainPlayer) return;
                evalJs("window.onShardTrackEnded && window.onShardTrackEnded()");
            });
            mp.setOnErrorListener((player, what, extra) -> {
                if (token != mainLoadToken) return true;
                releaseMainPlayer();
                notifyPlayState(false);
                evalJs("window.onShardToast && window.onShardToast('Файл недоступен или повреждён')");
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            releaseMainPlayer();
            notifyPlayState(false);
        }
    }

    private void setMainPlayingInternal(boolean play) {
        mainPendingPlay = play;
        if (mainPlayer == null || !mainPrepared) {
            if (!play) notifyPlayState(false);
            return;
        }
        try {
            if (play) {
                releasePreviewPlayer();
                notifyPreviewState(false);
                if (requestAudioFocus()) {
                    mainPlayer.setVolume(1.0f, 1.0f);
                    if (!mainPlayer.isPlaying()) mainPlayer.start();
                    notifyPlayState(true);
                    ensureTickerRunning();
                } else {
                    notifyPlayState(false);
                }
            } else {
                if (mainPlayer.isPlaying()) mainPlayer.pause();
                notifyPlayState(false);
            }
        } catch (Exception ignored) {}
    }

    private void startPreviewInternal(long mediaId, int positionMs) {
        if (mediaId <= 0) return;
        setMainPlayingInternal(false);

        if (previewPlayer != null && previewPrepared && previewMediaId == mediaId) {
            try {
                if (requestAudioFocus()) {
                    previewPlayer.seekTo(Math.max(0, positionMs));
                    if (!previewPlayer.isPlaying()) previewPlayer.start();
                    notifyPreviewState(true);
                    ensureTickerRunning();
                }
            } catch (Exception ignored) {}
            return;
        }

        releasePreviewPlayer();
        previewMediaId = mediaId;
        previewStartMs = Math.max(0, positionMs);
        final int token = ++previewLoadToken;

        Uri contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId);
        MediaPlayer mp = new MediaPlayer();
        previewPlayer = mp;

        try {
            mp.setAudioAttributes(buildMediaAttrs());
            mp.setDataSource(getApplicationContext(), contentUri);
            mp.setOnPreparedListener(player -> {
                if (token != previewLoadToken || player != previewPlayer) return;
                previewPrepared = true;
                int dur = player.getDuration();
                int target = dur > 1000 ? Math.min(previewStartMs, dur - 500) : 0;
                if (target > 0) player.seekTo(target);
                if (requestAudioFocus()) {
                    player.start();
                    notifyPreviewState(true);
                    ensureTickerRunning();
                } else {
                    notifyPreviewState(false);
                }
            });
            mp.setOnCompletionListener(player -> {
                if (token != previewLoadToken || player != previewPlayer) return;
                try {
                    int dur = player.getDuration();
                    int target = dur > 1000 ? Math.min(previewStartMs, dur - 500) : 0;
                    player.seekTo(target);
                    player.start();
                } catch (Exception ignored) {}
            });
            mp.setOnErrorListener((player, what, extra) -> {
                if (token != previewLoadToken) return true;
                releasePreviewPlayer();
                notifyPreviewState(false);
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            releasePreviewPlayer();
            notifyPreviewState(false);
        }
    }

    private void scanMediaStoreAsync(int minDurationSec) {
        if (!hasAudioPermission()) {
            evalJs("window.onShardPermissionResult && window.onShardPermissionResult(false)");
            return;
        }

        ioExecutor.execute(() -> {
            JSONArray result = new JSONArray();
            ContentResolver resolver = getContentResolver();
            Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

            String[] projection = new String[]{
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATE_ADDED,
                    MediaStore.Audio.Media.IS_RINGTONE,
                    MediaStore.Audio.Media.IS_NOTIFICATION,
                    MediaStore.Audio.Media.IS_ALARM
            };

            String sortOrder = MediaStore.Audio.Media.DATE_ADDED + " DESC";
            long minMs = Math.max(0, minDurationSec) * 1000L;

            try (Cursor cursor = resolver.query(uri, projection, null, null, sortOrder)) {
                if (cursor != null) {
                    int colId = cursor.getColumnIndex(MediaStore.Audio.Media._ID);
                    int colTitle = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
                    int colArtist = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
                    int colDuration = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION);
                    int colName = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME);
                    int colSize = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE);
                    int colAdded = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED);
                    int colRing = cursor.getColumnIndex(MediaStore.Audio.Media.IS_RINGTONE);
                    int colNotif = cursor.getColumnIndex(MediaStore.Audio.Media.IS_NOTIFICATION);
                    int colAlarm = cursor.getColumnIndex(MediaStore.Audio.Media.IS_ALARM);

                    while (cursor.moveToNext()) {
                        int isRing = colRing >= 0 ? cursor.getInt(colRing) : 0;
                        int isNotif = colNotif >= 0 ? cursor.getInt(colNotif) : 0;
                        int isAlarm = colAlarm >= 0 ? cursor.getInt(colAlarm) : 0;
                        if (isRing != 0 || isNotif != 0 || isAlarm != 0) continue;

                        long durationMs = colDuration >= 0 ? cursor.getLong(colDuration) : 0;
                        if (durationMs < minMs || durationMs <= 0) continue;

                        long size = colSize >= 0 ? cursor.getLong(colSize) : 0;
                        if (size <= 0) continue;

                        long id = colId >= 0 ? cursor.getLong(colId) : 0;
                        String title = colTitle >= 0 ? cursor.getString(colTitle) : "";
                        String artist = colArtist >= 0 ? cursor.getString(colArtist) : "";
                        String displayName = colName >= 0 ? cursor.getString(colName) : "";
                        long addedSec = colAdded >= 0 ? cursor.getLong(colAdded) : 0;

                        if (artist != null && "<unknown>".equalsIgnoreCase(artist.trim())) {
                            artist = "";
                        }

                        JSONObject item = new JSONObject();
                        item.put("mediaId", id);
                        item.put("title", title != null ? title : "");
                        item.put("artist", artist != null ? artist : "");
                        item.put("duration", Math.max(1, (int) (durationMs / 1000L)));
                        item.put("realFilename", displayName != null ? displayName : ("track_" + id));
                        item.put("size", size);
                        item.put("addedAt", addedSec > 0 ? addedSec * 1000L : System.currentTimeMillis());
                        result.put(item);
                    }
                }
            } catch (Exception ignored) {
            }

            String b64 = Base64.encodeToString(result.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            mainHandler.post(() -> evalJs("window.onShardLibraryScanned && window.onShardLibraryScanned('" + b64 + "')"));
        });
    }

    private WebResourceResponse loadAlbumArtResponse(long mediaId) {
        if (!hasAudioPermission() || mediaId <= 0) {
            return notFoundResponse();
        }
        Uri audioUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId);
        byte[] bytes = null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                Bitmap bmp = getContentResolver().loadThumbnail(audioUri, new Size(420, 420), null);
                if (bmp != null) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    bmp.compress(Bitmap.CompressFormat.JPEG, 86, out);
                    bmp.recycle();
                    bytes = out.toByteArray();
                }
            } catch (Exception ignored) {
            }
        }

        if (bytes == null) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(getApplicationContext(), audioUri);
                bytes = retriever.getEmbeddedPicture();
            } catch (Exception ignored) {
            } finally {
                try { retriever.release(); } catch (Exception ignored) {}
            }
        }

        if (bytes == null || bytes.length == 0) {
            return notFoundResponse();
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "public, max-age=86400");
        return new WebResourceResponse("image/jpeg", null, 200, "OK", headers, new ByteArrayInputStream(bytes));
    }

    private WebResourceResponse notFoundResponse() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, new ByteArrayInputStream(new byte[0]));
    }

    private class ShardWebViewClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (uri == null || !VIRTUAL_HOST.equals(uri.getHost())) {
                return super.shouldInterceptRequest(view, request);
            }

            String path = uri.getPath();
            if (path == null || path.equals("/") || path.equals("/index.html")) {
                try {
                    InputStream is = getAssets().open("index.html");
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Cache-Control", "no-store");
                    return new WebResourceResponse("text/html", "UTF-8", 200, "OK", headers, is);
                } catch (Exception e) {
                    return notFoundResponse();
                }
            }

            if (path.startsWith("/art/")) {
                try {
                    long id = Long.parseLong(path.substring(5));
                    return loadAlbumArtResponse(id);
                } catch (Exception e) {
                    return notFoundResponse();
                }
            }

            return notFoundResponse();
        }
    }

    private class ShardBridge {

        @JavascriptInterface
        public boolean hasPermission() {
            return hasAudioPermission();
        }

        @JavascriptInterface
        public void requestPermission() {
            mainHandler.post(() -> {
                if (hasAudioPermission()) {
                    evalJs("window.onShardPermissionResult && window.onShardPermissionResult(true)");
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    requestPermissions(new String[]{getAudioPermissionName()}, REQ_AUDIO_PERM);
                }
            });
        }

        @JavascriptInterface
        public void scanLibrary(int minDurationSec) {
            scanMediaStoreAsync(minDurationSec);
        }

        @JavascriptInterface
        public void loadTrack(long mediaId, int positionMs, boolean autoPlay) {
            mainHandler.post(() -> loadMainTrackInternal(mediaId, positionMs, autoPlay));
        }

        @JavascriptInterface
        public void setPlaying(boolean play) {
            mainHandler.post(() -> setMainPlayingInternal(play));
        }

        @JavascriptInterface
        public void seekTo(int positionMs) {
            mainHandler.post(() -> {
                mainPendingSeekMs = Math.max(0, positionMs);
                if (mainPlayer != null && mainPrepared) {
                    try {
                        int dur = mainPlayer.getDuration();
                        int target = dur > 500 ? Math.min(mainPendingSeekMs, dur - 250) : mainPendingSeekMs;
                        mainPlayer.seekTo(Math.max(0, target));
                    } catch (Exception ignored) {}
                }
            });
        }

        @JavascriptInterface
        public void startPreview(long mediaId, int positionMs) {
            mainHandler.post(() -> startPreviewInternal(mediaId, positionMs));
        }

        @JavascriptInterface
        public void setPreviewPlaying(boolean play) {
            mainHandler.post(() -> {
                if (previewPlayer == null || !previewPrepared) {
                    if (!play) notifyPreviewState(false);
                    return;
                }
                try {
                    if (play) {
                        setMainPlayingInternal(false);
                        if (requestAudioFocus()) {
                            if (!previewPlayer.isPlaying()) previewPlayer.start();
                            notifyPreviewState(true);
                            ensureTickerRunning();
                        }
                    } else {
                        if (previewPlayer.isPlaying()) previewPlayer.pause();
                        notifyPreviewState(false);
                    }
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void seekPreview(int positionMs) {
            mainHandler.post(() -> {
                if (previewPlayer != null && previewPrepared) {
                    try {
                        int dur = previewPlayer.getDuration();
                        int target = dur > 500 ? Math.min(Math.max(0, positionMs), dur - 250) : 0;
                        previewPlayer.seekTo(target);
                    } catch (Exception ignored) {}
                }
            });
        }

        @JavascriptInterface
        public void stopPreview() {
            mainHandler.post(() -> {
                releasePreviewPlayer();
                notifyPreviewState(false);
            });
        }

        @JavascriptInterface
        public void haptic(int level) {
            mainHandler.post(() -> {
                if (webView == null) return;
                int effect = level >= 2
                        ? HapticFeedbackConstants.VIRTUAL_KEY
                        : HapticFeedbackConstants.CLOCK_TICK;
                webView.performHapticFeedback(effect, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            });
        }

        @JavascriptInterface
        public void exportBackup(String jsonString, String filename) {
            mainHandler.post(() -> {
                pendingExportJson = jsonString;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                intent.putExtra(Intent.EXTRA_TITLE, filename);
                try {
                    startActivityForResult(intent, REQ_EXPORT_JSON);
                } catch (Exception e) {
                    pendingExportJson = null;
                    evalJs("window.onShardToast && window.onShardToast('Не удалось открыть проводник')");
                }
            });
        }

        @JavascriptInterface
        public void importBackup() {
            mainHandler.post(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain"});
                try {
                    startActivityForResult(intent, REQ_IMPORT_JSON);
                } catch (Exception e) {
                    evalJs("window.onShardToast && window.onShardToast('Не удалось открыть проводник')");
                }
            });
        }
    }
}
