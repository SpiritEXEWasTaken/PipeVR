package org.schabi.newpipe.views;

import android.content.Intent;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** PipeVR: signs in to Kick on kick.com; the session_token / XSRF-TOKEN cookies are kept. */
public class KickLoginWebViewActivity extends BaseLoginWebViewActivity {
    @Override
    protected String getLoginUrl() {
        return "https://kick.com/";
    }

    @Override
    protected String getSuccessCookieIndicator() {
        return "session_token=";
    }

    @Override
    protected void configureWebView() {
        final WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
    }

    @Override
    protected WebViewClient createWebViewClient() {
        return new StandardWebViewClient();
    }

    @Override
    protected void handleSuccessfulLogin(final String cookies) {
        String session = null;
        String xsrf = null;
        for (final String part : cookies.split(";")) {
            final String p = part.trim();
            if (p.startsWith("session_token=")) {
                session = URLDecoder.decode(p.substring(14), StandardCharsets.UTF_8);
            } else if (p.startsWith("XSRF-TOKEN=")) {
                xsrf = URLDecoder.decode(p.substring(11), StandardCharsets.UTF_8);
            }
        }
        if (session == null || session.isEmpty()) {
            return;
        }
        final Intent intent = new Intent();
        intent.putExtra("session", session);
        intent.putExtra("xsrf", xsrf);
        finishWithResult(intent);
    }
}
