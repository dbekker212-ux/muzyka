package com.muzyka.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.File;

/**
 * Foreground-сервис, который реально проигрывает звук через MediaPlayer,
 * держит MediaSession (для шторки/наушников/блокировки) и уведомление с кнопками управления.
 * Живёт независимо от Activity/WebView, поэтому музыка продолжает играть в фоне.
 *
 * Чтобы смена треков не зависела от того, успеет ли WebView выполнить JS в момент
 * окончания трека (в фоне это не гарантировано), сервис умеет заранее готовить
 * следующий трек (prepareNext) и переключаться на него сам, без ожидания JS.
 */
public class PlayerService extends Service {

    public static final String CHANNEL_ID = "muzyka_playback";
    private static final int NOTIF_ID = 1;

    public static final String ACTION_PLAY = "com.muzyka.player.PLAY";
    public static final String ACTION_PAUSE = "com.muzyka.player.PAUSE";
    public static final String ACTION_NEXT = "com.muzyka.player.NEXT";
    public static final String ACTION_PREV = "com.muzyka.player.PREV";
    public static final String ACTION_STOP = "com.muzyka.player.STOP";

    public interface Callback {
        void onProgress(double curSec, double durSec);
        void onStateChanged(boolean playing);
        void onEnded();
        void onAutoAdvanced(String newId);
        void onError(String message);
        void onRemoteNext();
        void onRemotePrev();
    }

