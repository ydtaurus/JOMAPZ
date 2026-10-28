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
        public void resolve(final String text, final String id) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final String res = resolveText(text);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                getBridge().getWebView().evaluateJavascript("window.__resolved&&window.__resolved(" + JSONObject.quote(id) + "," + JSONObject.quote(res) + ")", null);
                            } catch (Exception e) {
                            }
                        }
                    });
                }
            }).start();
        }

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
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 4242);
            }
        } catch (Exception e) {
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShare(intent);
    }

    static String resolveText(final String text) {
        String url = "";
                String dbg = "";
                String lat = "";
                String lng = "";
                try {
                    Matcher m = Pattern.compile("https?://\\S+").matcher(text);
                    if (m.find()) url = m.group();
                    if (url.length() > 0) {
                        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                        c.setInstanceFollowRedirects(true);
                        c.setConnectTimeout(8000);
                        c.setReadTimeout(8000);
                        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
                        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
                        c.setRequestProperty("Cookie", "CONSENT=YES+cb; SOCS=CAI");
                        int code = c.getResponseCode();
                        url = c.getURL().toString();
                        dbg = "http " + code;
                        StringBuilder sb = new StringBuilder();
                        try {
                            java.io.InputStream is = c.getInputStream();
                            byte[] buf = new byte[16384];
                            int n;
                            while ((n = is.read(buf)) > 0 && sb.length() < 700000) {
                                sb.append(new String(buf, 0, n, "UTF-8"));
                            }
                            is.close();
                        } catch (Exception e) {
                        }
                        c.disconnect();
                        String body = sb.toString();
                        String[] pats = new String[] {
                            "!3d(-?\\d{1,2}\\.\\d+)!4d(-?\\d{2,3}\\.\\d+)",
                            "@(-?\\d{1,2}\\.\\d+),(-?\\d{2,3}\\.\\d+)",
                            "center=(-?\\d{1,2}\\.\\d+)(?:%2C|,)(-?\\d{2,3}\\.\\d+)",
                            "\\[null,null,(-?\\d{1,2}\\.\\d+),(-?\\d{2,3}\\.\\d+)\\]"
                        };
                        String hay = url + "\n" + body;
                        for (int i = 0; i < pats.length && lat.length() == 0; i++) {
                            Matcher mm = Pattern.compile(pats[i]).matcher(hay);
                            if (mm.find()) {
                                lat = mm.group(1);
                                lng = mm.group(2);
                                dbg = dbg + " p" + i;
                            }
                        }
                        dbg = dbg + " len" + body.length();
                    }
                } catch (Exception e) {
                    dbg = "err " + e.getClass().getSimpleName();
                }
                try {
                    JSONObject o = new JSONObject();
                    o.put("text", text);
                    o.put("url", url);
                    o.put("lat", lat);
                    o.put("lng", lng);
                    o.put("dbg", dbg);
                    return o.toString();
                } catch (Exception e) {
                    return "";
                }
    }

    private void handleShare(final Intent in) {
        if (in == null || !Intent.ACTION_SEND.equals(in.getAction())) return;
        final String text = in.getStringExtra(Intent.EXTRA_TEXT);
        in.setAction(Intent.ACTION_MAIN);
        if (text == null || text.length() == 0) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String res = resolveText(text);
                if (res.length() == 0) return;
                pending = res;
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
