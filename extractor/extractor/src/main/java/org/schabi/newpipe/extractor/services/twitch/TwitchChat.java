package org.schabi.newpipe.extractor.services.twitch;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * PipeVR: Twitch chat over Twitch's IRC WebSocket (irc-ws.chat.twitch.tv). Anonymous it only
 * reads; with the signed-in account's token it can also send.
 */
public final class TwitchChat extends WebSocketClient {
    /** One chat line. */
    public static final class Message {
        public final String author;
        public final String text;
        public final int color;

        Message(final String author, final String text, final int color) {
            this.author = author;
            this.text = text;
            this.color = color;
        }
    }

    @Nullable
    private static volatile TwitchChat current;

    private final String channel;
    @Nullable
    private final String token;
    @Nullable
    private final String login;
    private final ConcurrentLinkedQueue<Message> incoming = new ConcurrentLinkedQueue<>();
    private int drained = 0;
    private final Map<String, String> room = new HashMap<>();
    private String lastRoomNote = "";

    /** PipeVR: say in the chat when it is restricted (subscriber-only, slow mode, ...). */
    private void roomState(final Map<String, String> tags) {
        room.putAll(tags);
        final java.util.List<String> parts = new java.util.ArrayList<>();
        if ("1".equals(room.get("subs-only"))) {
            parts.add("subscriber-only chat");
        }
        if ("1".equals(room.get("emote-only"))) {
            parts.add("emote-only chat");
        }
        final String followers = room.get("followers-only");
        if (followers != null && !followers.equals("-1")) {
            parts.add(followers.equals("0") ? "followers-only chat"
                    : "followers-only chat (followed " + followers + " min)");
        }
        final String slow = room.get("slow");
        if (slow != null && !slow.equals("0")) {
            parts.add("slow mode " + slow + " s");
        }
        final String note = String.join(" · ", parts);
        if (!note.equals(lastRoomNote)) {
            lastRoomNote = note;
            if (!note.isEmpty()) {
                final String text = Character.toUpperCase(note.charAt(0)) + note.substring(1)
                        + ". The chat may be quiet.";
                incoming.add(new Message("Twitch", text, 0xFF9E9E9E));
            }
        }
    }
    private long lastCountLog = System.currentTimeMillis();

    private TwitchChat(final String channel, @Nullable final String token,
                       @Nullable final String login) throws Exception {
        super(new URI("wss://irc-ws.chat.twitch.tv:443"));
        this.channel = channel.toLowerCase();
        this.token = token;
        this.login = login;
    }

    /** Joins a channel's chat (closing the previous one). */
    @Nonnull
    public static synchronized TwitchChat join(@Nonnull final String channel) throws Exception {
        final TwitchChat old = current;
        if (old != null) {
            old.close();
        }
        // PipeVR: chat is read anonymously (no account needed, nothing to go wrong); sending
        // uses its own signed-in connection (see sendMessage)
        final TwitchChat chat = new TwitchChat(channel, null, null);
        TwitchApi.log("chat join #" + channel);
        chat.connect();
        current = chat;
        return chat;
    }

    /** The chat currently joined, if any (used to send messages). */
    @Nullable
    public static TwitchChat current() {
        return current;
    }

    public String getChannel() {
        return channel;
    }

    public boolean canSend() {
        return !authRefused && TwitchApi.getAuthToken() != null && TwitchApi.getLogin() != null;
    }

    /** The signed-in connection used only for sending (opened on the first message). */
    @Nullable
    private static TwitchChat sender;

    private static synchronized TwitchChat senderFor(final String channel) throws Exception {
        TwitchChat s = sender;
        if (s == null || !s.channel.equals(channel) || s.isClosed() || s.isClosing()) {
            if (s != null) {
                s.close();
            }
            s = new TwitchChat(channel, TwitchApi.getAuthToken(), TwitchApi.getLogin());
            s.connectBlocking(10, java.util.concurrent.TimeUnit.SECONDS);
            sender = s;
        }
        return s;
    }

    /** Sends a message as the signed-in account. */
    public boolean sendMessage(@Nonnull final String text) {
        if (!canSend()) {
            return false;
        }
        try {
            final TwitchChat s = senderFor(channel);
            if (!s.isOpen()) {
                TwitchApi.log("chat send: connection not open");
                return false;
            }
            s.send("PRIVMSG #" + channel + " :" + text.replace('\n', ' '));
        } catch (final Exception e) {
            TwitchApi.log("chat send failed: " + e);
            return false;
        }
        // Twitch doesn't echo our own messages: show it right away
        incoming.add(new Message(TwitchApi.getDisplayName() != null
                ? TwitchApi.getDisplayName() : login, text, 0xFFBF94FF));
        return true;
    }

