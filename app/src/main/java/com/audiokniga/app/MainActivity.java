package com.audiokniga.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_BOOK = 41;
    private static final int INK = Color.rgb(27, 44, 38);
    private static final int GREEN = Color.rgb(29, 76, 62);
    private static final int MOSS = Color.rgb(49, 102, 83);
    private static final int MUTED = Color.rgb(89, 108, 99);
    private static final int PAPER = Color.rgb(245, 244, 234);
    private static final int CARD = Color.rgb(255, 254, 247);
    private static final int BORDER = Color.rgb(220, 226, 214);
    private static final int LIGHT = Color.rgb(222, 238, 224);
    private static final int WHITE = Color.rgb(255, 255, 250);
    private static final int[] COVER_COLORS = {MOSS, 0xff9b5b44, 0xff455d81, 0xff694965};
    private static final ExecutorService IMPORTER = Executors.newSingleThreadExecutor();

    private BookStore store;
    private String selectedId;
    private TextView importButton;
    private LinearLayout playerContainer;
    private LinearLayout library;
    private TextView libraryCount;
    private TextView playerTitle;
    private TextView playerFormat;
    private TextView playerLanguage;
    private TextView playerSpeed;
    private TextView playerStatus;
    private TextView progressLabel;
    private TextView playButton;
    private SeekBar seekBar;
    private BookCover playerCover;
    private boolean dragging;
    private boolean importing;
    private String playerId;
    private String lastError;
    private AlertDialog voiceDialog;
    private LinearLayout voiceDialogContent;
    private String voiceBook;
    private String voiceLanguage;
    private String voiceChoice;
    private boolean previewRequested;
    private PlaybackService.VoiceCatalog catalogAtOpen;
    private PlaybackService.VoiceCatalog displayedCatalog;

    private final Runnable playbackChanges = () -> {
        renderPlayer();
        renderLibrary();
        renderVoices();
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        store = new BookStore(this);
        PlaybackService.Snapshot playback = PlaybackService.snapshot();
        selectedId = playback.playing ? playback.bookId : store.selected();
        makeScreen();
        refreshSelection();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        PlaybackService.Snapshot playback = PlaybackService.snapshot();
        if (store.get(playback.bookId) != null) {
            select(playback.bookId);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        PlaybackService.addListener(playbackChanges);
        refreshSelection();
    }

    @Override protected void onStop() {
        if (voiceDialog != null) {
            voiceDialog.dismiss();
        }
        PlaybackService.removeListener(playbackChanges);
        super.onStop();
    }

    private void makeScreen() {
        getWindow().setStatusBarColor(PAPER);
        getWindow().setNavigationBarColor(PAPER);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PAPER);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        if (Build.VERSION.SDK_INT >= 35) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            });
        }

        LinearLayout root = column();
        root.setPadding(dp(20), dp(20), dp(20), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, fullWidth());
        TextView title = label("Аудиокнига", 32, INK);
        title.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setAutoSizeTextTypeUniformWithConfiguration(20, 32, 1, TypedValue.COMPLEX_UNIT_SP);
        markHeading(title);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1f);
        titleParams.rightMargin = dp(8);
        header.addView(title, titleParams);
        importButton = button("+ Книга", WHITE, GREEN, 14);
        importButton.setContentDescription("Добавить книгу");
        header.addView(importButton, new LinearLayout.LayoutParams(-2, dp(48)));
        importButton.setOnClickListener(view -> pickBook());

        playerContainer = column();
        playerContainer.setPadding(dp(18), dp(19), dp(18), dp(16));
        playerContainer.setBackground(shape(GREEN, 24));
        root.addView(playerContainer, spaced(22));
        makePlayer();

        LinearLayout subheader = row();
        subheader.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(subheader, spaced(25));
        TextView libraryTitle = label("Моя библиотека", 23, INK);
        libraryTitle.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        markHeading(libraryTitle);
        subheader.addView(libraryTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        libraryCount = label("", 13, MUTED);
        subheader.addView(libraryCount, new LinearLayout.LayoutParams(-2, -2));

        library = column();
        root.addView(library, spaced(11));
        setContentView(scroll);
    }

    private void makePlayer() {
        LinearLayout nowPlaying = row();
        nowPlaying.setGravity(Gravity.CENTER_VERTICAL);
        playerContainer.addView(nowPlaying, fullWidth());

        playerCover = new BookCover(this, MOSS);
        nowPlaying.addView(playerCover, new LinearLayout.LayoutParams(dp(62), dp(76)));
        LinearLayout bookInfo = column();
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(0, -2, 1f);
        infoParams.leftMargin = dp(14);
        nowPlaying.addView(bookInfo, infoParams);

        playerFormat = label("", 12, LIGHT);
        bookInfo.addView(playerFormat);
        playerTitle = label("", 25, WHITE);
        playerTitle.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        playerTitle.setMaxLines(3);
        playerTitle.setEllipsize(TextUtils.TruncateAt.END);
        bookInfo.addView(playerTitle, spaced(4));

        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setSplitTrack(false);
        seekBar.setProgressTintList(ColorStateList.valueOf(LIGHT));
        seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(0xff729587));
        seekBar.setThumbTintList(ColorStateList.valueOf(WHITE));
        seekBar.setContentDescription("Позиция в книге");
        seekBar.setPadding(dp(9), 0, dp(9), 0);
        LinearLayout.LayoutParams seekParams = spaced(12);
        seekParams.height = dp(38);
        playerContainer.addView(seekBar, seekParams);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    setProgressLabel(progress);
                    if (!dragging) {
                        seekTo(progress);
                    }
                }
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {
                dragging = true;
            }

            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                seekTo(bar.getProgress());
            }
        });

        LinearLayout statusRow = row();
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        playerContainer.addView(statusRow, fullWidth());
        playerStatus = label("", 13, LIGHT);
        playerStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusRow.addView(playerStatus, new LinearLayout.LayoutParams(0, -2, 1f));
        progressLabel = label("", 13, LIGHT);
        statusRow.addView(progressLabel, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout controls = row();
        controls.setGravity(Gravity.CENTER_VERTICAL);
        playerContainer.addView(controls, spaced(15));
        playerLanguage = button("", WHITE, 0xff356451, 13);
        controls.addView(playerLanguage, new LinearLayout.LayoutParams(-2, dp(48)));
        playerLanguage.setOnClickListener(view -> chooseLanguage());

        playButton = button("", GREEN, LIGHT, 15);
        playButton.setPadding(dp(4), 0, dp(4), 0);
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        playParams.setMargins(dp(8), 0, dp(8), 0);
        controls.addView(playButton, playParams);
        playButton.setOnClickListener(view -> togglePlayback());

        playerSpeed = button("", WHITE, 0xff356451, 13);
        controls.addView(playerSpeed, new LinearLayout.LayoutParams(-2, dp(48)));
        playerSpeed.setOnClickListener(view -> chooseSpeed());

        LinearLayout bookActions = row();
        playerContainer.addView(bookActions, spaced(10));
        TextView openText = button("Текст книги", WHITE, 0xff356451, 13);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, dp(48), 1.7f);
        textParams.rightMargin = dp(8);
        bookActions.addView(openText, textParams);
        openText.setOnClickListener(view -> openReader());

        TextView voices = button("Голос", WHITE, 0xff356451, 13);
        bookActions.addView(voices, new LinearLayout.LayoutParams(0, dp(48), 1f));
        voices.setOnClickListener(view -> chooseVoice());
    }

    private void refreshSelection() {
        if (store.get(selectedId) == null) {
            List<BookStore.Book> books = store.all();
            selectedId = books.isEmpty() ? null : books.get(0).id;
            store.select(selectedId);
        }
        renderPlayer();
        renderLibrary();
    }

    private void select(String id) {
        selectedId = id;
        lastError = null;
        store.select(id);
        renderPlayer();
        renderLibrary();
    }

    private void renderPlayer() {
        BookStore.Book book = store.get(selectedId);
        if (book == null) {
            playerContainer.setVisibility(View.GONE);
            return;
        }
        playerContainer.setVisibility(View.VISIBLE);
        PlaybackService.Snapshot playback = PlaybackService.snapshot();
        boolean active = isActive(book.id, playback);
        boolean playing = active && playback.playing;
        boolean preparing = active && playback.preparing;
        int position = active ? playback.position : book.position;
        if (!book.id.equals(playerId)) {
            playerId = book.id;
            playerCover.color = coverColor(book);
            playerCover.invalidate();
            playerTitle.setText(book.title);
            playerFormat.setText(book.format);
        }
        if (!dragging) {
            int progress = toProgress(position, book.length);
            seekBar.setProgress(progress);
            setProgressLabel(progress);
        }
        String language = active && playback.language != null ? playback.language : book.language;
        playerLanguage.setText("en".equals(language) ? "EN" : "РУ");
        playerLanguage.setContentDescription("Язык озвучки. " + ("en".equals(language) ? "Английский" : "Русский"));
        String speedText = speedText(store.speed());
        playerSpeed.setText(speedText);
        playerSpeed.setContentDescription("Скорость озвучки. " + speedText);
        String playText = playing ? "Пауза" : "Слушать";
        playButton.setText(playText);
        playButton.setContentDescription(playText);
        if (book.id.equals(playback.bookId) && playback.error != null) {
            if (!playback.error.equals(lastError)) {
                lastError = playback.error;
                if (hasWindowFocus()) {
                    showPlaybackError(playback.error);
                }
            }
            playerStatus.setText(playback.error);
        } else {
            lastError = null;
            if (preparing) {
                playerStatus.setText("Подготовка озвучки");
            } else if (playing) {
                playerStatus.setText("Воспроизводится");
            } else if (position >= book.length) {
                playerStatus.setText("Прослушано");
            } else if (position > 0) {
                playerStatus.setText("Пауза");
            } else {
                playerStatus.setText("Готово к чтению");
            }
        }
    }

    private void renderLibrary() {
        List<BookStore.Book> books = store.all();
        PlaybackService.Snapshot playback = PlaybackService.snapshot();
        String signature = librarySignature(books, playback);
        if (signature.equals(library.getTag())) {
            return;
        }
        library.setTag(signature);
        library.removeAllViews();
        libraryCount.setText(books.isEmpty() ? "" : Integer.toString(books.size()));
        if (books.isEmpty()) {
            makeEmptyCard();
        } else {
            for (BookStore.Book book : books) {
                addBookRow(book, playback);
            }
        }
    }

    private String librarySignature(List<BookStore.Book> books, PlaybackService.Snapshot playback) {
        StringBuilder signature = new StringBuilder();
        signature.append(selectedId).append('/').append(playback.bookId).append('/').append(playback.playing);
        for (BookStore.Book book : books) {
            int position = isActive(book.id, playback) ? playback.position : book.position;
            signature.append('/').append(book.id).append(':')
                    .append(toProgress(position, book.length) / 10);
        }
        return signature.toString();
    }

    private void makeEmptyCard() {
        LinearLayout empty = column();
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        empty.setPadding(dp(20), dp(25), dp(20), dp(25));
        empty.setBackground(outlined(CARD, BORDER, 21));
        library.addView(empty, fullWidth());
        BookCover cover = new BookCover(this, MOSS);
        empty.addView(cover, new LinearLayout.LayoutParams(dp(68), dp(84)));
        TextView heading = label("Добавьте первую книгу", 22, INK);
        heading.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        heading.setGravity(Gravity.CENTER);
        empty.addView(heading, spaced(15));
        TextView description = label("Выберите файл EPUB, FB2 или TXT на телефоне.", 14, MUTED);
        description.setGravity(Gravity.CENTER);
        empty.addView(description, spaced(7));
    }

    private void addBookRow(BookStore.Book book, PlaybackService.Snapshot playback) {
        boolean selected = book.id.equals(selectedId);
        LinearLayout item = row();
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(10), dp(9), dp(8), dp(9));
        item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22356a4b),
                outlined(selected ? 0xffe8efe0 : CARD, selected ? 0xffb4c7af : BORDER, 17), null));
        library.addView(item, spaced(8));

        BookCover cover = new BookCover(this, coverColor(book));
        item.addView(cover, new LinearLayout.LayoutParams(dp(38), dp(48)));
        LinearLayout metadata = column();
        LinearLayout.LayoutParams metadataParams = new LinearLayout.LayoutParams(0, -2, 1f);
        metadataParams.leftMargin = dp(11);
        item.addView(metadata, metadataParams);

        TextView title = label(book.title, 16, INK);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        metadata.addView(title);

        boolean active = isActive(book.id, playback);
        int position = active ? playback.position : book.position;
        int percent = toProgress(position, book.length) / 10;
        String detail = book.format;
        if (active && playback.playing) {
            detail += "  ·  Воспроизводится";
        } else if (percent > 0) {
            detail += "  ·  " + percent + " %";
        }
        metadata.addView(label(detail, 12, MUTED), spaced(3));
        item.setOnClickListener(view -> select(book.id));
        item.setOnLongClickListener(view -> {
            confirmRemoval(book);
            return true;
        });
        TextView more = button("⋮", MUTED, Color.TRANSPARENT, 22);
        more.setContentDescription("Действия с книгой " + book.title);
        more.setPadding(0, 0, 0, 0);
        more.setMinimumWidth(0);
        item.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
        more.setOnClickListener(view -> confirmRemoval(book));
    }

    private void confirmRemoval(BookStore.Book book) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить книгу из библиотеки?")
                .setMessage(book.title)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (dialog, which) -> {
                    if (isActive(book.id, PlaybackService.snapshot())) {
                        startService(playbackIntent(PlaybackService.ACTION_STOP));
                    }
                    if (store.remove(book.id)) {
                        refreshSelection();
                    } else {
                        showMessage("Не удалось удалить книгу.", null);
                    }
                }).show();
    }

    private void pickBook() {
        if (importing) {
            return;
        }
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("*/*");
        try {
            startActivityForResult(picker, PICK_BOOK);
        } catch (android.content.ActivityNotFoundException exception) {
            showMessage("Не удалось открыть выбор файлов.", null);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_BOOK || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        importing = true;
        importButton.setEnabled(false);
        importButton.setText("Импорт…");
        Context app = getApplicationContext();
        IMPORTER.execute(() -> {
            BookStore.Book book = null;
            String error = null;
            try {
                String filename = displayName(app, uri);
                try (InputStream input = app.getContentResolver().openInputStream(uri)) {
                    if (input == null) {
                        throw new IOException("Файл недоступен.");
                    }
                    BookParser.ParsedBook parsed = BookParser.parse(input, filename);
                    book = store.add(parsed, filename.substring(filename.lastIndexOf('.') + 1)
                            .toUpperCase(Locale.ROOT));
                    store.select(book.id);
                }
            } catch (IOException exception) {
                error = exception.getMessage();
                if (error == null || error.trim().isEmpty()) {
                    error = "Проверьте, что файл доступен и имеет формат EPUB, FB2 или TXT.";
                }
            } catch (RuntimeException exception) {
                error = "Проверьте, что файл доступен и имеет формат EPUB, FB2 или TXT.";
            }
            BookStore.Book imported = book;
            String message = error;
            runOnUiThread(() -> finishImport(imported, message));
        });
    }

    private void finishImport(BookStore.Book book, String error) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        importing = false;
        importButton.setEnabled(true);
        importButton.setText("+ Книга");
        if (book != null) {
            select(book.id);
        } else {
            showMessage("Не удалось импортировать книгу", error);
        }
    }

    private static String displayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            }
        }
        String fallback = uri.getLastPathSegment();
        return fallback == null ? "" : fallback;
    }

    private void togglePlayback() {
        BookStore.Book book = store.get(selectedId);
        if (book == null) {
            return;
        }
        PlaybackService.Snapshot playback = PlaybackService.snapshot();
        if (book.id.equals(playback.bookId) && playback.playing) {
            startService(playbackIntent(PlaybackService.ACTION_PAUSE));
        } else {
            lastError = null;
            startForegroundService(playbackIntent(PlaybackService.ACTION_PLAY)
                    .putExtra(PlaybackService.EXTRA_BOOK_ID, book.id));
        }
    }

    private void seekTo(int progress) {
        BookStore.Book book = store.get(selectedId);
        if (book == null) {
            return;
        }
        int position = (int) ((long) progress * book.length / 1000);
        if (isActive(book.id, PlaybackService.snapshot())) {
            startService(playbackIntent(PlaybackService.ACTION_SEEK)
                    .putExtra(PlaybackService.EXTRA_BOOK_ID, book.id)
                    .putExtra(PlaybackService.EXTRA_POSITION, position));
        } else {
            store.savePosition(book.id, position);
            renderPlayer();
            renderLibrary();
        }
    }

    private void openReader() {
        BookStore.Book book = store.get(selectedId);
        if (book != null) {
            startActivity(new Intent(this, ReaderActivity.class)
                    .putExtra(PlaybackService.EXTRA_BOOK_ID, book.id));
        }
    }

    private void chooseVoice() {
        BookStore.Book book = store.get(selectedId);
        if (book == null) {
            return;
        }
        voiceBook = book.id;
        voiceLanguage = book.language;
        voiceChoice = store.voice(book.id, book.language);
        catalogAtOpen = PlaybackService.voiceCatalog();
        displayedCatalog = null;
        previewRequested = false;

        voiceDialogContent = column();
        voiceDialogContent.setPadding(dp(24), dp(12), dp(24), dp(12));
        voiceDialogContent.addView(label("Загружаем голоса Android…", 14, MUTED));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(voiceDialogContent);

        voiceDialog = new AlertDialog.Builder(this)
                .setTitle("en".equals(voiceLanguage) ? "Английский голос" : "Русский голос")
                .setView(scroll)
                .setNegativeButton("Отмена", null)
                .setNeutralButton("Прослушать", null)
                .setPositiveButton("Применить", (dialog, which) ->
                        startService(voiceIntent(PlaybackService.ACTION_VOICE)))
                .create();
        voiceDialog.setOnDismissListener(dialog -> {
            if (previewRequested) {
                startService(playbackIntent(PlaybackService.ACTION_CANCEL_PREVIEW));
            }
            voiceDialog = null;
            voiceDialogContent = null;
        });
        voiceDialog.show();
        voiceDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
        voiceDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        startService(voiceIntent(PlaybackService.ACTION_LIST_VOICES));
    }

    private void renderVoices() {
        if (voiceDialog == null) {
            return;
        }
        PlaybackService.VoiceCatalog catalog = PlaybackService.voiceCatalog();
        if (catalog == displayedCatalog || catalog == catalogAtOpen || !voiceLanguage.equals(catalog.language)) {
            return;
        }
        displayedCatalog = catalog;
        Button preview = voiceDialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        Button apply = voiceDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        voiceDialogContent.removeAllViews();
        preview.setEnabled(false);
        apply.setEnabled(false);

        if (catalog.loading) {
            voiceDialogContent.addView(label("Загружаем голоса Android…", 14, MUTED));
            return;
        }
        if (catalog.options.isEmpty()) {
            String status = catalog.error == null
                    ? "Установите офлайн-голос нужного языка в настройках синтеза речи Android." : catalog.error;
            voiceDialogContent.addView(label(status, 14, INK));
            Intent settings = new Intent("com.android.settings.TTS_SETTINGS");
            preview.setText("Настройки");
            preview.setEnabled(settings.resolveActivity(getPackageManager()) != null);
            preview.setOnClickListener(view -> {
                voiceDialog.dismiss();
                startActivity(settings);
            });
            return;
        }

        if (catalog.error != null) {
            voiceDialogContent.addView(label(catalog.error, 14, INK));
        }
        RadioGroup voices = new RadioGroup(this);
        voiceDialogContent.addView(voices, fullWidth());
        boolean knownChoice = false;
        for (PlaybackService.VoiceOption option : catalog.options) {
            knownChoice |= option.id.equals(voiceChoice);
        }
        if (!knownChoice) {
            voiceChoice = null;
        }
        addVoiceOption(voices, "Автоматически", null);
        for (PlaybackService.VoiceOption option : catalog.options) {
            addVoiceOption(voices, option.label, option.id);
        }
        preview.setText(catalog.previewing ? "Остановить" : "Прослушать");
        preview.setEnabled(true);
        preview.setOnClickListener(view -> {
            previewRequested = true;
            startService(voiceIntent(catalog.previewing
                    ? PlaybackService.ACTION_CANCEL_PREVIEW : PlaybackService.ACTION_PREVIEW));
        });
        apply.setEnabled(true);
    }

    private void addVoiceOption(RadioGroup group, String label, String id) {
        RadioButton row = new RadioButton(this);
        row.setId(View.generateViewId());
        row.setText(label);
        row.setTextSize(15);
        row.setMinHeight(dp(48));
        group.addView(row);
        if (TextUtils.equals(id, voiceChoice)) {
            group.check(row.getId());
        }
        row.setOnClickListener(view -> voiceChoice = id);
    }

    private Intent voiceIntent(String action) {
        return playbackIntent(action).putExtra(PlaybackService.EXTRA_BOOK_ID, voiceBook)
                .putExtra(PlaybackService.EXTRA_LANGUAGE, voiceLanguage)
                .putExtra(PlaybackService.EXTRA_VOICE, voiceChoice);
    }

    private void chooseLanguage() {
        BookStore.Book book = store.get(selectedId);
        if (book == null) {
            return;
        }
        String[] languages = {"Русский", "Английский"};
        new AlertDialog.Builder(this).setTitle("Язык озвучки")
                .setSingleChoiceItems(languages, "en".equals(book.language) ? 1 : 0,
                        (dialog, index) -> {
                            dialog.dismiss();
                            String language = index == 0 ? "ru" : "en";
                            store.saveLanguage(book.id, language);
                            if (isActive(book.id, PlaybackService.snapshot())) {
                                startService(playbackIntent(PlaybackService.ACTION_LANGUAGE)
                                        .putExtra(PlaybackService.EXTRA_BOOK_ID, book.id)
                                        .putExtra(PlaybackService.EXTRA_LANGUAGE, language));
                            }
                            renderPlayer();
                        }).show();
    }

    private void chooseSpeed() {
        float[] rates = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
        String[] labels = new String[rates.length];
        int current = 2;
        float speed = store.speed();
        for (int i = 0; i < rates.length; i++) {
            labels[i] = speedText(rates[i]);
            if (Math.abs(speed - rates[i]) < 0.01f) {
                current = i;
            }
        }
        new AlertDialog.Builder(this).setTitle("Скорость озвучки")
                .setSingleChoiceItems(labels, current, (dialog, index) -> {
                    dialog.dismiss();
                    store.saveSpeed(rates[index]);
                    if (PlaybackService.snapshot().attached) {
                        startService(playbackIntent(PlaybackService.ACTION_SPEED)
                                .putExtra(PlaybackService.EXTRA_SPEED, rates[index]));
                    }
                    renderPlayer();
                }).show();
    }

    private Intent playbackIntent(String action) {
        return new Intent(this, PlaybackService.class).setAction(action);
    }

    private void showPlaybackError(String message) {
        Intent settings = new Intent("com.android.settings.TTS_SETTINGS");
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle("Озвучка недоступна").setMessage(message).setNegativeButton("Закрыть", null);
        if (settings.resolveActivity(getPackageManager()) != null) {
            dialog.setPositiveButton("Настройки озвучки", (view, which) -> startActivity(settings));
        }
        dialog.show();
    }

    private void showMessage(String title, String message) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setPositiveButton("Закрыть", null).show();
    }

    private void setProgressLabel(int progress) {
        progressLabel.setText(getString(R.string.playback_percent, progress / 10));
    }

    private static boolean isActive(String bookId, PlaybackService.Snapshot playback) {
        return playback.attached && bookId.equals(playback.bookId);
    }

    private static int toProgress(int position, int length) {
        return length <= 0 ? 0 : (int) Math.max(0, Math.min(1000, 1000L * position / length));
    }

    private static String speedText(float speed) {
        return java.text.NumberFormat.getNumberInstance(Locale.forLanguageTag("ru")).format(speed) + "×";
    }

    private static int coverColor(BookStore.Book book) {
        return COVER_COLORS[Math.floorMod(book.title.hashCode(), COVER_COLORS.length)];
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView label(String text, int sp, int color) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(color);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        label.setIncludeFontPadding(false);
        return label;
    }

    private static void markHeading(View view) {
        if (Build.VERSION.SDK_INT >= 28) {
            view.setAccessibilityHeading(true);
        }
    }

    private TextView button(String text, int textColor, int fill, int sp) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextColor(textColor);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        button.setIncludeFontPadding(false);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setMinWidth(dp(50));
        button.setMinimumWidth(dp(50));
        button.setMaxLines(1);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setPadding(dp(11), 0, dp(11), 0);
        button.setStateListAnimator(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3375a58a),
                shape(fill, 16), shape(Color.WHITE, 16)));
        return button;
    }

    private GradientDrawable shape(int fill, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private GradientDrawable outlined(int fill, int stroke, int radius) {
        GradientDrawable shape = shape(fill, radius);
        shape.setStroke(dp(1), stroke);
        return shape;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private LinearLayout.LayoutParams spaced(int top) {
        LinearLayout.LayoutParams params = fullWidth();
        params.topMargin = dp(top);
        return params;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class BookCover extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path waves = new Path();
        private int color;

        BookCover(Context context, int color) {
            super(context);
            this.color = color;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            canvas.drawRoundRect(0, 0, w, h, w * 0.15f, w * 0.15f, paint);
            paint.setColor(0x2fffffff);
            canvas.drawRoundRect(w * 0.12f, h * 0.09f, w * 0.88f, h * 0.90f,
                    w * 0.08f, w * 0.08f, paint);
            paint.setColor(0xffe9ecce);
            canvas.drawCircle(w * 0.67f, h * 0.32f, w * 0.105f, paint);

            waves.reset();
            waves.moveTo(w * 0.23f, h * 0.62f);
            waves.cubicTo(w * 0.40f, h * 0.39f, w * 0.59f, h * 0.76f, w * 0.77f, h * 0.53f);
            waves.moveTo(w * 0.23f, h * 0.72f);
            waves.cubicTo(w * 0.40f, h * 0.49f, w * 0.59f, h * 0.86f, w * 0.77f, h * 0.63f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(w * 0.031f);
            canvas.drawPath(waves, paint);
        }
    }
}
