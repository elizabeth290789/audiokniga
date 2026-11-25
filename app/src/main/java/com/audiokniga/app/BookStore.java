package com.audiokniga.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class BookStore {
    private static final Object LOCK = new Object();
    private static final String INDEX_KEY = "library";
    private final SharedPreferences preferences;
    private final File directory;

    BookStore(Context context) {
        preferences = context.getSharedPreferences("audiokniga", Context.MODE_PRIVATE);
        directory = new File(context.getFilesDir(), "books");
    }

    static final class Book {
        final String id;
        final String title;
        final String format;
        final String language;
        final int length;
        final int position;

        Book(String id, String title, String format, String language, int length, int position) {
            this.id = id;
            this.title = title;
            this.format = format;
            this.language = language;
            this.length = length;
            this.position = position;
        }
    }

    Book add(BookParser.ParsedBook parsed, String format) throws IOException {
        synchronized (LOCK) {
            JSONArray index = index();
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("Не удалось создать папку для книг.");
            }
            String id = UUID.randomUUID().toString();
            String language = guessLanguage(parsed.text);
            File file = textFile(id);
            boolean indexed = false;
            try {
                try (FileOutputStream output = new FileOutputStream(file)) {
                    output.write(parsed.text.getBytes(StandardCharsets.UTF_8));
                    output.getFD().sync();
                }
                JSONObject entry = new JSONObject();
                entry.put("id", id);
                entry.put("title", parsed.title);
                entry.put("format", format);
                entry.put("length", parsed.text.length());
                entry.put("language", language);
                index.put(entry);
                if (!preferences.edit().putString(INDEX_KEY, index.toString()).commit()) {
                    throw new IOException("Не удалось сохранить книгу в библиотеке.");
                }
                indexed = true;
                return asBook(entry);
            } catch (JSONException exception) {
                throw new IOException("Не удалось сохранить книгу в библиотеке.", exception);
            } finally {
                if (!indexed) {
                    file.delete();
                }
            }
        }
    }

    List<Book> all() {
        synchronized (LOCK) {
            JSONArray index = index();
            List<Book> books = new ArrayList<>(index.length());
            for (int i = index.length() - 1; i >= 0; i--) {
                JSONObject entry = index.optJSONObject(i);
                if (entry != null) {
                    books.add(asBook(entry));
                }
            }
            return books;
        }
    }

    Book get(String id) {
        if (id == null) {
            return null;
        }
        synchronized (LOCK) {
            JSONObject entry = find(index(), id);
            return entry == null ? null : asBook(entry);
        }
    }

    boolean remove(String id) {
        synchronized (LOCK) {
            JSONArray index = index();
            for (int i = 0; i < index.length(); i++) {
                JSONObject entry = index.optJSONObject(i);
                if (entry == null || !id.equals(entry.optString("id"))) {
                    continue;
                }
                index.remove(i);
                boolean saved = preferences.edit().putString(INDEX_KEY, index.toString())
                        .remove("position." + id).remove("language." + id).commit();
                if (saved) {
                    textFile(id).delete();
                }
                return saved;
            }
            return false;
        }
    }

    String readText(String id) throws IOException {
        return new String(Files.readAllBytes(textFile(id).toPath()), StandardCharsets.UTF_8);
    }

    void savePosition(String id, int position) {
        synchronized (LOCK) {
            JSONObject entry = find(index(), id);
            if (entry != null) {
                int bounded = Math.max(0, Math.min(position, entry.optInt("length")));
                preferences.edit().putInt("position." + id, bounded).apply();
            }
        }
    }

    void saveLanguage(String id, String language) {
        synchronized (LOCK) {
            if (find(index(), id) != null) {
                preferences.edit().putString("language." + id, "en".equals(language) ? "en" : "ru").apply();
            }
        }
    }

    float speed() {
        return preferences.getFloat("speed", 1f);
    }

    void saveSpeed(float speed) {
        if (Float.isFinite(speed)) {
            preferences.edit().putFloat("speed", Math.max(0.5f, Math.min(speed, 2f))).apply();
        }
    }

    String selected() {
        return preferences.getString("selected", null);
    }

    void select(String id) {
        preferences.edit().putString("selected", id).apply();
    }

    private File textFile(String id) {
        if (id == null || !id.matches("[0-9a-f-]{36}")) {
            throw new IllegalArgumentException("Некорректный идентификатор книги.");
        }
        return new File(directory, id + ".txt");
    }

    private JSONArray index() {
        try {
            return new JSONArray(preferences.getString(INDEX_KEY, "[]"));
        } catch (JSONException exception) {
            throw new IllegalStateException("Повреждён каталог книг.", exception);
        }
    }

    private static JSONObject find(JSONArray index, String id) {
        for (int i = 0; i < index.length(); i++) {
            JSONObject entry = index.optJSONObject(i);
            if (entry != null && entry.optString("id").equals(id)) {
                return entry;
            }
        }
        return null;
    }

    private Book asBook(JSONObject entry) {
        String id = entry.optString("id");
        return new Book(id, entry.optString("title"), entry.optString("format"),
                preferences.getString("language." + id, entry.optString("language", "ru")),
                entry.optInt("length"), preferences.getInt("position." + id, 0));
    }

    private static String guessLanguage(String text) {
        int latin = 0;
        int cyrillic = 0;
        int limit = Math.min(text.length(), 100_000);
        for (int i = 0; i < limit; i++) {
            char ch = text.charAt(i);
            if (!Character.isLetter(ch)) {
                continue;
            }
            Character.UnicodeScript script = Character.UnicodeScript.of(ch);
            if (script == Character.UnicodeScript.CYRILLIC) {
                cyrillic++;
            } else if (script == Character.UnicodeScript.LATIN) {
                latin++;
            }
        }
        return cyrillic >= latin ? "ru" : "en";
    }
}