    public class LocalBinder extends Binder {
        PlayerService getService() { return PlayerService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private MediaPlayer player;
    private MediaSession mediaSession;
    private Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable ticker;

    private String curId, curTitle = "", curArtist = "";
    private Bitmap curCover;
    private String currentPath;

    private MediaPlayer nextPlayer;
    private boolean nextPrepared = false;
    private String nextId, nextTitle = "", nextArtist = "";
    private Bitmap nextCover;
    private String nextPath;

    private final MediaPlayer.OnErrorListener errorListener = (mp, what, extra) -> {
        if (callback != null) callback.onError("playback error " + what);
        return true;
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        setupMediaSession();
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    public void setCallback(Callback cb) { this.callback = cb; }

    public String getCurrentId() { return curId; }
    public boolean hasActiveTrack() { return player != null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Воспроизведение музыки", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private void setupMediaSession() {
        mediaSession = new MediaSession(this, "Muzyka");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { if (callback != null) callback.onRemoteNext(); }
            @Override public void onSkipToPrevious() { if (callback != null) callback.onRemotePrev(); }
            @Override public void onSeekTo(long pos) { seekTo((int) pos); }
            @Override public void onStop() { stopSelfAll(); }
        });
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_PLAY: resume(); break;
                case ACTION_PAUSE: pause(); break;
                case ACTION_NEXT: if (callback != null) callback.onRemoteNext(); break;
                case ACTION_PREV: if (callback != null) callback.onRemotePrev(); break;
                case ACTION_STOP: stopSelfAll(); break;
                default: break;
            }
        }
        return START_NOT_STICKY;
    }

    /** Основной запуск/смена трека по явному действию (тап по треку, свайп, кнопки). */
    public void playFile(String path, String id, String title, String artist, Bitmap cover, int startPosMs) {
        stopTicker();
        clearNext(); // ручная смена — заранее подготовленный "следующий" трек больше не актуален
        String oldPath = currentPath;
        try {
            if (player != null) {
                player.reset();
            } else {
                player = new MediaPlayer();
                player.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
            }
            curId = id; curTitle = title; curArtist = artist; curCover = cover;
            currentPath = path;
            player.setDataSource(path);
            player.setOnPreparedListener(mp -> {
                if (startPosMs > 0) mp.seekTo(startPosMs);
                mp.start();
                onPlaybackStarted();
            });
            player.setOnCompletionListener(mp -> handleCompletion());
            player.setOnErrorListener(errorListener);
            player.prepareAsync();
            deleteQuietly(oldPath);
        } catch (Exception e) {
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    /** Заранее готовим следующий трек, чтобы окончание текущего не зависело от JS в фоне. */
    public void prepareNext(String path, String id, String title, String artist, Bitmap cover) {
        deleteQuietly(nextPath);
        try {
            nextPlayer = new MediaPlayer();
            nextPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            nextId = id; nextTitle = title; nextArtist = artist; nextCover = cover;
            nextPath = path;
            nextPrepared = false;
            nextPlayer.setDataSource(path);
            nextPlayer.setOnPreparedListener(mp -> nextPrepared = true);
            nextPlayer.setOnErrorListener(errorListener);
            nextPlayer.prepareAsync();
        } catch (Exception ignored) { clearNext(); }
    }

    public void clearNext() {
        nextPrepared = false;
        if (nextPlayer != null) {
            try { nextPlayer.reset(); nextPlayer.release(); } catch (Exception ignored) {}
            nextPlayer = null;
        }
        deleteQuietly(nextPath);
        nextPath = null; nextId = null; nextTitle = ""; nextArtist = ""; nextCover = null;
    }

    /** Трек закончился: если следующий уже готов — переключаемся сами, без ожидания JS. */
    private void handleCompletion() {
        stopTicker();
        if (nextPrepared && nextPlayer != null) {
            MediaPlayer old = player;
            String oldPath = currentPath;
            player = nextPlayer;
            curId = nextId; curTitle = nextTitle; curArtist = nextArtist; curCover = nextCover;
            currentPath = nextPath;
            nextPlayer = null; nextPrepared = false; nextPath = null; nextId = null; nextTitle = ""; nextArtist = ""; nextCover = null;
            player.setOnCompletionListener(mp -> handleCompletion());
            player.setOnErrorListener(errorListener);
            try { old.release(); } catch (Exception ignored) {}
            deleteQuietly(oldPath);
            try {
                player.start();
                onPlaybackStarted();
                if (callback != null) callback.onAutoAdvanced(curId);
            } catch (Exception e) {
                if (callback != null) callback.onError(e.getMessage());
            }
        } else if (callback != null) {
            callback.onEnded();
        }
    }

    private void onPlaybackStarted() {
        updateMetadata();
        updatePlaybackState(PlaybackState.STATE_PLAYING);
        startForeground(NOTIF_ID, buildNotification(true));
        startTicker();
        if (callback != null) callback.onStateChanged(true);
    }

    public void resume() {
        if (player == null) return;
        try {
            player.start();
            updatePlaybackState(PlaybackState.STATE_PLAYING);
            startForeground(NOTIF_ID, buildNotification(true));
            startTicker();
            if (callback != null) callback.onStateChanged(true);
        } catch (Exception ignored) {}
    }

    public void pause() {
        if (player == null) return;
        try {
            player.pause();
            updatePlaybackState(PlaybackState.STATE_PAUSED);
            stopForeground(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(false));
            stopTicker();
            if (callback != null) callback.onStateChanged(false);
        } catch (Exception ignored) {}
    }

    public void seekTo(int ms) {
        if (player == null) return;
        try { player.seekTo(ms); } catch (Exception ignored) {}
    }

    public boolean isPlaying() {
        try { return player != null && player.isPlaying(); } catch (Exception e) { return false; }
    }

    public int getCurrentPositionMs() {
        try { return player != null ? player.getCurrentPosition() : 0; } catch (Exception e) { return 0; }
    }

    public int getDurationMs() {
        try { return player != null ? player.getDuration() : 0; } catch (Exception e) { return 0; }
    }

    private void startTicker() {
        stopTicker();
        ticker = new Runnable() {
            @Override
            public void run() {
                if (player != null && callback != null) {
                    try { callback.onProgress(getCurrentPositionMs() / 1000.0, getDurationMs() / 1000.0); } catch (Exception ignored) {}
                }
                handler.postDelayed(this, 800);
            }
        };
        handler.post(ticker);
    }

    private void stopTicker() {
        if (ticker != null) handler.removeCallbacks(ticker);
    }

    private void updateMetadata() {
        android.media.MediaMetadata.Builder b = new android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, curTitle)
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, curArtist)
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, getDurationMs());
        if (curCover != null) b.putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, curCover);
        mediaSession.setMetadata(b.build());
    }

    private void updatePlaybackState(int state) {
        PlaybackState.Builder b = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_PLAY_PAUSE)
                .setState(state, getCurrentPositionMs(), 1f);
        mediaSession.setPlaybackState(b.build());
    }

    private Notification buildNotification(boolean playing) {
        Intent openIntent = new Intent(this, MainActivity.class);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent contentPI = PendingIntent.getActivity(this, 0, openIntent, piFlags);

        PendingIntent prevPI = servicePendingIntent(ACTION_PREV, 1);
        PendingIntent playPausePI = servicePendingIntent(playing ? ACTION_PAUSE : ACTION_PLAY, 2);
        PendingIntent nextPI = servicePendingIntent(ACTION_NEXT, 3);

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(curTitle)
                .setContentText(curArtist)
                .setContentIntent(contentPI)
                .setOnlyAlertOnce(true)
                .setOngoing(playing)
                .addAction(android.R.drawable.ic_media_previous, "Пред.", prevPI)
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Пауза" : "Play", playPausePI)
                .addAction(android.R.drawable.ic_media_next, "След.", nextPI)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (curCover != null) b.setLargeIcon(curCover);
        return b.build();
    }

    private PendingIntent servicePendingIntent(String action, int reqCode) {
        Intent i = new Intent(this, PlayerService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getService(this, reqCode, i, flags);
    }

    private void deleteQuietly(String path) {
        if (path == null) return;
        try { new File(path).delete(); } catch (Exception ignored) {}
    }

    public void stopSelfAll() {
        stopTicker();
        clearNext();
        try {
            if (player != null) { player.stop(); player.release(); player = null; }
        } catch (Exception ignored) {}
        deleteQuietly(currentPath);
        currentPath = null; curId = null;
        if (mediaSession != null) mediaSession.setActive(false);
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopTicker();
        clearNext();
        try { if (player != null) player.release(); } catch (Exception ignored) {}
        if (mediaSession != null) mediaSession.release();
        super.onDestroy();
    }
          }
