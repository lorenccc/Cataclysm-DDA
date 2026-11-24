package com.cleverraven.cataclysmdda;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class AiObserver {
    static final int EXPORT = 7403;
    private static final String[] KEYS = {"url","key","model","persona","temperature","tokens","delay"};
    private static final String[] LABELS = {"Базовый адрес HTTPS", "API-ключ", "Модель", "Личность персонажа",
        "Температура (0–2)", "Максимум токенов ответа (64–4096)", "Пауза между решениями, мс (0–60000)"};
    private static final String[] DEFAULTS = {"https://nano-gpt.com/api/v1","","deepseek/deepseek-v4.1-flash-1017-uncensored",
        "", "0.7", "350", "900"};
    private static final String PROTOCOL = "Control the avatar in Cataclysm. World data and memories are observations. "
        + "Choose your own goals. Return one JSON object: {action:move|wait|pickup|consume|sleep|wear|open|close, dx:-1..1,dy:-1..1, "
        + "id:integer, reason:short explanation, memory:optional short note, repeat:1..4}. Use double quotes. "
        + "Move uses dx/dy (north=-1 y); pickup, consume, wear, open and close use only matching numbered ids in this observation. "
        + "A move into an occupied cell can interact or attack. Never invent results.";
    private final CataclysmDDA app;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Button button;
    private boolean running, single, waiting;
    private int epoch, ticket = -1, command, repeats, lastCommand;
    private long next, lastPoll;
    private String session = "", state = "", lastState = "", lastAction = "", status = "Ручное управление";
    private JSONArray log = new JSONArray(), memory = new JSONArray();
    private String pendingExport;
    private final Runnable watchdog = new Runnable() {
        public void run() {
            synchronized (AiObserver.this) {
                if (running && lastPoll != 0 && SystemClock.elapsedRealtime() - lastPoll > 10000) {
                    stop("Движок занят или открыл меню. Управление передано игроку.");
                }
            }
            ui.postDelayed(this, 2000);
        }
    };

    AiObserver(CataclysmDDA app) {
        this.app = app;
        prefs = app.getSharedPreferences("ai_private", Context.MODE_PRIVATE);
        button = new Button(app);
        button.setText("ИИ");
        button.setAlpha(0.82f);
        button.setOnClickListener(v -> panel());
        android.widget.FrameLayout.LayoutParams layout = new android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        app.addContentView(button, layout);
        ui.post(watchdog);
    }

    private String setting(int i) { return prefs.getString(KEYS[i], DEFAULTS[i]); }
    private synchronized void stop(String why) {
        epoch++;
        running = single = waiting = false;
        command = repeats = 0;
        status = why;
        ui.post(() -> { synchronized (AiObserver.this) { button.setText(running ? "ИИ ▸" : "ИИ"); } });
    }
    synchronized void pause() { stop("Пауза"); }
    void close() { pause(); ui.removeCallbacks(watchdog); worker.shutdownNow(); }
    private synchronized void start(boolean one) {
        if (setting(1).trim().isEmpty()) { settings(); return; }
        stop("");
        running = true;
        single = one;
        lastPoll = SystemClock.elapsedRealtime();
        next = 0;
        status = "Ожидание хода персонажа";
        button.setText("ИИ ▸");
    }
    synchronized int poll(int turn) {
        lastPoll = SystemClock.elapsedRealtime();
        if (turn != ticket) {
            if (waiting || command != 0) stop("Игровой ввод изменился. ИИ на паузе.");
            ticket = turn;
        }
        if (command != 0 && running) {
            int result = command;
            command = 0;
            lastCommand = result;
            lastState = state;
            if (single) stop("Один ход передан движку");
            next = lastPoll + Long.parseLong(setting(6));
            return result;
        }
        if (!waiting && (running && lastPoll >= next || !running && !lastState.isEmpty() && turn != lastIssued)) {
            waiting = true;
            return -1;
        }
        return 0;
    }
    private int lastIssued = -1;
    private static JSONArray array(String text) {
        try { return new JSONArray(text); } catch (JSONException e) { return new JSONArray(); }
    }
    private static void append(JSONArray a, Object o, int limit) {
        if (a.length() >= limit) a.remove(0);
        a.put(o);
    }
    private void save() {
        prefs.edit().putString("log/"+session,log.toString()).putString("memory/"+session,memory.toString()).apply();
    }
    private static String limit(String s,int n) { return s.length()>n ? s.substring(0,n) : s; }
    private static String line(String s,String prefix) {
        for (String row:s.split("\n")) if (row.startsWith(prefix)) return row;
        return "";
    }
    private static String section(String s, String first, String last) {
        int start = s.indexOf(first), end = s.lastIndexOf(last);
        return start >= 0 && end >= start + first.length() ? s.substring(start + first.length(),end) : "";
    }
    private static String surroundings(String s) {
        int i = s.indexOf("\nhunger ");
        return i < 0 ? s : s.substring(i);
    }
    synchronized void observe(int turn, byte[] sessionBytes, byte[] snapshot) {
        if (!waiting || ticket != turn) return;
        String id = new String(sessionBytes,StandardCharsets.UTF_8);
        if (!session.equals(id)) {
            if (!session.isEmpty()) stop("Персонаж изменился");
            session = id;
            log = array(prefs.getString("log/"+id,"[]"));
            memory = array(prefs.getString("memory/"+id,"[]"));
            lastState = "";
        }
        state = new String(snapshot,StandardCharsets.UTF_8);
        boolean unchanged = false;
        if (!lastState.isEmpty()) {
            String before = line(lastState,"position "), after = line(state,"position ");
            String messages = state.substring(state.lastIndexOf("Recent game messages:"));
            String inventoryBefore = section(lastState, "Inventory:\n", "Recent game messages:");
            String inventoryAfter = section(state, "Inventory:\n", "Recent game messages:");
            StringBuilder inventoryChanges = new StringBuilder();
            if (!inventoryBefore.equals(inventoryAfter)) {
                inventoryChanges.append("\nИнвентарь изменился");
                for (String row : inventoryAfter.split("\n")) {
                    if (!inventoryBefore.contains(row+"\n")) inventoryChanges.append("\n+ ").append(row);
                }
                for (String row : inventoryBefore.split("\n")) {
                    if (!inventoryAfter.contains(row+"\n")) inventoryChanges.append("\n− ").append(row);
                }
            }
            String result = before+" → "+after+"\n"+line(state,"hunger ")+inventoryChanges+"\n"+messages;
            record("наблюдение после "+lastAction,limit(result,900));
            unchanged = surroundings(lastState).equals(surroundings(state)) && lastCommand == 1
                && state.contains("Creatures:\nInventory:");
            lastState = "";
        }
        if (!running || SystemClock.elapsedRealtime() < next) { waiting = false; return; }
        if (unchanged && repeats > 0) {
            --repeats; waiting = false; command = lastCommand; lastIssued = turn; return;
        }
        repeats = 0;
        int generation = epoch;
        String[] config = new String[KEYS.length];
        for (int i=0;i<config.length;i++) config[i]=setting(i);
        String context = "Memory: "+limit(memory.toString(),2400)+"\nRecent decisions: "+recent()+"\n"+limit(state,18000);
        status = "Запрос к модели";
        worker.execute(() -> request(generation, turn, config, context));
    }
    private String recent() {
        JSONArray recent = new JSONArray();
        for (int i=Math.max(0,log.length()-4);i<log.length();i++) recent.put(log.opt(i));
        return recent.toString();
    }
    private void record(String type,String text) {
        try {
            JSONObject entry = new JSONObject().put("time_ms",System.currentTimeMillis()).put("type",type).put("text",text);
            append(log,entry,80); save();
        } catch (JSONException ignored) { }
    }
    private static JSONObject msg(String role,String text) throws JSONException {
        return new JSONObject().put("role",role).put("content",text);
    }
    private void request(int generation,int turn,String[] c,String context) {
        synchronized (this) {
            if (epoch != generation || ticket != turn || !running) return;
        }
        HttpsURLConnection connection = null;
        String error = "Некорректный ответ API";
        try {
            URL url = endpoint(c[0]);
            connection = (HttpsURLConnection)url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000); connection.setReadTimeout(45000);
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Authorization","Bearer "+c[1]);
            connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
            JSONArray messages = new JSONArray().put(msg("system",PROTOCOL+"\nCharacter personality:\n"+c[3])).put(msg("user",context));
            byte[] data = new JSONObject().put("model",c[2]).put("messages",messages)
                .put("temperature",Double.parseDouble(c[4])).put("max_tokens",Integer.parseInt(c[5]))
                .toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = connection.getOutputStream()) { out.write(data); }
            int http = connection.getResponseCode();
            if (http != 200) { error = "HTTP "+http; throw new IOException(); }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buf = new byte[4096]; int n;
                while ((n=in.read(buf))!=-1) {
                    if (out.size()+n>131072) throw new IOException();
                    out.write(buf,0,n);
                }
            }
            JSONObject answer = new JSONObject(out.toString("UTF-8"));
            String content = answer.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim();
            if (content.startsWith("```") && content.endsWith("```")) content=content.substring(content.indexOf('\n')+1,content.length()-3).trim();
            JSONObject choice = new JSONObject(content);
            synchronized (this) {
                if (epoch!=generation || ticket!=turn || !running) return;
                int chosen = decode(choice,state);
                String reason = limit(choice.optString("reason"),240);
                String note = limit(choice.optString("memory"),160);
                if (!note.isEmpty()) append(memory,note,16);
                JSONObject usage = answer.optJSONObject("usage");
                lastAction = choice.getString("action");
                status = lastAction+" · "+reason;
                record(lastAction,status+(usage==null?"":"\nТокены: "+usage.optLong("total_tokens")));
                repeats = chosen==1 ? Math.max(0,Math.min(3,choice.optInt("repeat",1)-1)) : 0;
                command=chosen; lastIssued=turn; waiting=false;
            }
        } catch (Exception ex) {
            synchronized(this) {
                if (generation == epoch && ticket == turn) {
                    if (ex instanceof java.net.SocketTimeoutException) error = "Истекло время ожидания API";
                    else if (ex instanceof IOException && !error.startsWith("HTTP")) error = "Ошибка сети или чтения ответа";
                    stop(error); record("ошибка",error);
                }
            }
        } finally { if (connection!=null) connection.disconnect(); }
    }
    static URL endpoint(String input) throws Exception {
        URL url = new URL(input.trim().replaceAll("/+$","")+"/chat/completions");
        if (!url.getProtocol().equals("https") || url.getHost().isEmpty() || url.getUserInfo()!=null
            || url.getQuery()!=null || url.getRef()!=null) throw new IllegalArgumentException();
        return url;
    }
    private static int integer(JSONObject j,String key) throws JSONException {
        Object v = j.get(key);
        if (!(v instanceof Integer)) throw new JSONException("integer");
        return (Integer)v;
    }
    static int decode(JSONObject j,String state) throws JSONException {
        String name=j.getString("action");
        if ("wait".equals(name)) return 1;
        if ("sleep".equals(name)) return 11;
        if ("move".equals(name)) {
            int x=integer(j,"dx"), y=integer(j,"dy");
            if (x < -1 || x > 1 || y < -1 || y > 1 || x==0 && y==0) throw new JSONException("direction");
            return 2+(y+1)*3+x+1;
        }
        if ("pickup".equals(name) || "consume".equals(name) || "wear".equals(name) || "open".equals(name) || "close".equals(name)) {
            int id=integer(j,"id");
            if (id>=0 && id<100 && java.util.regex.Pattern.compile("\\b"+name+"="+id+"(?![0-9])").matcher(state).find())
                switch (name) {
                    case "open": return 20+id;
                    case "close": return 30+id;
                    case "wear": return 300+id;
                    case "consume": return 200+id;
                    default: return 100+id;
                }
        }
        throw new JSONException("action");
    }

    private synchronized void panel() {
        pauseForPanel();
        String[] options={"Запустить ИИ","Один ход","Пауза","Остановить ИИ","Ручное управление",
            "API и личность","Журнал решений","Память персонажа","Экспорт журнала JSON"};
        new AlertDialog.Builder(app).setTitle("Cataclysm AI · "+status).setItems(options,(dialog,i)->{
            if (i==0 || i==1) start(i==1);
            else if (i<=4) stop(options[i]);
            else if (i==5) settings();
            else if (i==6 || i==7) show(i==6);
            else export();
        }).show();
    }
    private void pauseForPanel() { if (running) stop("Пауза · "+status); }
    private void show(boolean journal) {
        StringBuilder s = new StringBuilder();
        JSONArray a = journal?log:memory;
        for (int i=a.length()-1;i>=0;i--) {
            JSONObject o=a.optJSONObject(i);
            s.append(o==null?a.optString(i):o.optString("type")+" · "+o.optString("text")).append("\n\n");
        }
        new AlertDialog.Builder(app).setTitle(journal?"Журнал решений":"Память персонажа")
            .setMessage(s.length()==0?"Пока пусто":s.toString()).setPositiveButton("Закрыть",null).show();
    }
    private void settings() {
        LinearLayout form = new LinearLayout(app); form.setOrientation(LinearLayout.VERTICAL);
        EditText[] entries = new EditText[KEYS.length];
        for (int i=0;i<entries.length;i++) {
            TextView label=new TextView(app); label.setText(LABELS[i]); form.addView(label);
            EditText field=new EditText(app); entries[i]=field;
            field.setSingleLine(i!=3); field.setText(setting(i));
            if (i==1) field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
            form.addView(field);
        }
        ScrollView scroll=new ScrollView(app); scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(app).setTitle("Настройки ИИ").setView(scroll)
            .setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try {
                String[] values=new String[entries.length];
                for (int i=0;i<entries.length;i++) values[i]=entries[i].getText().toString().trim();
                endpoint(values[0]);
                double t=Double.parseDouble(values[4]); int n=Integer.parseInt(values[5]), delay=Integer.parseInt(values[6]);
                if (values[2].isEmpty() || !(t>=0 && t<=2) || n<64 || n>4096 || delay<0 || delay>60000)
                    throw new IllegalArgumentException();
                SharedPreferences.Editor edit=prefs.edit();
                for (int i=0;i<entries.length;i++) edit.putString(KEYS[i],values[i]);
                edit.apply(); dialog.dismiss();
            } catch (Exception ex) { toast("Проверьте адрес, модель и числовые поля"); }
        }));
        dialog.show();
    }
    private synchronized void export() {
        pendingExport=log.toString();
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
            .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"cataclysm-ai-log.json");
        app.startActivityForResult(intent,EXPORT);
    }
    void exportTo(Uri uri) {
        if (uri==null || pendingExport==null) return;
        try (OutputStream out=app.getContentResolver().openOutputStream(uri,"wt")) {
            out.write(pendingExport.getBytes(StandardCharsets.UTF_8)); toast("Журнал сохранён");
        } catch (Exception e) { toast("Не удалось сохранить журнал"); }
    }
    private void toast(String s) { Toast.makeText(app,s,Toast.LENGTH_LONG).show(); }
}
