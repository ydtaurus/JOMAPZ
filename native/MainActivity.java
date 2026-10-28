package my.jomapz.app;

import android.content.Intent;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import com.getcapacitor.BridgeActivity;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

public class MainActivity extends BridgeActivity {
    private static volatile String pending = "";

    public class ShareBridge {
        @JavascriptInterface
        public String takeShare() {
            String p = pending;
            pending = "";
            return p;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getBridge().getWebView().addJavascriptInterface(new ShareBridge(), "JomapzNative");
        } catch (Exception e) {
        }
        handleShare(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShare(intent);
    }

    private void handleShare(final Intent in) {
        if (in == null || !Intent.ACTION_SEND.equals(in.getAction())) return;
        final String text = in.getStringExtra(Intent.EXTRA_TEXT);
        in.setAction(Intent.ACTION_MAIN);
        if (text == null || text.length() == 0) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String url = "";
                try {
                    Matcher m = Pattern.compile("https?://\\S+").matcher(text);
                    if (m.find()) url = m.group();
                    if (url.length() > 0) {
                        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                        c.setInstanceFollowRedirects(true);
                        c.setConnectTimeout(7000);
                        c.setReadTimeout(7000);
                        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
                        c.connect();
                        c.getResponseCode();
                        url = c.getURL().toString();
                        c.disconnect();
                    }
                } catch (Exception e) {
                }
                try {
                    JSONObject o = new JSONObject();
                    o.put("text", text);
                    o.put("url", url);
                    pending = o.toString();
                } catch (Exception e) {
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            getBridge().getWebView().evaluateJavascript("window.__sharePing&&window.__sharePing()", null);
                        } catch (Exception e) {
                        }
                    }
                });
            }
        }).start();
    }
}
