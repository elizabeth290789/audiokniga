package com.audiokniga.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlaybackService extends Service {
    private static final String PREFIX = "com.audiokniga.app.playback.";
    public static final String ACTION_PLAY = PREFIX + "PLAY";
    public static final String ACTION_PAUSE = PREFIX + "PAUSE";
    public static final String ACTION_STOP = PREFIX + "STOP";
    public static final String ACTION_SEEK = PREFIX + "SEEK";
    public static final String ACTION_SPEED = PREFIX + "SPEED";
    public static final String ACTION_LANGUAGE = PREFIX + "LANGUAGE";
    public static final String ACTION_LIST_VOICES = PREFIX + "LIST_VOICES";
    public static final String ACTION_VOICE = PREFIX + "VOICE";
    public static final String ACTION_PREVIEW = PREFIX + "PREVIEW";
    public static final String ACTION_CANCEL_PREVIEW = PREFIX + "CANCEL_PREVIEW";
    public static final String EXTRA_BOOK_ID = "book_id";
    public static final String EXTRA_POSITION = "position";
    public static final String EXTRA_SPEED = "speed";
    public static final String EXTRA_LANGUAGE = "language";
    public static final String EXTRA_VOICE = "voice";

    private static final String CHANNEL_ID = "reading";
    private static final int NOTIFICATION_ID = 1;
    private static final int CHUNK_SIZE = 900;
    private static final long SAVE_DELAY_MS = 2_000;
    private static final long UPDATE_DELAY_MS = 120;
    private static final Locale RUSSIAN = Locale.forLanguageTag("ru");

    public static final class Snapshot {
        public final String bookId;
        public final String language;
        public final int position;
        public final int rangeEnd;
        public final boolean playing;
        public final boolean preparing;
        public final boolean attached;
        public final String error;

        private Snapshot(String bookId, String language, int position, int rangeEnd, boolean playing,
                         boolean preparing, boolean attached, String error) {
            this.bookId = bookId;
            this.language = language;
            this.position = position;
            this.rangeEnd = rangeEnd;
            this.playing = playing;
            this.preparing = preparing;
            this.attached = attached;
            this.error = error;
        }
    }

    public static final class VoiceOption {
        public final String id;
        public final String label;

        private VoiceOption(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    public static final class VoiceCatalog {
        public final String language;
        public final List<VoiceOption> options;
        public final boolean loading;
        public final boolean previewing;
        public final String error;

        private VoiceCatalog(String language, List<VoiceOption> options, boolean loading,
                             boolean previewing, String error) {
            this.language = language;
            this.options = options;
            this.loading = loading;
            this.previewing = previewing;
            this.error = error;
        }
    }

    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile Snapshot snapshot =
            new Snapshot(null, "ru", 0, 0, false, false, false, null);
    private static volatile VoiceCatalog voiceCatalog =
            new VoiceCatalog(null, Collections.emptyList(), false, false, null);

    public static Snapshot snapshot() {
        return snapshot;
    }

    public static VoiceCatalog voiceCatalog() {
        return voiceCatalog;
    }

    static void addListener(Runnable listener) {
        LISTENERS.addIfAbsent(listener);
    }

    static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (wantsPlayback) {
                pause();
            } else if (preview != null) {
                finishPreview(null);
            }
        }
    };

    private static final class VoicePreview {
        final String language;
        final String voiceId;
        String utteranceId;

        VoicePreview(String language, String voiceId) {
            this.language = language;
            this.voiceId = voiceId;
        }
    }

    private BookStore store;
    private NotificationManager notifications;
    private AudioManager audioManager;
    private AudioAttributes audioAttributes;
    private AudioFocusRequest audioFocus;
    private AudioFocusRequest previewFocus;
    private AudioFocusRequest heldFocus;
    private PowerManager.WakeLock wakeLock;
    private MediaSession mediaSession;
    private TextToSpeech engine;
    private boolean engineReady;
    private boolean foreground;
    private boolean wantsPlayback;
    private boolean loading;
    private boolean destroyed;
    private int loadGeneration;
    private int engineGeneration;
    private int utteranceGeneration;
    private int latestStartId;
    private String bookId;
    private String title = "";
    private String language = "ru";
    private String selectedLanguage;
    private String selectedVoicePreference;
    private String enginePackage;
    private String catalogRequest;
    private VoicePreview preview;
    private String bookText;
    private BookPages bookPages;
    private String utteranceId;
    private String error;
    private int position;
    private int utteranceStart;
    private int utteranceEnd;
    private int rangeEnd;
    private boolean hasWordRange;
    private float speed = 1f;
    private long lastSaved;
    private long lastPublished;

    @Override
    public void onCreate() {
        super.onCreate();
        store = new BookStore(this);
        speed = store.speed();
        notifications = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Озвучивание книг", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        notifications.createNotificationChannel(channel);

        audioManager = getSystemService(AudioManager.class);
        audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        audioFocus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(focus -> onAudioFocusChanged(focus, audioFocus), handler)
                .build();
        previewFocus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(audioAttributes)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(focus -> onAudioFocusChanged(focus, previewFocus), handler)
                .build();
        PowerManager powerManager = getSystemService(PowerManager.class);
        wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "Audiokniga:Reading");
        wakeLock.setReferenceCounted(false);

        mediaSession = new MediaSession(this, "Audiokniga");
        mediaSession.setSessionActivity(activityIntent());
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                play(bookId, null);
            }

            @Override
            public void onPause() {
                pause();
            }

            @Override
            public void onStop() {
                stop();
            }
        }, handler);

        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(noisyReceiver, filter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        if (intent == null || intent.getAction() == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        String requestedBook = intent.getStringExtra(EXTRA_BOOK_ID);
        switch (intent.getAction()) {
            case ACTION_PLAY:
                play(requestedBook, intent.hasExtra(EXTRA_POSITION)
                        ? intent.getIntExtra(EXTRA_POSITION, 0) : null);
                break;
            case ACTION_PAUSE:
                pause();
                break;
            case ACTION_STOP:
                stop();
                break;
            case ACTION_SEEK:
                if (bookId != null && (requestedBook == null || bookId.equals(requestedBook))) {
                    seek(intent.getIntExtra(EXTRA_POSITION, 0));
                } else if (requestedBook != null) {
                    store.savePosition(requestedBook, intent.getIntExtra(EXTRA_POSITION, 0));
                    publish();
                }
                break;
            case ACTION_SPEED:
                changeSpeed(intent.getFloatExtra(EXTRA_SPEED, speed));
                break;
            case ACTION_LANGUAGE:
                changeLanguage(requestedBook, intent.getStringExtra(EXTRA_LANGUAGE));
                break;
            case ACTION_LIST_VOICES:
                listVoices(intent.getStringExtra(EXTRA_LANGUAGE));
                break;
            case ACTION_VOICE:
                changeVoice(requestedBook, intent.getStringExtra(EXTRA_LANGUAGE),
                        intent.getStringExtra(EXTRA_VOICE));
                break;
            case ACTION_PREVIEW:
                requestPreview(intent.getStringExtra(EXTRA_LANGUAGE), intent.getStringExtra(EXTRA_VOICE));
                break;
            case ACTION_CANCEL_PREVIEW:
                if (preview != null) finishPreview(null);
                break;
            default:
                break;
        }
        stopIfIdle();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void play(String requestedBook, Integer startPosition) {
        clearPreview(null);
        boolean alreadyPlaying = wantsPlayback;
        if (!alreadyPlaying && engineReady && !Objects.equals(enginePackage, engine.getDefaultEngine())) {
            resetEngine();
        }
        wantsPlayback = true;
        error = null;
        if (requestedBook == null) requestedBook = bookId;
        boolean switching = requestedBook != null && !requestedBook.equals(bookId);
        if (switching) {
            savePosition();
            stopUtterance();
            loadGeneration++;
            loading = false;
            bookText = null;
            bookPages = null;
            bookId = requestedBook;
            position = 0;
            title = "";
        }

        try {
            showForeground();
        } catch (IllegalStateException | SecurityException exception) {
            fail("Android не разрешил запустить фоновое чтение. Запустите чтение из приложения.");
            return;
        }

        if (bookId == null) {
            fail("Сначала выберите книгу.");
            return;
        }
        if (switching) {
            BookStore.Book book = store.get(bookId);
            if (book == null) {
                fail("Книга не найдена в библиотеке.");
                return;
            }
            title = book.title;
            language = book.language;
            position = startPosition == null ? book.position : Math.max(0, startPosition);
            mediaSession.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .build());
        } else if (startPosition != null) {
            seek(startPosition);
        } else if (!alreadyPlaying && bookText != null && position >= bookText.length()) {
            position = 0;
        }
        mediaSession.setActive(true);
        if (bookText == null && !loading) readBook(!switching || startPosition == null);
        if (!engineReady && engine == null) initEngine();
        refresh();
        startSpeechIfReady();
    }

    private void readBook(boolean restartAtEnd) {
        loading = true;
        String requested = bookId;
        int generation = ++loadGeneration;
        reader.execute(() -> {
            try {
                String text = store.readText(requested);
                BookPages pages = new BookPages(text);
                handler.post(() -> {
                    if (destroyed || generation != loadGeneration) return;
                    loading = false;
                    if (text == null || text.trim().isEmpty()) {
                        fail("В книге нет текста для чтения.");
                        return;
                    }
                    bookText = text;
                    bookPages = pages;
                    position = clampPosition(position);
                    if (restartAtEnd && wantsPlayback && position >= bookText.length()) {
                        position = 0;
                    }
                    refresh();
                    startSpeechIfReady();
                });
            } catch (IOException exception) {
                handler.post(() -> {
                    if (destroyed || generation != loadGeneration) return;
                    loading = false;
                    fail("Не удалось прочитать сохранённый текст книги.");
                });
            }
        });
    }

    private void initEngine() {
        int generation = ++engineGeneration;
        engine = new TextToSpeech(getApplicationContext(), status ->
                handler.post(() -> onEngineInitialized(generation, status)));
        handler.postDelayed(() -> {
            if (!destroyed && engineGeneration == generation && !engineReady) {
                engineFailed("Системная озвучка не ответила. Проверьте настройки синтеза речи Android.");
            }
        }, 15_000);
    }

    private void onEngineInitialized(int generation, int status) {
        if (destroyed || generation != engineGeneration) return;
        if (status != TextToSpeech.SUCCESS) {
            engineFailed("Не удалось запустить системную озвучку. Проверьте настройки синтеза речи Android.");
            return;
        }
        engineReady = true;
        enginePackage = engine.getDefaultEngine();
        engine.setAudioAttributes(audioAttributes);
        engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {
            }

            @Override
            public void onRangeStart(String id, int start, int end, int frame) {
                handler.post(() -> onSpeechRange(id, start, end));
            }

            @Override
            public void onDone(String id) {
                handler.post(() -> onSpeechDone(id));
            }

            @Override
            public void onError(String id) {
                handler.post(() -> {
                    if (preview != null && id.equals(preview.utteranceId)) {
                        finishPreview("Не удалось воспроизвести пример голоса.");
                    } else if (id.equals(utteranceId)) {
                        fail("Системному голосу не удалось озвучить этот фрагмент.");
                    }
                });
            }
        });
        completeCatalogRequest();
        startPreviewIfReady();
        startSpeechIfReady();
        stopIfIdle();
    }

    private void engineFailed(String message) {
        resetEngine();
        if (catalogRequest != null) {
            String requested = catalogRequest;
            catalogRequest = null;
            setVoiceCatalog(new VoiceCatalog(requested, Collections.emptyList(), false, preview != null, message));
        }
        if (wantsPlayback) {
            fail(message);
        } else if (preview != null) {
            finishPreview(message);
        }
        stopIfIdle();
    }

    private void startSpeechIfReady() {
        if (!wantsPlayback || loading || bookText == null || !engineReady || utteranceId != null || preview != null) {
            return;
        }
        while (position < bookText.length() && Character.isWhitespace(bookText.charAt(position))) {
            position++;
        }
        if (position >= bookText.length()) {
            pause();
            return;
        }
        if (!selectReadingVoice()) {
            fail(missingVoice(language));
            return;
        }
        if (engine.setSpeechRate(speed) != TextToSpeech.SUCCESS) {
            fail("Системному голосу не удалось применить скорость чтения.");
            return;
        }
        if (!requestFocus(audioFocus)) {
            fail("Звук занят другим приложением. Попробуйте снова.");
            return;
        }
        wakeLock.acquire(5 * 60_000L);
        utteranceStart = position;
        utteranceEnd = Math.min(chunkEnd(bookText, position), bookPages.end(bookPages.pageForPosition(position)));
        rangeEnd = utteranceEnd;
        hasWordRange = false;
        utteranceId = Integer.toString(++utteranceGeneration);
        int result = engine.speak(bookText.substring(utteranceStart, utteranceEnd),
                TextToSpeech.QUEUE_FLUSH, new Bundle(), utteranceId);
        if (result != TextToSpeech.SUCCESS) {
            fail("Не удалось начать озвучивание. Проверьте настройки синтеза речи Android.");
            return;
        }
        refresh();
    }

    private void listVoices(String requested) {
        if (!supportedLanguage(requested)) return;
        catalogRequest = requested;
        setVoiceCatalog(new VoiceCatalog(requested, Collections.emptyList(), true, preview != null, null));
        if (engineReady && !wantsPlayback && preview == null
                && !Objects.equals(enginePackage, engine.getDefaultEngine())) {
            resetEngine();
        }
        if (engineReady) {
            completeCatalogRequest();
        } else if (engine == null) {
            initEngine();
        }
    }

    private void completeCatalogRequest() {
        if (catalogRequest == null) return;
        String requested = catalogRequest;
        catalogRequest = null;
        List<VoiceOption> options = new ArrayList<>();
        for (Voice voice : offlineVoices(requested)) {
            String name = voice.getLocale().getDisplayName(RUSSIAN) + " · " + voice.getName();
            options.add(new VoiceOption(voiceId(voice), name));
        }
        setVoiceCatalog(new VoiceCatalog(requested, Collections.unmodifiableList(options), false, preview != null,
                options.isEmpty() ? missingVoice(requested) : null));
    }

    private void changeVoice(String requestedBook, String requestedLanguage, String requestedVoice) {
        if (requestedBook == null || !supportedLanguage(requestedLanguage)) return;
        store.saveVoice(requestedBook, requestedLanguage, requestedVoice);
        if (requestedBook.equals(bookId) && requestedLanguage.equals(language)) {
            error = null;
            restartUtterance();
        } else {
            publish();
        }
    }

    private void requestPreview(String requestedLanguage, String requestedVoice) {
        if (!supportedLanguage(requestedLanguage)) return;
        clearPreview(null);
        stopUtterance();
        savePosition();
        VoicePreview request = new VoicePreview(requestedLanguage, requestedVoice);
        preview = request;
        setPreviewStatus(true, null);
        publish();
        if (!engineReady && engine == null) initEngine();
        startPreviewIfReady();
        handler.postDelayed(() -> {
            if (preview == request) finishPreview("Пример голоса не удалось воспроизвести вовремя.");
        }, 25_000);
    }

    private void startPreviewIfReady() {
        if (!engineReady || preview == null || preview.utteranceId != null) return;
        List<Voice> voices = offlineVoices(preview.language);
        Voice voice = chooseVoice(voices, preview.voiceId);
        if (voice == null) {
            finishPreview(missingVoice(preview.language));
            return;
        }
        if (preview.voiceId != null && !preview.voiceId.equals(voiceId(voice))) {
            finishPreview("Этот голос недоступен. Откройте список голосов снова.");
            return;
        }
        if (!requestFocus(previewFocus)) {
            finishPreview("Звук занят другим приложением. Попробуйте снова.");
            return;
        }
        selectedLanguage = null;
        if (engine.setVoice(voice) != TextToSpeech.SUCCESS || engine.setSpeechRate(1f) != TextToSpeech.SUCCESS) {
            finishPreview("Не удалось применить настройки голоса.");
            return;
        }
        String sample = "en".equals(preview.language)
                ? "It was a bright morning, and the birds were singing in the garden."
                : "В старом замке висел тяжёлый замок. Утром я полил цветы и открыл окно.";
        preview.utteranceId = Integer.toString(++utteranceGeneration);
        if (engine.speak(sample, TextToSpeech.QUEUE_FLUSH, new Bundle(), preview.utteranceId) != TextToSpeech.SUCCESS) {
            finishPreview("Не удалось воспроизвести пример голоса.");
        }
    }

    private void finishPreview(String message) {
        clearPreview(message);
        publish();
        startSpeechIfReady();
        stopIfIdle();
    }

    private void clearPreview(String message) {
        if (preview == null) return;
        String id = preview.utteranceId;
        preview = null;
        if (id != null && engine != null) engine.stop();
        if (!wantsPlayback) releaseAudio();
        setPreviewStatus(false, message);
    }

    private void setPreviewStatus(boolean previewing, String message) {
        VoiceCatalog previous = voiceCatalog;
        setVoiceCatalog(new VoiceCatalog(previous.language, previous.options, previous.loading, previewing, message));
    }

    private void setVoiceCatalog(VoiceCatalog result) {
        voiceCatalog = result;
        notifyListeners();
    }

    private void stopIfIdle() {
        if (bookId == null && !foreground && preview == null && catalogRequest == null) {
            stopSelf(latestStartId);
        }
    }

    private boolean selectReadingVoice() {
        String preferred = store.voice(bookId, language);
        if (language.equals(selectedLanguage) && Objects.equals(preferred, selectedVoicePreference)) return true;
        Voice voice = chooseVoice(offlineVoices(language), preferred);
        if (voice == null || engine.setVoice(voice) != TextToSpeech.SUCCESS) return false;
        selectedLanguage = language;
        selectedVoicePreference = preferred;
        return true;
    }

    private Voice chooseVoice(List<Voice> voices, String preferred) {
        if (voices.isEmpty()) return null;
        if (preferred != null) {
            for (Voice voice : voices) {
                if (preferred.equals(voiceId(voice))) return voice;
            }
        }
        return voices.get(0);
    }

    private List<Voice> offlineVoices(String requestedLanguage) {
        Set<Voice> voices = engine.getVoices();
        if (voices == null) return Collections.emptyList();
        List<Voice> offline = new ArrayList<>();
        for (Voice voice : voices) {
            Locale locale = voice.getLocale();
            if (!locale.getLanguage().equals(requestedLanguage) || voice.isNetworkConnectionRequired()
                    || (voice.getFeatures() != null
                    && voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))) {
                continue;
            }
            offline.add(voice);
        }
        String preferredRegion = "en".equals(requestedLanguage) ? "US" : "RU";
        offline.sort(Comparator.<Voice>comparingInt(voice -> voiceScore(voice, preferredRegion))
                .reversed().thenComparing(Voice::getName));
        return offline;
    }

    private static int voiceScore(Voice voice, String preferredRegion) {
        int regionScore = preferredRegion.equals(voice.getLocale().getCountry()) ? 1_000 : 0;
        return regionScore + voice.getQuality() - voice.getLatency() / 10;
    }

    private String voiceId(Voice voice) {
        String packageName = enginePackage == null ? "default" : enginePackage;
        return packageName + "/" + voice.getName();
    }

    private static boolean supportedLanguage(String language) {
        return "ru".equals(language) || "en".equals(language);
    }

    private static String missingVoice(String language) {
        String name = "en".equals(language) ? "английский" : "русский";
        return "Установите " + name + " офлайн-голос в настройках синтеза речи Android.";
    }

    private static int chunkEnd(String text, int start) {
        int upper = Math.min(text.length(), start + CHUNK_SIZE);
        if (upper == text.length()) return upper;
        int fallback = upper;
        if (Character.isLowSurrogate(text.charAt(fallback))) fallback--;
        int wordBoundary = fallback;
        for (int index = fallback - 1; index > start + CHUNK_SIZE / 3; index--) {
            char ch = text.charAt(index);
            if (ch == '\n') return index + 1;
            if ((ch == '.' || ch == '!' || ch == '?' || ch == '…')
                    && Character.isWhitespace(text.charAt(index + 1))) return index + 1;
            if (wordBoundary == fallback && Character.isWhitespace(ch)) wordBoundary = index + 1;
        }
        return wordBoundary;
    }

    private void onSpeechRange(String id, int offset, int endOffset) {
        if (!id.equals(utteranceId)) return;
        int utteranceLength = utteranceEnd - utteranceStart;
        if (offset < 0 || offset >= utteranceLength || endOffset <= offset) return;
        int newPosition = clampPosition(utteranceStart + offset);
        if (newPosition < position || newPosition >= utteranceEnd) return;
        position = newPosition;
        rangeEnd = utteranceStart + Math.min(endOffset, utteranceLength);
        if (rangeEnd < utteranceEnd && Character.isLowSurrogate(bookText.charAt(rangeEnd))) {
            rangeEnd++;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastSaved >= SAVE_DELAY_MS) savePosition();
        if (!hasWordRange || now - lastPublished >= UPDATE_DELAY_MS) publish();
        hasWordRange = true;
    }

    private void onSpeechDone(String id) {
        if (preview != null && id.equals(preview.utteranceId)) {
            preview.utteranceId = null;
            finishPreview(null);
            return;
        }
        if (!id.equals(utteranceId)) return;
        utteranceId = null;
        position = utteranceEnd;
        savePosition();
        startSpeechIfReady();
    }

    private void seek(int requested) {
        position = clampPosition(requested);
        if (bookId != null && bookText == null) {
            store.savePosition(bookId, position);
        } else {
            savePosition();
        }
        restartUtterance();
    }

    private int clampPosition(int requested) {
        if (bookText == null) return Math.max(0, requested);
        int clamped = Math.max(0, Math.min(requested, bookText.length()));
        if (clamped > 0 && clamped < bookText.length()
                && Character.isLowSurrogate(bookText.charAt(clamped))
                && Character.isHighSurrogate(bookText.charAt(clamped - 1))) {
            clamped--;
        }
        return clamped;
    }

    private void changeSpeed(float requested) {
        if (!Float.isFinite(requested)) return;
        float newSpeed = Math.max(0.5f, Math.min(2f, requested));
        if (newSpeed == speed) return;
        speed = newSpeed;
        store.saveSpeed(speed);
        restartUtterance();
    }

    private void changeLanguage(String requestedBook, String requestedLanguage) {
        if (!supportedLanguage(requestedLanguage)) return;
        if (requestedBook == null) requestedBook = bookId;
        if (requestedBook == null) return;
        store.saveLanguage(requestedBook, requestedLanguage);
        if (requestedBook.equals(bookId) && !requestedLanguage.equals(language)) {
            language = requestedLanguage;
            error = null;
            restartUtterance();
        }
    }

    private void restartUtterance() {
        stopUtterance();
        refresh();
        startSpeechIfReady();
    }

    private void onAudioFocusChanged(int focus, AudioFocusRequest request) {
        if (focus >= 0 || heldFocus != request) return;
        if (wantsPlayback) {
            pause();
        } else if (preview != null) {
            finishPreview(null);
        }
    }

    private void pause() {
        wantsPlayback = false;
        clearPreview(null);
        stopUtterance();
        releaseAudio();
        savePosition();
        if (foreground) {
            stopForeground(STOP_FOREGROUND_DETACH);
            foreground = false;
        }
        refresh();
    }

    private void stop() {
        wantsPlayback = false;
        clearPreview(null);
        savePosition();
        stopUtterance();
        releaseAudio();
        loadGeneration++;
        loading = false;
        bookId = null;
        title = "";
        position = 0;
        bookText = null;
        bookPages = null;
        error = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        notifications.cancel(NOTIFICATION_ID);
        mediaSession.setActive(false);
        publish();
        stopSelf();
    }

    private void fail(String message) {
        error = message;
        wantsPlayback = false;
        clearPreview(message);
        stopUtterance();
        releaseAudio();
        savePosition();
        stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        notifications.cancel(NOTIFICATION_ID);
        mediaSession.setActive(false);
        publish();
        stopSelf();
    }

    private void stopUtterance() {
        if (utteranceId == null) return;
        utteranceId = null;
        engine.stop();
    }

    private boolean requestFocus(AudioFocusRequest request) {
        if (heldFocus == request || (heldFocus == audioFocus && request == previewFocus)) return true;
        if (heldFocus != null) {
            audioManager.abandonAudioFocusRequest(heldFocus);
            heldFocus = null;
        }
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false;
        heldFocus = request;
        return true;
    }

    private void releaseAudio() {
        if (heldFocus != null) {
            audioManager.abandonAudioFocusRequest(heldFocus);
            heldFocus = null;
        }
        if (wakeLock.isHeld()) wakeLock.release();
    }

    private void savePosition() {
        if (bookId != null && bookText != null) {
            store.savePosition(bookId, position);
            lastSaved = SystemClock.elapsedRealtime();
        }
    }

    private boolean isPreparing() {
        return wantsPlayback && (loading || bookText == null || !engineReady);
    }

    private void publish() {
        lastPublished = SystemClock.elapsedRealtime();
        int currentEnd = utteranceId == null ? position : rangeEnd;
        snapshot = new Snapshot(bookId, language, position, currentEnd, wantsPlayback, isPreparing(), !destroyed, error);
        notifyListeners();
    }

    private static void notifyListeners() {
        for (Runnable listener : LISTENERS) {
            listener.run();
        }
    }

    private void refresh() {
        int playbackState = isPreparing() ? PlaybackState.STATE_BUFFERING
                : wantsPlayback ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP)
                .setState(playbackState, PlaybackState.PLAYBACK_POSITION_UNKNOWN, wantsPlayback ? speed : 0f)
                .build());
        if (bookId != null && error == null) notifications.notify(NOTIFICATION_ID, notification());
        publish();
    }

    private void showForeground() {
        if (foreground) return;
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification());
        }
        foreground = true;
    }

    private Notification notification() {
        boolean pauseAction = wantsPlayback;
        Intent action = new Intent(this, PlaybackService.class)
                .setAction(pauseAction ? ACTION_PAUSE : ACTION_PLAY)
                .putExtra(EXTRA_BOOK_ID, bookId);
        PendingIntent playbackIntent = pauseAction
                ? PendingIntent.getService(this, 1, action, pendingIntentFlags())
                : PendingIntent.getForegroundService(this, 2, action, pendingIntentFlags());
        PendingIntent stopIntent = PendingIntent.getService(this, 3,
                new Intent(this, PlaybackService.class).setAction(ACTION_STOP), pendingIntentFlags());
        CharSequence notificationTitle = title.isEmpty()
                ? getApplicationInfo().loadLabel(getPackageManager()) : title;
        String status = isPreparing() ? "Подготовка к чтению"
                : wantsPlayback ? "Книга озвучивается" : "На паузе";
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(notificationTitle)
                .setContentText(status)
                .setContentIntent(activityIntent())
                .setDeleteIntent(stopIntent)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setOngoing(wantsPlayback)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        pauseAction ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        pauseAction ? "Пауза" : "Продолжить", playbackIntent).build())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "Остановить", stopIntent).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0))
                .build();
    }

    private PendingIntent activityIntent() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, open, pendingIntentFlags());
    }

    private static int pendingIntentFlags() {
        return PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
    }

    private void resetEngine() {
        engineGeneration++;
        engineReady = false;
        selectedLanguage = null;
        selectedVoicePreference = null;
        enginePackage = null;
        if (engine != null) {
            engine.shutdown();
            engine = null;
        }
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        wantsPlayback = false;
        clearPreview(null);
        if (catalogRequest != null) {
            setVoiceCatalog(new VoiceCatalog(catalogRequest, Collections.emptyList(), false, false,
                    "Системная озвучка отключилась. Откройте список голосов снова."));
            catalogRequest = null;
        }
        stopUtterance();
        savePosition();
        releaseAudio();
        resetEngine();
        reader.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        unregisterReceiver(noisyReceiver);
        mediaSession.release();
        notifications.cancel(NOTIFICATION_ID);
        publish();
        super.onDestroy();
    }
}
