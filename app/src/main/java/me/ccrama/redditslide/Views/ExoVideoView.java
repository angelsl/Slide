package me.ccrama.redditslide.Views;

import android.content.Context;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceView;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.media.AudioAttributesCompat;
import androidx.media.AudioFocusRequestCompat;
import androidx.media.AudioManagerCompat;

import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.ExoTrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.PlayerControlView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.cache.CacheDataSource;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.video.VideoSize;

import me.ccrama.redditslide.R;
import me.ccrama.redditslide.Reddit;
import me.ccrama.redditslide.SettingValues;
import me.ccrama.redditslide.util.BlendModeUtil;
import me.ccrama.redditslide.util.LogUtil;
import me.ccrama.redditslide.util.NetworkUtil;

/**
 * View containing an ExoPlayer
 */
public class ExoVideoView extends RelativeLayout {
    private Context context;
    private SimpleExoPlayer player;
    private DefaultTrackSelector trackSelector;
    private PlayerControlView playerUI;
    private boolean muteAttached = false;
    private boolean hqAttached = false;
    private AudioFocusHelper audioFocusHelper;

    public ExoVideoView(final Context context) {
        this(context, null, true);
    }

    public ExoVideoView(final Context context, final boolean ui) {
        this(context, null, ui);
    }

    public ExoVideoView(final Context context, final AttributeSet attrs) {
        this(context, attrs, true);
    }

    public ExoVideoView(final Context context, final AttributeSet attrs, final boolean ui) {
        super(context, attrs);
        this.context = context;

        setupPlayer();
        if (ui) {
            setupUI();
        }
    }

    /**
     * Initializes the view to render onto and the SimpleExoPlayer instance
     */
    private void setupPlayer() {
        // Create a view to render the video onto and an AspectRatioFrameLayout to size the video correctly
        AspectRatioFrameLayout frame = new AspectRatioFrameLayout(context);
        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        params.addRule(CENTER_IN_PARENT, TRUE);
        frame.setLayoutParams(params);
        frame.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);

        SurfaceView renderView = new SurfaceView(context);
        frame.addView(renderView);
        addView(frame);

        // Create a track selector so we can set specific video quality for DASH
        trackSelector = new DefaultTrackSelector(context);
        if ((SettingValues.lowResAlways
                || (NetworkUtil.isConnected(context) && !NetworkUtil.isConnectedWifi(context) && SettingValues.lowResMobile))
                && SettingValues.lqVideos) {
            trackSelector.setParameters(trackSelector.buildUponParameters().setForceLowestBitrate(true));
        } else {
            trackSelector.setParameters(trackSelector.buildUponParameters().setForceHighestSupportedBitrate(true));
        }

        // Create the player, attach it to the view, make it repeat infinitely
        player = new SimpleExoPlayer.Builder(context).setTrackSelector(trackSelector).build();
        player.setVideoSurfaceView(renderView);
        player.setRepeatMode(Player.REPEAT_MODE_ALL);

        // Mute by default
        player.setVolume(0f);

        // Create audio focus helper
        audioFocusHelper = new AudioFocusHelper(ContextCompat.getSystemService(context, AudioManager.class));

