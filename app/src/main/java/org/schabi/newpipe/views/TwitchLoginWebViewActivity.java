package org.schabi.newpipe.views;

import android.content.Intent;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;

/** PipeVR: signs in to Twitch on twitch.tv; the "auth-token" cookie is the account token. */
public class TwitchLoginWebViewActivity extends BaseLoginWebViewActivity {
    @Override
    protected String getLoginUrl() {
        return "https://www.twitch.tv/login";
    }

    @Override
    protected String getSuccessCookieIndicator() {
        return "auth-token=";
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
        String token = null;
        for (final String part : cookies.split(";")) {
            final String p = part.trim();
            if (p.startsWith("auth-token=")) {
                token = p.substring("auth-token=".length());
            }
        }
        if (token == null || token.isEmpty()) {
            return;
        }
        final Intent intent = new Intent();
        intent.putExtra("token", token);
        finishWithResult(intent);
    }
}
