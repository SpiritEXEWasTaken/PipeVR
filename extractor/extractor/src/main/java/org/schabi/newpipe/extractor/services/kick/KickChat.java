package org.schabi.newpipe.extractor.services.kick;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * PipeVR: Kick chat. Reading uses the Pusher WebSocket the Kick website listens to (public app
 * key); sending posts the message with the signed-in session.
 */
public final class KickChat extends WebSocketClient {
    private static final String PUSHER = "wss://ws-us2.pusher.com/app/32cbd69e4b950bf97679"
            + "?protocol=7&client=js&version=8.4.0&flash=false";

    /** One chat line. */
    public static final class Message {
        public final String author;
        public final String text;

        Message(final String author, final String text) {
            this.author = author;
            this.text = text;
        }
    }

    @Nullable
    private static volatile KickChat current;

    private final long chatroomId;
    private final ConcurrentLinkedQueue<Message> incoming = new ConcurrentLinkedQueue<>();

    private KickChat(final long chatroomId) throws Exception {
        super(new URI(PUSHER));
        this.chatroomId = chatroomId;
    }

    @Nonnull
    public static synchronized KickChat join(final long chatroomId) throws Exception {
        final KickChat old = current;
        if (old != null) {
            old.close();
        }
        final KickChat chat = new KickChat(chatroomId);
        chat.connect();
        current = chat;
        return chat;
    }

    @Nullable
    public static KickChat current() {
        return current;
    }

    public long getChatroomId() {
        return chatroomId;
    }

    /** Sends a message as the signed-in account (blocking). */
    public boolean sendMessage(@Nonnull final String text) {
        if (!KickApi.isSignedIn()) {
            return false;
        }
        try {
            final JsonObject body = new JsonObject();
            body.put("content", text);
            body.put("type", "message");
            final int code = KickApi.send("POST", "/api/v2/messages/send/" + chatroomId, body);
            return code >= 200 && code < 300;
        } catch (final Exception e) {
            return false;
        }
    }

    @Nonnull
    public List<Message> drain() {
        final List<Message> out = new ArrayList<>();
        Message m;
        while ((m = incoming.poll()) != null) {
            out.add(m);
        }
        return out;
    }

    @Override
    public void onOpen(final ServerHandshake handshake) {
        send("{\"event\":\"pusher:subscribe\",\"data\":{\"auth\":\"\",\"channel\":\"chatrooms."
                + chatroomId + ".v2\"}}");
    }

    @Override
    public void onMessage(final String raw) {
        try {
            final JsonObject event = JsonParser.object().from(raw);
            final String name = event.getString("event", "");
            if ("pusher:ping".equals(name)) {
                send("{\"event\":\"pusher:pong\",\"data\":{}}");
                return;
            }
            if (!name.endsWith("ChatMessageEvent")) {
                return;
            }
            final JsonObject data = JsonParser.object().from(event.getString("data", "{}"));
            final String author = data.getObject("sender").getString("username", "?");
            // emotes come as [emote:id:name]: show their names
            final String text = data.getString("content", "")
                    .replaceAll("\\[emote:\\d+:([^\\]]+)\\]", "$1");
            incoming.add(new Message(author, text));
            while (incoming.size() > 500) {
                incoming.poll();
            }
        } catch (final Exception ignored) {
            // not a chat message
        }
    }

    @Override
    public void onClose(final int code, final String reason, final boolean remote) {
    }

    @Override
    public void onError(final Exception ex) {
    }
}
