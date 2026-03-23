package com.joragaka.ptdialogue.client;

import com.joragaka.ptdialogue.HistorySyncPayload;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Client-side dialogue history.
 * Data comes exclusively from the server via HistorySyncPayload.
 * No file I/O on the client — history lives in <world>/dialoguehistory/ on the server.
 */
public class DialogueHistory {

    public static class Entry {
        private final String icon;
        private final String name;
        private final int nameColor;
        private final Text message;
        private final long timestamp;

        public Entry(String icon, String name, int nameColor, Text message, long timestamp) {
            this.icon = icon;
            this.name = name;
            this.nameColor = nameColor;
            this.message = message;
            this.timestamp = timestamp;
        }

        public String getIcon()      { return icon; }
        public String getName()      { return name; }
        public int    getNameColor() { return nameColor; }
        public Text   getMessage()   { return message; }
        public long   getTimestamp() { return timestamp; }
    }

    private static final List<Entry> history = new ArrayList<>();
    // Simple monotonic version counter incremented whenever history changes.
    // DialogueHistoryScreen will poll this to avoid expensive rebakes every frame.
    private static volatile long version = 0L;

    // ── Public API ────────────────────────────────────────────────────────────

    /** Full sync from server on join — replaces the entire local history. */
    public static void loadFromServer(List<HistorySyncPayload.Entry> entries) {
        history.clear();
        for (var e : entries) {
            // messages on disk are stored as JSON component arrays/objects. Parse them to Text.
            Text parsed = com.joragaka.ptdialogue.client.DialoguePacketHandler.parseJsonToText(e.message());
            history.add(new Entry(e.icon(), e.name(), e.color(), parsed, e.timestamp()));
        }
        version++;
        System.out.println("[ptdialogue] Loaded " + history.size() + " history entries from server");
    }

    /** Incremental update — append a single new entry received in real-time. */
    public static void appendFromServer(String icon, String name, int color,
                                        Text message, long timestamp) {
        history.add(new Entry(icon, name, color, message, timestamp));
        version++;
    }

    /** Called on disconnect — clear local cache (server already has it saved). */
    public static void onDisconnect() {
        history.clear();
        version++;
    }

    /** Return an unmodifiable view of history (oldest first). */
    public static List<Entry> getEntries() {
        return Collections.unmodifiableList(history);
    }

    /** Return a monotonically increasing version counter to detect changes without heavy operations. */
    public static long getVersion() { return version; }
}
