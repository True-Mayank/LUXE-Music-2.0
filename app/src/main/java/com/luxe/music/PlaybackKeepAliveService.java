package com.luxe.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.List;

public class PlaybackKeepAliveService extends Service {

    private static final String TAG = "LUXEPlaybackService";

    private static final String CHANNEL_ID = "luxe_music_playback";
    private static final int NOTIFICATION_ID = 1201;

    public static final String ACTION_STATE = "com.luxe.music.PLAYBACK_STATE";
    private static final String ACTION_REQUEST_STATE = "com.luxe.music.REQUEST_PLAYBACK_STATE";

    public static void requestState(Context context) {
        Intent intent = new Intent(context, PlaybackKeepAliveService.class);
        intent.setAction(ACTION_REQUEST_STATE);
        context.startService(intent);
    }

    public static final String ACTION_PLAY_QUEUE = "PLAY_QUEUE";
    public static final String ACTION_PLAY = "PLAY";
    public static final String ACTION_PAUSE = "PAUSE";
    public static final String ACTION_NEXT = "NEXT";
    public static final String ACTION_PREVIOUS = "PREVIOUS";
    public static final String ACTION_SEEK = "SEEK";
    public static final String ACTION_SET_REPEAT = "SET_REPEAT";
    public static final String ACTION_STOP = "STOP";

    private static final String ACTION_NOTIFICATION_PLAY =
            "com.luxe.music.NOTIFICATION_PLAY";
    private static final String ACTION_NOTIFICATION_PAUSE =
            "com.luxe.music.NOTIFICATION_PAUSE";
    private static final String ACTION_NOTIFICATION_NEXT =
            "com.luxe.music.NOTIFICATION_NEXT";
    private static final String ACTION_NOTIFICATION_PREVIOUS =
            "com.luxe.music.NOTIFICATION_PREVIOUS";

    private MediaPlayer player;
    private MediaSession mediaSession;
    private PowerManager.WakeLock wakeLock;
    private Bitmap currentArtwork;
    private final ExecutorService artworkExecutor = Executors.newSingleThreadExecutor();

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final List<Track> queue = new ArrayList<>();

    private int currentIndex = -1;
    private int repeatMode = 0;

    private boolean isPreparing = false;
    private boolean serviceDestroyed = false;

    private String currentTitle = "LUXE Music";
    private String currentArtist = "Unknown Artist";
    private String currentAlbum = "Local Music";

    /*
     * IMPORTANT:
     * Use an anonymous Runnable so it can reschedule itself with "this".
     * This avoids the Java self-reference-in-initializer compilation error.
     */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            publishState();

