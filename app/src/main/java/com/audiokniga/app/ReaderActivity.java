package com.audiokniga.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.Layout;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ReaderActivity extends Activity {
    private static final int INK = 0xff1b2c26;
    private static final int GREEN = 0xff1d4c3e;
    private static final int PAPER = 0xfff5f4ea;
    private static final int PAGE = 0xfffffef7;
    private static final int LIGHT = 0xffdeeee0;
    private static final int MUTED = 0xff596c63;

    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Runnable playbackChanges = this::updateReader;
    private final BackgroundColorSpan highlight = new BackgroundColorSpan(0xffb8ddbf);
    private final ForegroundColorSpan highlightInk = new ForegroundColorSpan(0xff17382a);

    private BookStore store;
    private String bookId;
    private BookPages pages;
    private int displayedPage = -1;
    private int highlightStart = -1;
    private int highlightEnd = -1;
    private int pendingPosition = -1;
    private long followAfter;
    private Button pageButton;
    private Button previous;
    private Button next;
    private Button play;
    private TextView state;
    private TextView text;
    private ScrollView scroll;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        store = new BookStore(this);
        bookId = getIntent().getStringExtra(PlaybackService.EXTRA_BOOK_ID);
        BookStore.Book book = store.get(bookId);
        if (book == null) {
            finish();
            return;
        }
        store.select(book.id);
        makeScreen(book.title);
        loader.execute(() -> {
            try {
                BookPages loaded = new BookPages(store.readText(book.id));
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        pages = loaded;
                        updateReader();
                    }
                });
            } catch (IOException exception) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        text.setText("Не удалось открыть текст книги.");
                    }
                });
            }
        });
    }

    @Override protected void onStart() {
        super.onStart();
        PlaybackService.addListener(playbackChanges);
        updateReader();
    }

    @Override protected void onStop() {
        PlaybackService.removeListener(playbackChanges);
        super.onStop();
    }

    @Override protected void onDestroy() {
        loader.shutdownNow();
        super.onDestroy();
    }

    private void makeScreen(String title) {
        getWindow().setStatusBarColor(PAPER);
        getWindow().setNavigationBarColor(PAPER);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAPER);
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            });
        }

        LinearLayout header = row();
        header.setPadding(dp(12), dp(8), dp(16), dp(6));
        root.addView(header, fullWidth());
        Button back = button("‹", INK, PAPER, 27);
        back.setContentDescription("Назад к библиотеке");
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        back.setOnClickListener(view -> finish());

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(INK);
        titleView.setTextSize(23);
        titleView.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setMaxLines(2);
        if (Build.VERSION.SDK_INT >= 28) {
            titleView.setAccessibilityHeading(true);
        }
        header.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout navigation = row();
        navigation.setPadding(dp(16), dp(4), dp(16), dp(9));
        root.addView(navigation, fullWidth());
        previous = button("‹", GREEN, LIGHT, 25);
        previous.setContentDescription("Предыдущая страница");
        navigation.addView(previous, new LinearLayout.LayoutParams(dp(48), dp(48)));
        previous.setOnClickListener(view -> goToPage(displayedPage - 1));

        pageButton = button("Страницы", GREEN, PAGE, 14);
        LinearLayout.LayoutParams pageParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        pageParams.setMargins(dp(8), 0, dp(8), 0);
        navigation.addView(pageButton, pageParams);
        pageButton.setOnClickListener(view -> showPageInput());

        next = button("›", GREEN, LIGHT, 25);
        next.setContentDescription("Следующая страница");
        navigation.addView(next, new LinearLayout.LayoutParams(dp(48), dp(48)));
        next.setOnClickListener(view -> goToPage(displayedPage + 1));
        previous.setEnabled(false);
        pageButton.setEnabled(false);
        next.setEnabled(false);

        scroll = new ScrollView(this) {
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        followAfter = Long.MAX_VALUE;
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        followAfter = android.os.SystemClock.uptimeMillis() + 2000;
                        break;
                    default:
                        break;
                }
                return super.dispatchTouchEvent(event);
            }
        };
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAGE);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        text = new TextView(this);
        text.setText("Открываем текст…");
        text.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        text.setTextColor(INK);
        text.setTextSize(20);
        text.setLineSpacing(dp(5), 1f);
        text.setPadding(dp(20), dp(16), dp(20), dp(24));
        scroll.addView(text, new ScrollView.LayoutParams(-1, -2));

        LinearLayout controls = row();
        controls.setPadding(dp(16), dp(8), dp(16), dp(9));
        root.addView(controls, fullWidth());
        state = new TextView(this);
        state.setTextColor(MUTED);
        state.setTextSize(13);
        state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(0, -2, 1);
        statusParams.rightMargin = dp(10);
        controls.addView(state, statusParams);

        play = button("Слушать", 0xfffffff9, GREEN, 15);
        controls.addView(play, new LinearLayout.LayoutParams(-2, dp(48)));
        play.setOnClickListener(view -> togglePlayback());
        play.setEnabled(false);
        setContentView(root);
    }

    private void updateReader() {
        if (pages == null || isFinishing()) {
            return;
        }
        BookStore.Book book = store.get(bookId);
        if (book == null) {
            finish();
            return;
        }
        PlaybackService.Snapshot playback = PlaybackService.snapshot();
        boolean active = playback.attached && bookId.equals(playback.bookId);
        int position = active ? playback.position : book.position;
        if (pendingPosition >= 0) {
            if (!active || position >= pendingPosition && pages.pageForPosition(position)
                    == pages.pageForPosition(pendingPosition)) {
                pendingPosition = -1;
            } else {
                position = pendingPosition;
            }
        }
        int page = pages.pageForPosition(position);
        boolean changedPage = page != displayedPage;
        if (changedPage) {
            displayedPage = page;
            highlightStart = -1;
            highlightEnd = -1;
            text.setText(pages.text(page), TextView.BufferType.SPANNABLE);
            pageButton.setText(getString(R.string.reader_page, page + 1, pages.count()));
            pageButton.setContentDescription(getString(R.string.reader_page_action, page + 1, pages.count()));
            pageButton.setEnabled(true);
            setPageButtonEnabled(previous, page > 0);
            setPageButtonEnabled(next, page + 1 < pages.count());
            scroll.scrollTo(0, 0);
        }
        boolean hasHighlight = active && pendingPosition < 0 && playback.rangeEnd > position;
        if (hasHighlight) {
            setHighlight(position, playback.rangeEnd);
        } else {
            setHighlight(-1, -1);
        }
        if (changedPage) {
            int offset = position;
            scroll.post(() -> scrollToPosition(offset, page));
        } else if (hasHighlight && playback.playing && followAfter <= android.os.SystemClock.uptimeMillis()) {
            scrollToPosition(position, page);
        }
        play.setEnabled(true);
        boolean playing = active && playback.playing;
        setLabel(play, playing ? "Пауза" : "Слушать");
        if (bookId.equals(playback.bookId) && playback.error != null) {
            setLabel(state, playback.error);
        } else if (playing) {
            setLabel(state, playback.preparing ? "Подготовка озвучки" : "Воспроизводится");
        } else if (position >= book.length) {
            setLabel(state, "Прослушано");
        } else {
            setLabel(state, position == 0 ? "Готово к чтению" : "Пауза");
        }
    }

    private void setHighlight(int from, int to) {
        int start = pages.start(displayedPage);
        int end = pages.end(displayedPage);
        int relativeFrom = Math.max(0, from - start);
        int relativeTo = Math.min(end, to) - start;
        if (relativeTo <= relativeFrom) {
            relativeFrom = -1;
            relativeTo = -1;
        }
        if (relativeFrom == highlightStart && relativeTo == highlightEnd) {
            return;
        }
        Spannable visibleText = (Spannable) text.getText();
        visibleText.removeSpan(highlight);
        visibleText.removeSpan(highlightInk);
        highlightStart = relativeFrom;
        highlightEnd = relativeTo;
        if (highlightStart >= 0) {
            visibleText.setSpan(highlight, highlightStart, highlightEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            visibleText.setSpan(highlightInk, highlightStart, highlightEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void scrollToPosition(int position, int page) {
        if (page != displayedPage || position < pages.start(page) || position >= pages.end(page)) {
            return;
        }
        Layout layout = text.getLayout();
        if (layout == null) {
            return;
        }
        int line = layout.getLineForOffset(position - pages.start(page));
        int y = text.getTotalPaddingTop() + layout.getLineTop(line);
        int visibleTop = scroll.getScrollY();
        int visibleBottom = visibleTop + scroll.getHeight();
        if (y < visibleTop || y + dp(50) > visibleBottom) {
            scroll.smoothScrollTo(0, Math.max(0, y - dp(34)));
        }
    }

    private void goToPage(int page) {
        if (pages == null || page < 0 || page >= pages.count()) {
            return;
        }
        int position = pages.start(page);
        PlaybackService.Snapshot playback = PlaybackService.snapshot();
        followAfter = 0;
        if (playback.attached && bookId.equals(playback.bookId)) {
            pendingPosition = position;
            startService(playbackIntent(PlaybackService.ACTION_SEEK)
                    .putExtra(PlaybackService.EXTRA_POSITION, position));
        } else {
            store.savePosition(bookId, position);
        }
        updateReader();
    }

    private void showPageInput() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setHint(getString(R.string.reader_page_range, pages.count()));
        input.setText(String.format(Locale.getDefault(), "%d", displayedPage + 1));
        input.setSelectAllOnFocus(true);
        LinearLayout field = new LinearLayout(this);
        field.setPadding(dp(24), dp(10), dp(24), 0);
        field.addView(input, fullWidth());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Перейти на страницу")
                .setView(field)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Перейти", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Runnable jump = () -> {
                int number;
                try {
                    number = Integer.parseInt(input.getText().toString().trim());
                } catch (NumberFormatException exception) {
                    number = 0;
                }
                if (number < 1 || number > pages.count()) {
                    input.setError(getString(R.string.reader_page_range, pages.count()));
                    return;
                }
                goToPage(number - 1);
                dialog.dismiss();
            };
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> jump.run());
            input.setOnEditorActionListener((view, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    jump.run();
                    return true;
                }
                return false;
            });
            input.requestFocus();
            input.post(() -> getSystemService(InputMethodManager.class)
                    .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT));
        });
        dialog.show();
    }

    private void togglePlayback() {
        PlaybackService.Snapshot playback = PlaybackService.snapshot();
        if (bookId.equals(playback.bookId) && playback.playing) {
            startService(playbackIntent(PlaybackService.ACTION_PAUSE));
        } else {
            startForegroundService(playbackIntent(PlaybackService.ACTION_PLAY));
        }
    }

    private Intent playbackIntent(String action) {
        return new Intent(this, PlaybackService.class).setAction(action)
                .putExtra(PlaybackService.EXTRA_BOOK_ID, bookId);
    }

    private static void setLabel(TextView view, String label) {
        if (!TextUtils.equals(view.getText(), label)) {
            view.setText(label);
        }
    }

    private static void setPageButtonEnabled(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.45f);
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private Button button(String label, int foreground, int background, int size) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextColor(foreground);
        button.setTextSize(size);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMaxLines(1);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setPadding(dp(9), 0, dp(9), 0);
        button.setStateListAnimator(null);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(background);
        shape.setCornerRadius(dp(13));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2275a58a), shape, null));
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
