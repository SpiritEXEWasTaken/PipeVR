package org.schabi.newpipe.views;

import android.content.Intent;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;

/** PipeVR: signs in to Floatplane on floatplane.com; its cookies (sails.sid) are kept. */
public class FloatplaneLoginWebViewActivity extends BaseLoginWebViewActivity {
    @Override
    protected String getLoginUrl() {
        return "https://www.www.floatplane.com/login";
    }

    @Override
    protected String getSuccessCookieIndicator() {
        return "sails.sid=";
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