            if (player != null && player.isPlaying()) {
                handler.postDelayed(this, 1000);
            }
        }
    };

    private static class Track {
        String id;
        String title;
        String artist;
        String album;
        String url;

        Track(
                String id,
                String title,
                String artist,
                String album,
                String url
        ) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.url = url;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();
        initializeMediaSession();
        initializeWakeLock();

        Log.d(TAG, "Playback service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_STICKY;
        }

        String action = intent.getAction();

        if (action == null) {
            return START_STICKY;
        }

        Log.d(TAG, "Received action: " + action);

        switch (action) {
            case ACTION_REQUEST_STATE:
                publishState();
                break;

            case ACTION_PLAY_QUEUE:
                handlePlayQueue(intent);
                break;

            case ACTION_PLAY:
            case ACTION_NOTIFICATION_PLAY:
                resumePlayback();
                break;

            case ACTION_PAUSE:
            case ACTION_NOTIFICATION_PAUSE:
                pausePlayback();
                break;

            case ACTION_NEXT:
            case ACTION_NOTIFICATION_NEXT:
                playNext();
                break;

            case ACTION_PREVIOUS:
            case ACTION_NOTIFICATION_PREVIOUS:
                playPrevious();
                break;

            case ACTION_SEEK:
                seekTo(intent);
                break;

            case ACTION_SET_REPEAT:
                setRepeatMode(intent);
                break;

            case ACTION_STOP:
                stopPlayback();
                break;

            default:
                Log.w(TAG, "Unknown action: " + action);
                break;
        }

        return START_STICKY;
    }

    private void initializeMediaSession() {
        mediaSession = new MediaSession(this, "LUXE Music");

        mediaSession.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                        | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        );

        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                resumePlayback();
            }

            @Override
            public void onPause() {
                pausePlayback();
            }

            @Override
            public void onSkipToNext() {
                playNext();
            }

            @Override
            public void onSkipToPrevious() {
                playPrevious();
            }

            @Override
            public void onSeekTo(long position) {
                if (player != null) {
                    try {
                        player.seekTo((int) position);
                        publishState();
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "Unable to seek", e);
                    }
                }
            }

            @Override
            public void onStop() {
                stopPlayback();
            }
        });

        mediaSession.setActive(true);
    }

    private void initializeWakeLock() {
        try {
            PowerManager powerManager =
                    (PowerManager) getSystemService(POWER_SERVICE);

            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "LUXEMusic:PlaybackWakeLock"
                );

                wakeLock.setReferenceCounted(false);
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to create wake lock", e);
        }
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire();
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to acquire wake lock", e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to release wake lock", e);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "LUXE Music Playback",
                    NotificationManager.IMPORTANCE_LOW
            );

            channel.setDescription(
                    "Playback controls for LUXE Music"
            );

            channel.setShowBadge(false);

            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void handlePlayQueue(Intent intent) {
        String queueJson = intent.getStringExtra("queue");

        if (queueJson == null) {
            queueJson = intent.getStringExtra("songs");
        }

        int requestedIndex = intent.getIntExtra(
                "currentIndex",
                intent.getIntExtra("index", 0)
        );

        if (queueJson != null) {
            parseQueue(queueJson);
        }

        if (queue.isEmpty()) {
            Log.w(TAG, "Cannot start playback: queue is empty");
            publishState();
            return;
        }

        if (requestedIndex < 0 || requestedIndex >= queue.size()) {
            requestedIndex = 0;
        }

        currentIndex = requestedIndex;
        playCurrentTrack();
    }

    private void parseQueue(String json) {
        queue.clear();

        try {
            JSONArray array = new JSONArray(json);

            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);

                String id = object.optString("id", "");
                String title = object.optString(
                        "title",
                        "Unknown Title"
                );
                String artist = object.optString(
                        "artist",
                        "Unknown Artist"
                );
                String album = object.optString(
                        "album",
                        "Local Music"
                );
                String url = object.optString("url", "");

                queue.add(new Track(
                        id,
                        title,
                        artist,
                        album,
                        url
                ));
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to parse playback queue", e);
        }
    }

    private void playCurrentTrack() {
        if (currentIndex < 0 || currentIndex >= queue.size()) {
            Log.w(TAG, "Invalid current track index");
            return;
        }

        Track track = queue.get(currentIndex);

        if (currentArtwork != null && !currentArtwork.isRecycled()) {
            currentArtwork.recycle();
        }
        currentArtwork = null;
        loadArtworkInBackground(track);
        releasePlayer();

        currentTitle = track.title;
        currentArtist = track.artist;
        currentAlbum = track.album;

        isPreparing = true;
        acquireWakeLock();

        try {
            // Promote the service immediately, before opening a provider URI.
            // This also avoids foreground-service start timeouts on newer Android.
            startForeground(NOTIFICATION_ID, buildNotification());
            player = new MediaPlayer();
            player.setOnSeekCompleteListener(mp -> publishState());

            player.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(
                                    AudioAttributes.CONTENT_TYPE_MUSIC
                            )
                            .build()
            );

            /*
             * Android native playback must use the original content URI
             * when available. The WebView URL is only a fallback.
             */
            String source = track.id;

            if (source == null || source.isEmpty()) {
                source = track.url;
            }

            if (source == null || source.isEmpty()) {
                throw new IllegalArgumentException(
                        "No audio URI is available for this track"
                );
            }

            if (source.startsWith("content://")
                    || source.startsWith("file://")
                    || source.startsWith("android.resource://")) {

                player.setDataSource(
                        getApplicationContext(),
                        Uri.parse(source)
                );

            } else {
                player.setDataSource(source);
            }

            player.setOnPreparedListener(mediaPlayer -> {
                isPreparing = false;

                try {
                    mediaPlayer.start();
                    updateMediaSessionMetadata();
                    updateMediaSessionPlaybackState();
                    showNotification();
                    scheduleTicker();
                    publishState();
                } catch (Exception e) {
                    Log.e(TAG, "Unable to start playback", e);
                    releasePlayer();
                    publishState();
                }
            });

            player.setOnCompletionListener(mediaPlayer -> {
                if (repeatMode == 1) {
                    playCurrentTrack();
                } else {
                    playNext();
                }
            });

            player.setOnErrorListener((mediaPlayer, what, extra) -> {
                Log.e(
                        TAG,
                        "MediaPlayer error: what=" + what
                                + ", extra=" + extra
                );

                isPreparing = false;
                Log.e(TAG, "Playback failed for " + track.title + " (" + track.id + ")");
                publishState();
                return true;
            });

            player.prepareAsync();

            updateMediaSessionMetadata();
            updateMediaSessionPlaybackState();
            showNotification();
            publishState();

        } catch (Exception e) {
            Log.e(TAG, "Unable to prepare track", e);

            isPreparing = false;
            releasePlayer();
            publishState();
        }
    }

    private void resumePlayback() {
        if (player != null) {
            try {
                if (!player.isPlaying() && !isPreparing) {
                    player.start();
                    acquireWakeLock();
                    scheduleTicker();
                }

                updateMediaSessionPlaybackState();
                showNotification();
                publishState();
                return;

            } catch (IllegalStateException e) {
                Log.e(TAG, "Unable to resume playback", e);
            }
        }

        if (currentIndex >= 0 && currentIndex < queue.size()) {
            playCurrentTrack();
        }
    }

    private void pausePlayback() {
        if (player != null) {
            try {
                if (player.isPlaying()) {
                    player.pause();
                }
            } catch (IllegalStateException e) {
                Log.e(TAG, "Unable to pause playback", e);
            }
        }

        handler.removeCallbacks(ticker);

        releaseWakeLock();

        updateMediaSessionPlaybackState();
        showNotification();
        publishState();
    }

    private void playNext() {
        if (queue.isEmpty()) {
            return;
        }

        if (repeatMode == 2) {
            currentIndex = (int) (Math.random() * queue.size());
        } else {
            currentIndex++;

            if (currentIndex >= queue.size()) {
                currentIndex = 0;
            }
        }

        playCurrentTrack();
    }

    private void playPrevious() {
        if (queue.isEmpty()) {
            return;
        }

        if (player != null) {
            try {
                if (player.getCurrentPosition() > 3000) {
                    player.seekTo(0);
                    publishState();
                    return;
                }
            } catch (IllegalStateException ignored) {
            }
        }

        currentIndex--;

        if (currentIndex < 0) {
            currentIndex = queue.size() - 1;
        }

        playCurrentTrack();
    }

    private void seekTo(Intent intent) {
        if (player == null) {
            return;
        }

        long requestedPosition = intent.getLongExtra(
                "position",
                intent.getLongExtra("seekTo", 0L)
        );
        int position = (int) Math.max(0L, Math.min(requestedPosition, Integer.MAX_VALUE));

        try {
            player.seekTo(Math.max(position, 0));
            publishState();
        } catch (IllegalStateException e) {
            Log.e(TAG, "Unable to seek", e);
        }
    }

    private void setRepeatMode(Intent intent) {
        repeatMode = intent.getIntExtra(
                "repeatMode",
                intent.getIntExtra("mode", 0)
        );

        if (repeatMode < 0 || repeatMode > 2) {
            repeatMode = 0;
        }

        publishState();
    }

    private void stopPlayback() {
        handler.removeCallbacks(ticker);

        releasePlayer();
        releaseWakeLock();

        currentIndex = -1;
        queue.clear();
        isPreparing = false;
        currentTitle = "LUXE Music";
        currentArtist = "Unknown Artist";
        currentAlbum = "Local Music";

        artworkExecutor.shutdownNow();
        if (currentArtwork != null && !currentArtwork.isRecycled()) {
            currentArtwork.recycle();
            currentArtwork = null;
        }
        if (mediaSession != null) {
            mediaSession.setActive(false);
        }

        stopForeground(true);
        stopSelf();

        publishState();
    }

    private void releasePlayer() {
        handler.removeCallbacks(ticker);

        if (player != null) {
            try {
                player.setOnPreparedListener(null);
                player.setOnCompletionListener(null);
                player.setOnErrorListener(null);
                player.reset();
                player.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing player", e);
            }

            player = null;
        }

        isPreparing = false;
    }

    private void scheduleTicker() {
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    private void updateMediaSessionMetadata() {
        if (mediaSession == null) {
            return;
        }

        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .putString(
                        MediaMetadata.METADATA_KEY_TITLE,
                        currentTitle
                )
                .putString(
                        MediaMetadata.METADATA_KEY_ARTIST,
                        currentArtist
                )
                .putString(
                        MediaMetadata.METADATA_KEY_ALBUM,
                        currentAlbum
                );

        if (player != null) {
            try {
                if (player.getDuration() > 0) {
                    metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, player.getDuration());
                }
            } catch (IllegalStateException ignored) {}
        }
        if (currentArtwork != null && !currentArtwork.isRecycled()) {
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ART, currentArtwork);
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, currentArtwork);
        }
        mediaSession.setMetadata(metadata.build());

    }

    private void loadArtworkInBackground(Track track) {
        final String trackId = track.id;
        artworkExecutor.execute(() -> {
            Bitmap found = null;
            MediaMetadataRetriever retriever = null;
            try {
                if (trackId != null && (trackId.startsWith("content://") || trackId.startsWith("file://"))) {
                    retriever = new MediaMetadataRetriever();
                    retriever.setDataSource(getApplicationContext(), Uri.parse(trackId));
                    byte[] bytes = retriever.getEmbeddedPicture();
                    if (bytes != null && bytes.length > 0) {
                        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        if (decoded != null) {
                            int max = 512;
                            float scale = Math.min(1f, max / (float) Math.max(decoded.getWidth(), decoded.getHeight()));
                            if (scale < 1f) {
                                found = Bitmap.createScaledBitmap(decoded, Math.max(1, (int)(decoded.getWidth() * scale)), Math.max(1, (int)(decoded.getHeight() * scale)), true);
                                if (found != decoded) decoded.recycle();
                            } else found = decoded;
                        }
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "No embedded cover art for current track", e);
            } finally {
                if (retriever != null) try { retriever.release(); } catch (Exception ignored) {}
            }
            final Bitmap result = found;
            handler.post(() -> {
                if (serviceDestroyed || trackId == null || !trackId.equals(currentTrackId())) {
                    if (result != null && !result.isRecycled()) result.recycle();
                    return;
                }
                if (currentArtwork != null && !currentArtwork.isRecycled()) currentArtwork.recycle();
                currentArtwork = result;
                updateMediaSessionMetadata();
                showNotification();
            });
        });
    }

    private String currentTrackId() {
        return currentIndex >= 0 && currentIndex < queue.size() ? queue.get(currentIndex).id : null;
    }

    private void updateMediaSessionPlaybackState() {
        if (mediaSession == null) {
            return;
        }

        boolean playing = false;
        int position = 0;

        if (player != null) {
            try {
                playing = player.isPlaying();

                if (player.getCurrentPosition() >= 0) {
                    position = player.getCurrentPosition();
                }
            } catch (IllegalStateException ignored) {
            }
        }

        int state = playing
                ? PlaybackState.STATE_PLAYING
                : PlaybackState.STATE_PAUSED;

        PlaybackState playbackState =
                new PlaybackState.Builder()
                        .setActions(
                                PlaybackState.ACTION_PLAY
                                        | PlaybackState.ACTION_PAUSE
                                        | PlaybackState.ACTION_PLAY_PAUSE
                                        | PlaybackState.ACTION_SKIP_TO_NEXT
                                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                                        | PlaybackState.ACTION_SEEK_TO
                                        | PlaybackState.ACTION_STOP
                        )
                        .setState(
                                state,
                                position,
                                playing ? 1.0f : 0.0f
                        )
                        .build();

        mediaSession.setPlaybackState(playbackState);
    }

    private Notification buildNotification() {
        boolean playing = false;

        if (player != null) {
            try {
                playing = player.isPlaying();
            } catch (IllegalStateException ignored) {
            }
        }

        Intent openAppIntent = getPackageManager()
                .getLaunchIntentForPackage(getPackageName());

        PendingIntent contentPendingIntent = null;

        if (openAppIntent != null) {
            contentPendingIntent = PendingIntent.getActivity(
                    this,
                    100,
                    openAppIntent,
                    pendingIntentFlags()
            );
        }

        PendingIntent previousPendingIntent =
                createActionPendingIntent(
                        ACTION_NOTIFICATION_PREVIOUS,
                        101
                );

        PendingIntent playPausePendingIntent =
                createActionPendingIntent(
                        playing
                                ? ACTION_NOTIFICATION_PAUSE
                                : ACTION_NOTIFICATION_PLAY,
                        102
                );

        PendingIntent nextPendingIntent =
                createActionPendingIntent(
                        ACTION_NOTIFICATION_NEXT,
                        103
                );

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_media_play)
                        .setContentTitle(
                                currentTitle == null
                                        ? "LUXE Music"
                                        : currentTitle
                        )
                        .setContentText(
                                currentArtist == null
                                        ? "Unknown Artist"
                                        : currentArtist
                        )
                        .setSubText("LUXE Music")
                        .setVisibility(
                                NotificationCompat.VISIBILITY_PUBLIC
                        )
                        .setOngoing(playing)
                        .setOnlyAlertOnce(true)
                        .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .addAction(
                                android.R.drawable.ic_media_previous,
                                "Previous",
                                previousPendingIntent
                        )
                        .addAction(
                                playing
                                        ? android.R.drawable.ic_media_pause
                                        : android.R.drawable.ic_media_play,
                                playing ? "Pause" : "Play",
                                playPausePendingIntent
                        )
                        .addAction(
                                android.R.drawable.ic_media_next,
                                "Next",
                                nextPendingIntent
                        );

        if (contentPendingIntent != null) {
            builder.setContentIntent(contentPendingIntent);
        }
        if (currentArtwork != null && !currentArtwork.isRecycled()) {
            builder.setLargeIcon(currentArtwork);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                && mediaSession != null) {
            builder.setStyle(
                    new androidx.media.app.NotificationCompat.MediaStyle()
                            .setShowActionsInCompactView(0, 1, 2)
            );
        }

        return builder.build();
    }

    private PendingIntent createActionPendingIntent(
            String action,
            int requestCode
    ) {
        Intent intent = new Intent(this, PlaybackKeepAliveService.class);
        intent.setAction(action);

        return PendingIntent.getService(
                this,
                requestCode,
                intent,
                pendingIntentFlags()
        );
    }

    private int pendingIntentFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return PendingIntent.FLAG_UPDATE_CURRENT
                    | PendingIntent.FLAG_IMMUTABLE;
        }

        return PendingIntent.FLAG_UPDATE_CURRENT;
    }

    private void showNotification() {
        try {
            Notification notification = buildNotification();

            NotificationManager manager =
                    (NotificationManager) getSystemService(
                            NOTIFICATION_SERVICE
                    );

            if (manager != null) {
                manager.notify(NOTIFICATION_ID, notification);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                        NOTIFICATION_ID,
                        notification
                );
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to update notification", e);
        }
    }

    private void publishState() {
        updateMediaSessionPlaybackState();

        Intent stateIntent = new Intent(
                ACTION_STATE
        );

        stateIntent.setPackage(getPackageName());

        boolean playing = false;
        int position = 0;
        int duration = 0;

        if (player != null) {
            try {
                playing = player.isPlaying();
                position = player.getCurrentPosition();
                duration = player.getDuration();
            } catch (IllegalStateException ignored) {
            }
        }

        stateIntent.putExtra("playing", playing);
        stateIntent.putExtra("isPlaying", playing);
        stateIntent.putExtra("position", (long) position);
        stateIntent.putExtra("duration", (long) duration);
        stateIntent.putExtra("currentIndex", currentIndex);
        stateIntent.putExtra("title", currentTitle);
        stateIntent.putExtra("artist", currentArtist);
        stateIntent.putExtra("album", currentAlbum);
        stateIntent.putExtra("repeatMode", repeatMode);
        stateIntent.putExtra("id", currentTrackId());
        stateIntent.putExtra("queueIndex", currentIndex);
        stateIntent.putExtra("queueSize", queue.size());

        sendBroadcast(stateIntent);
    }

    /*
     * Compatibility method for callers that update playback metadata.
     */
    public void updatePlayback(
            String title,
            String artist,
            boolean playing
    ) {
        currentTitle = title == null ? "LUXE Music" : title;
        currentArtist = artist == null ? "Unknown Artist" : artist;

        updateMediaSessionMetadata();
        updateMediaSessionPlaybackState();
        showNotification();
        publishState();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // When the user dismisses LUXE from Recents, stop playback completely.
        // This also removes the notification and deactivates the lock-screen session.
        stopPlayback();
        queue.clear();
        currentTitle = "LUXE Music";
        currentArtist = "Unknown Artist";
        currentAlbum = "Local Music";
        if (mediaSession != null) {
            try {
                mediaSession.setActive(false);
            } catch (Exception ignored) {
            }
        }
        stopForeground(true);
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        serviceDestroyed = true;

        handler.removeCallbacks(ticker);
        releasePlayer();
        releaseWakeLock();

        artworkExecutor.shutdownNow();
        if (currentArtwork != null && !currentArtwork.isRecycled()) {
            currentArtwork.recycle();
            currentArtwork = null;
        }
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }

        super.onDestroy();

        Log.d(TAG, "Playback service destroyed");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
