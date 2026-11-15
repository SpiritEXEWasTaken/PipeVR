package org.schabi.newpipe.views;

import android.content.Intent;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;

/** PipeVR: signs in to Patreon on patreon.com; its cookies (session_id) are kept. */
public class PatreonLoginWebViewActivity extends BaseLoginWebViewActivity {
    @Override
    protected String getLoginUrl() {
        return "https://www.patreon.com/login";
    }

    @Override
    protected String getSuccessCookieIndicator() {
        return "session_id=";
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
        final Intent intent = new Intent();
        intent.putExtra("cookies", cookies);
        finishWithResult(intent);
    }
}
