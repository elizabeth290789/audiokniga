package com.audiokniga.app;

import java.util.Arrays;

/** Stable pages with offsets into the same UTF-16 text used by playback. */
final class BookPages {
    private static final int PAGE_LENGTH = 1_800;
    private static final int MIN_PAGE_LENGTH = 1_350;

    private final String text;
    private final int[] ends;

    BookPages(String text) {
        this.text = text;
        int[] boundaries = new int[text.length() / MIN_PAGE_LENGTH + 1];
        int pageCount = 0;
        int boundary = 0;
        do {
            boundary = nextEnd(text, boundary);
            boundaries[pageCount++] = boundary;
        } while (boundary < text.length());
        ends = Arrays.copyOf(boundaries, pageCount);
    }

    int count() {
        return ends.length;
    }

    int pageForPosition(int offset) {
        int position = Math.max(0, Math.min(offset, text.length()));
        int index = Arrays.binarySearch(ends, position);
        if (index >= 0) {
            return Math.min(index + 1, count() - 1);
        }
        return -index - 1;
    }

    int start(int page) {
        checkPage(page);
        return page == 0 ? 0 : ends[page - 1];
    }

    int end(int page) {
        return ends[page];
    }

    String text(int page) {
        return text.substring(start(page), end(page));
    }

    private void checkPage(int page) {
        if (page < 0 || page >= count()) {
            throw new IndexOutOfBoundsException("Page " + page);
        }
    }

    private static int nextEnd(String text, int start) {
        int limit = start + Math.min(PAGE_LENGTH, text.length() - start);
        if (limit == text.length()) {
            return limit;
        }
        if (Character.isHighSurrogate(text.charAt(limit - 1))
                && Character.isLowSurrogate(text.charAt(limit))) {
            limit--;
        }

        int lineBreak = -1;
        int sentenceBreak = -1;
        int wordBreak = -1;
        for (int boundary = limit; boundary >= start + MIN_PAGE_LENGTH; boundary--) {
            char ch = text.charAt(boundary - 1);
            if (ch == '\n') {
                if (text.charAt(boundary - 2) == '\n') {
                    return boundary;
                }
                if (lineBreak < 0) {
                    lineBreak = boundary;
                }
            } else if (Character.isWhitespace(ch)) {
                if (wordBreak < 0) {
                    wordBreak = boundary;
                }
                if (sentenceBreak < 0 && endsSentence(text, boundary - 1, start)) {
                    sentenceBreak = boundary;
                }
            }
        }
        if (lineBreak >= 0) {
            return lineBreak;
        }
        if (sentenceBreak >= 0) {
            return sentenceBreak;
        }
        return wordBreak >= 0 ? wordBreak : limit;
    }

    private static boolean endsSentence(String text, int space, int start) {
        int index = space - 1;
        while (index >= start && "»”\"’')]}".indexOf(text.charAt(index)) >= 0) {
            index--;
        }
        return index >= start && ".!?…".indexOf(text.charAt(index)) >= 0;
    }
}