    /** Messages received since the last call. */
    @Nonnull
    public java.util.List<Message> drain() {
        final java.util.List<Message> out = new java.util.ArrayList<>();
        Message m;
        while ((m = incoming.poll()) != null) {
            out.add(m);
        }
        drained += out.size();
        final long now = System.currentTimeMillis();
        if (now - lastCountLog > 10_000) {
            TwitchApi.log("chat #" + channel + ": " + drained + " messages in the last "
                    + ((now - lastCountLog) / 1000) + " s, open=" + isOpen());
            drained = 0;
            lastCountLog = now;
        }
        return out;
    }

    @Override
    public void onOpen(final ServerHandshake handshake) {
        TwitchApi.log("chat #" + channel + " open" + (token != null ? " (sender)" : ""));
        send("CAP REQ :twitch.tv/tags twitch.tv/commands");
        if (token != null && login != null) {
            send("PASS oauth:" + token);
            send("NICK " + login.toLowerCase());
        } else {
            send("PASS SCHMOOPIIE");
            send("NICK justinfan" + ThreadLocalRandom.current().nextInt(10000, 99999));
        }
        send("JOIN #" + channel);
    }

    /** Set when Twitch refused the account for chat; later joins read anonymously. */
    private static volatile boolean authRefused = false;

    @Override
    public void onMessage(final String raw) {
        for (final String line : raw.split("\r\n")) {
            if (line.contains(" NOTICE ") && (line.contains("Login authentication failed")
                    || line.contains("Improperly formatted auth"))) {
                // only the sending connection signs in: sending isn't possible
                authRefused = true;
                TwitchApi.log("chat sign-in refused: " + line);
                return;
            }
            if (line.contains(" NOTICE ") || line.contains(" RECONNECT")
                    || line.contains(" 001 ") || line.contains(" JOIN #")
                    || line.contains(" ROOMSTATE ") || line.contains(" USERSTATE ")) {
                TwitchApi.log("chat #" + channel + ": " + (line.length() > 160
                        ? line.substring(0, 300) : line));
            }
            if (line.contains(" RECONNECT")) {
                close();  // Twitch asks to reconnect: the next read re-joins
                return;
            }
            if (line.startsWith("PING")) {
                send("PONG" + line.substring(4));
                continue;
            }
            parse(line);
        }
    }

    private void parse(final String line) {
        String rest = line;
        final Map<String, String> tags = new HashMap<>();
        if (rest.startsWith("@")) {
            final int space = rest.indexOf(' ');
            if (space < 0) {
                return;
            }
            for (final String tag : rest.substring(1, space).split(";")) {
                final int eq = tag.indexOf('=');
                if (eq > 0) {
                    tags.put(tag.substring(0, eq), tag.substring(eq + 1));
                }
            }
            rest = rest.substring(space + 1);
        }
        if (rest.contains(" ROOMSTATE #")) {
            roomState(tags);
            return;
        }
        final int cmd = rest.indexOf(" PRIVMSG #");
        if (cmd < 0) {
            return;
        }
        final int textStart = rest.indexOf(" :", cmd);
        if (textStart < 0) {
            return;
        }
        String author = tags.get("display-name");
        if (author == null || author.isEmpty()) {
            final int bang = rest.indexOf('!');
            author = bang > 1 ? rest.substring(1, bang) : "?";
        }
        String text = rest.substring(textStart + 2);
        if (text.startsWith("\u0001ACTION ")) {
            text = text.substring(8).replace("\u0001", "");
        }
        int color = 0xFFFFFFFF;
        final String hex = tags.get("color");
        if (hex != null && hex.length() == 7 && hex.startsWith("#")) {
            try {
                color = 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
            } catch (final NumberFormatException ignored) {
                // keep white
            }
        }
        incoming.add(new Message(author, text, color));
        while (incoming.size() > 500) {
            incoming.poll();
        }
    }

    @Override
    public void onClose(final int code, final String reason, final boolean remote) {
        TwitchApi.log("chat #" + channel + " closed " + code + " " + reason
                + (token != null ? " (sender)" : ""));
    }

    @Override
    public void onError(final Exception ex) {
        TwitchApi.log("chat #" + channel + " error " + ex);
    }
}
