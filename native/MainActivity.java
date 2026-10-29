package my.jomapz.app;

import android.content.Intent;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import com.getcapacitor.BridgeActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

public class MainActivity extends BridgeActivity {
    private android.media.MediaPlayer mp;
    private volatile int playGen = 0;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    private File ttsFile(String text, String lang) {
        File d = new File(getCacheDir(), "tts");
        d.mkdirs();
        String h = Integer.toHexString((lang + "|" + text).hashCode()) + "_" + text.length();
        return new File(d, h + ".mp3");
    }

    private boolean fetchTts(String text, String lang) {
        File f = ttsFile(text, lang);
        if (f.exists() && f.length() > 200) return true;
        HttpURLConnection c = null;
        try {
            String u = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl="
                    + URLEncoder.encode(lang, "UTF-8") + "&q=" + URLEncoder.encode(text, "UTF-8");
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
            c.setRequestProperty("Referer", "https://translate.google.com/");
            if (c.getResponseCode() != 200) return false;
            File tmp = new File(f.getPath() + ".tmp");
            InputStream is = c.getInputStream();
            FileOutputStream os = new FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            int n;
            long tot = 0;
            while ((n = is.read(buf)) > 0) {
                os.write(buf, 0, n);
                tot += n;
            }
            os.close();
            is.close();
            if (tot < 200) {
                tmp.delete();
                return false;
            }
            return tmp.renameTo(f);
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void notifySay(final int id, final boolean ok) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    getBridge().getWebView().evaluateJavascript("window.__say&&window.__say(" + id + "," + (ok ? "true" : "false") + ")", null);
                } catch (Exception e) {
                }
            }
        });
    }

    private void stopMp() {
        try {
            if (mp != null) {
                mp.setOnCompletionListener(null);
                mp.setOnErrorListener(null);
                mp.stop();
                mp.release();
            }
        } catch (Exception e) {
        }
        mp = null;
    }

    private void startMp(final File f, final int id, final int gen) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (gen != playGen) return;
                try {
                    stopMp();
                    mp = new android.media.MediaPlayer();
                    mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build());
                    mp.setDataSource(f.getPath());
                    mp.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
                        @Override
                        public void onCompletion(android.media.MediaPlayer m) {
                            notifySay(id, true);
                        }
                    });
                    mp.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
                        @Override
                        public boolean onError(android.media.MediaPlayer m, int what, int extra) {
                            f.delete();
                            notifySay(id, false);
                            return true;
                        }
                    });
                    mp.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
                        @Override
                        public void onPrepared(android.media.MediaPlayer m) {
                            try {
                                m.start();
                            } catch (Exception e) {
                                notifySay(id, false);
                            }
                        }
                    });
                    mp.prepareAsync();
                } catch (Exception e) {
                    notifySay(id, false);
                }
            }
        });
    }

    private static volatile String pending = "";
    private final android.media.AudioManager.OnAudioFocusChangeListener afl =
            new android.media.AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int change) {
                }
            };

    public class ShareBridge {
        @JavascriptInterface
        public void focus(final boolean on) {
            try {
                android.media.AudioManager am = (android.media.AudioManager) getSystemService(android.content.Context.AUDIO_SERVICE);
                if (am == null) return;
                if (on) {
                    am.requestAudioFocus(afl, android.media.AudioManager.STREAM_MUSIC,
                            android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
                } else {
                    am.abandonAudioFocus(afl);
                }
            } catch (Exception e) {
            }
        }

        @JavascriptInterface
        public boolean has(String text, String lang) {
            File f = ttsFile(text, lang);
            return f.exists() && f.length() > 200;
        }

        @JavascriptInterface
        public void prefetch(final String text, final String lang) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    fetchTts(text, lang);
                }
            });
        }

        @JavascriptInterface
        public void play(final String text, final String lang, final int id) {
            final int gen = ++playGen;
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    boolean ok = fetchTts(text, lang);
                    if (gen != playGen) return;
                    if (!ok) {
                        notifySay(id, false);
                        return;
                    }
                    startMp(ttsFile(text, lang), id, gen);
                }
            });
        }

        @JavascriptInterface
        public void stopPlay() {
            playGen++;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    stopMp();
                }
            });
        }

        @JavascriptInterface
        public void quit() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        finishAndRemoveTask();
                    } catch (Exception e) {
                        finish();
                    }
                    new android.os.Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            android.os.Process.killProcess(android.os.Process.myPid());
                        }
                    }, 500);
                }
            });
        }

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
            getBridge().getWebView().getSettings().setMediaPlaybackRequiresUserGesture(false);
        } catch (Exception e) {
        }
        try {
            getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    try {
                        getBridge().getWebView().evaluateJavascript(
                                "(function(){try{return window.__jzBack&&window.__jzBack()?1:0}catch(e){return 0}})()",
                                new android.webkit.ValueCallback<String>() {
                                    @Override
                                    public void onReceiveValue(String v) {
                                        if (!"1".equals(v)) moveTaskToBack(true);
                                    }
                                });
                    } catch (Exception e) {
                        moveTaskToBack(true);
                    }
                }
            });
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