        player.addListener(new Player.Listener() {
            // Make the video use the correct aspect ratio
            @Override
            public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
                frame.setAspectRatio(
                        videoSize.height == 0 || videoSize.width == 0
                                ? 1
                                : videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height);
            }

            // Logging
            @Override
            public void onTracksChanged(@NonNull TrackGroupArray trackGroups,
                                        @NonNull TrackSelectionArray trackSelections) {
                StringBuilder toLog = new StringBuilder();
                for (int i = 0; i < trackGroups.length; i++) {
                    for (int j = 0; j < trackGroups.get(i).length; j++) {
                        toLog.append("Format:\t").append(trackGroups.get(i).getFormat(j)).append("\n");
                    }
                }
                // FIXME: How do I make onTracksChanged work with ExoTrackSelection?
                /*for (TrackSelection i : trackSelections.getAll()) {
                    if (i != null)
                        toLog.append("Selected format:\t").append(i.getSelectedFormat()).append("\n");
                }*/
                Log.v(LogUtil.getTag(), toLog.toString());
            }
        });
    }

    /**
     * Sets up the player UI
     */
    private void setupUI() {
        // Create a PlayerControlView for our video controls and add it
        playerUI = new PlayerControlView(context);
        playerUI.setPlayer(player);
        playerUI.setShowTimeoutMs(2000);
        playerUI.hide();
        addView(playerUI);

        // Show/hide the player UI on tap
        setOnClickListener((v) -> {
            playerUI.clearAnimation();
            if (playerUI.isVisible()) {
                playerUI.startAnimation(new PlayerUIFadeInAnimation(playerUI, false, 300));
            } else {
                playerUI.startAnimation(new PlayerUIFadeInAnimation(playerUI, true, 300));
            }
        });
    }

    /**
     * Sets the player's URI and prepares for playback
     *
     * @param uri      URI
     * @param type     Type of video
     * @param listener EventLister attached to the player, helpful for player state
     */
    public void setVideoURI(Uri uri, VideoType type, Player.Listener listener) {
        // Create the data sources used to retrieve and cache the video
        DataSource.Factory downloader =
                new OkHttpDataSource.Factory(Reddit.client)
                        .setUserAgent(context.getString(R.string.app_name));
        DataSource.Factory cacheDataSourceFactory =
                new CacheDataSource.Factory()
                        .setCache(Reddit.videoCache)
                        .setUpstreamDataSourceFactory(downloader);

        // Create an appropriate media source for the video type
        MediaSource videoSource;
        switch (type) {
            // DASH video, e.g. v.redd.it video
            case DASH:
                videoSource = new DashMediaSource.Factory(cacheDataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(uri));
                break;

            // Standard video, e.g. MP4 file
            case STANDARD:
            default:
                videoSource = new ProgressiveMediaSource.Factory(cacheDataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(uri));
                break;
        }

        player.setMediaSource(videoSource);
        player.prepare();
        if (listener != null) {
            player.addListener(listener);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // If we don't release the player here, hardware decoders won't be released, breaking ExoPlayer device-wide
        stop();
    }

    /**
     * Plays the video
     */
    public void play() {
        player.play();
    }

    /**
     * Pauses the video
     */
    public void pause() {
        player.pause();
    }

    /**
     * Stops the video and releases the player
     */
    public void stop() {
        player.stop();
        player.release();
        audioFocusHelper.loseFocus(); // do this last so audio doesn't overlap
    }

    /**
     * Seeks to a specific timestamp
     *
     * @param time timestamp
     */
    public void seekTo(long time) {
        player.seekTo(time);
    }

    /**
     * Gets the current timestamp
     *
     * @return current timestamp
     */
    public long getCurrentPosition() {
        return player.getCurrentPosition();
    }

    /**
     * Attach a mute button to the view. The view will then handle hiding/showing that button as appropriate.
     * If this is not called, audio will be permanently muted.
     *
     * @param mute Mute button
     */
    public void attachMuteButton(final ImageView mute) {
        // Hide the mute button by default
        mute.setVisibility(GONE);

        player.addListener(new Player.Listener() {
            @Override
            public void onTracksChanged(@NonNull TrackGroupArray trackGroups,
                                        @NonNull TrackSelectionArray trackSelections) {
                // We only need to run this on the first track change, i.e. when the video is loaded
                // Skip this if mute has already been configured, otherwise mark it as configured
                if (muteAttached && trackGroups.length > 0) {
                    return;
                } else {
                    muteAttached = true;
                }
                // Loop through the tracks and check if any contain audio, if so set up the mute button
                for (int i = 0; i < trackSelections.length; i++) {
                    final ExoTrackSelection selection = (ExoTrackSelection) trackSelections.get(i);
                    if (selection != null && selection.getSelectedFormat() != null
                            && MimeTypes.isAudio(selection.getSelectedFormat().sampleMimeType)) {

                        mute.setVisibility(VISIBLE);
                        // Set initial mute state
                        if (!SettingValues.isMuted) {
                            player.setVolume(1f);
                            BlendModeUtil.tintImageViewAsSrcAtop(mute, Color.WHITE);
                            audioFocusHelper.gainFocus();
                        } else {
                            player.setVolume(0f);
                            BlendModeUtil.tintImageViewAsSrcAtop(mute, getResources().getColor(R.color.md_red_500));
                        }

                        mute.setOnClickListener((v) -> {
                            if (SettingValues.isMuted) {
                                player.setVolume(1f);
                                SettingValues.isMuted = false;
                                SettingValues.prefs.edit().putBoolean(SettingValues.PREF_MUTE, false).apply();
                                BlendModeUtil.tintImageViewAsSrcAtop(mute, Color.WHITE);
                                audioFocusHelper.gainFocus();
                            } else {
                                player.setVolume(0f);
                                SettingValues.isMuted = true;
                                SettingValues.prefs.edit().putBoolean(SettingValues.PREF_MUTE, true).apply();
                                BlendModeUtil.tintImageViewAsSrcAtop(mute, getResources().getColor(R.color.md_red_500));
                                audioFocusHelper.loseFocus();
                            }
                        });
                        return;
                    }
                }
            }
        });
    }

    /**
     * Attach an HQ button to the view. The view will then handle hiding/showing that button as appropriate.
     *
     * @param hq HQ button
     */
    public void attachHqButton(final ImageView hq) {
        // Hidden by default - we don't yet know if we'll have multiple qualities to select from
        hq.setVisibility(GONE);

        player.addListener(new Player.Listener() {
            @Override
            public void onTracksChanged(@NonNull TrackGroupArray trackGroups,
                                        @NonNull TrackSelectionArray trackSelections) {
                if (hqAttached || trackGroups.length == 0
                        || trackSelector.getParameters().forceHighestSupportedBitrate) {
                    return;
                } else {
                    hqAttached = true;
                }
                // Lopp through the tracks, check if they're video. If we have at least 2 video tracks we can set
                // up quality selection.
                int videoTrackCounter = 0;
                for (int trackGroup = 0; trackGroup < trackGroups.length; trackGroup++) {
                    for (int format = 0; format < trackGroups.get(trackGroup).length; format++) {
                        if (MimeTypes.isVideo(trackGroups.get(trackGroup).getFormat(format).sampleMimeType)) {
                            videoTrackCounter++;
                        }
                        if (videoTrackCounter > 1) {
                            break;
                        }
                    }
                    if (videoTrackCounter > 1) {
                        break;
                    }
                }
                // If we have enough video tracks to have a quality button, set it up.
                if (videoTrackCounter > 1) {
                    hq.setVisibility(VISIBLE);

                    hq.setOnClickListener((v) -> {
                        trackSelector.setParameters(trackSelector.buildUponParameters()
                                .setForceLowestBitrate(false)
                                .setForceHighestSupportedBitrate(true));
                        hq.setVisibility(GONE);
                    });
                }
            }
        });
    }

    public enum VideoType {
        STANDARD,
        DASH
    }

    /**
     * Helps manage audio focus
     */
    private class AudioFocusHelper implements AudioManager.OnAudioFocusChangeListener {
        private AudioManager manager;
        private boolean wasPlaying;
        private AudioFocusRequestCompat request;

        AudioFocusHelper(AudioManager manager) {
            this.manager = manager;

            if (request == null) {
                AudioAttributesCompat audioAttributes = new AudioAttributesCompat.Builder()
                        .setContentType(AudioAttributesCompat.CONTENT_TYPE_MOVIE)
                        .setUsage(AudioAttributesCompat.USAGE_MEDIA)
                        .build();
                request = new AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN_TRANSIENT)
                        //.setAcceptsDelayedFocusGain(false)
                        .setAudioAttributes(audioAttributes)
                        .setOnAudioFocusChangeListener(this)
                        .setWillPauseWhenDucked(true)
                        .build();
            }
        }

        /**
         * Lose audio focus
         */
        void loseFocus() {
            AudioManagerCompat.abandonAudioFocusRequest(manager, request);
        }

        /**
         * Gain audio focus
         */
        void gainFocus() {
            AudioManagerCompat.requestAudioFocus(manager, request);
        }

        @Override
        public void onAudioFocusChange(int focusChange) {
            // Pause on audiofocus loss, play on gain
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                wasPlaying = player.getPlayWhenReady();
                player.pause();
            } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                player.setPlayWhenReady(wasPlaying);
            }
        }
    }

    static class PlayerUIFadeInAnimation extends AnimationSet {
        private PlayerControlView animationView;
        private boolean toVisible;

        PlayerUIFadeInAnimation(PlayerControlView view, boolean toVisible, long duration) {
            super(false);
            this.toVisible = toVisible;
            this.animationView = view;

            float startAlpha = toVisible ? 0 : 1;
            float endAlpha = toVisible ? 1 : 0;

            AlphaAnimation alphaAnimation = new AlphaAnimation(startAlpha, endAlpha);
            alphaAnimation.setDuration(duration);

            addAnimation(alphaAnimation);
            setAnimationListener(new PlayerUIFadeInAnimation.Listener());
        }

        private class Listener implements AnimationListener {

            @Override
            public void onAnimationStart(Animation animation) {
                animationView.show();
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                if (toVisible)
                    animationView.show();
                else
                    animationView.hide();
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
                // Purposefully left blank
            }
        }
    }
}
