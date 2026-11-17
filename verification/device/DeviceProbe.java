package com.voxarium.remote.devicecheck;

import android.app.*;
import android.os.*;
import android.content.*;
import android.net.Uri;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.inputmethod.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Standalone instrumentation against the installed, unmodified, write-closed APK.
 * No Remote, credentials, report, production source or writable editor is injected. */
public final class DeviceProbe extends Instrumentation {
  final List<String> results = new ArrayList<>();
  ClassLoader cl;
  Activity activity;
  int failed;
  boolean localWritableIme;
  interface Work { void run() throws Exception; }
  void check(boolean b, String m) { if (!b) throw new AssertionError(m); }
  void main(Work w) throws Exception {
    final Throwable[] ex = {null};
    runOnMainSync(() -> { try { w.run(); } catch(Throwable t) { ex[0]=t; } });
    if (ex[0]!=null) throw new RuntimeException(ex[0]);
  }
  void test(String name, Work w) {
    try { w.run(); results.add("PASS "+name); }
    catch(Throwable t) { failed++; results.add("FAIL "+name+": "+t); android.util.Log.e("VS78",name,t); }
  }
  @Override public void onCreate(Bundle args) { super.onCreate(args); localWritableIme="true".equals(args.getString("localWritableIme")); start(); }
  @Override public void onStart() {
    try {
      cl=getTargetContext().getClassLoader();
      getUiAutomation();
      activity=startActivitySync(new Intent().setClassName("com.voxarium.remote","com.voxarium.remote.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
      test("installed BuildConfig write gate remains false", () -> check(!cl.loadClass("com.voxarium.remote.BuildConfig").getField("EDITOR_WRITES_ENABLED").getBoolean(null), "gate enabled"));
      test("installed backup and cleartext policy", () -> {
        check((getTargetContext().getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)==0,"backup enabled");
        check(!android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(),"cleartext allowed");
      });
      test("real Android Keystore encrypted vault and lost-key fail-closed", () -> {
        Class<?> vaultClass=cl.loadClass("com.voxarium.remote.data.security.CredentialStore");
        Object companion=vaultClass.getField("Companion").get(null);
        Object vault=companion.getClass().getMethod("open",Context.class).invoke(companion,getTargetContext());
        Class<?> credentialClass=cl.loadClass("com.voxarium.remote.data.security.Credential");
        Object cc=credentialClass.getField("Companion").get(null);
        String marker="VS78-SYNTHETIC-NOT-A-SERVER-KEY";
        Object credential=cc.getClass().getMethod("fromToken",String.class).invoke(cc,marker);
        String id=(String)vaultClass.getMethod("create",credentialClass).invoke(vault,credential);
        Object loaded=vaultClass.getMethod("get",String.class).invoke(vault,id);
        check(loaded!=null && !loaded.toString().contains(marker),"redaction");
        Class<?> f1=cl.loadClass("kotlin.jvm.functions.Function1");
        Object extract=Proxy.newProxyInstance(cl,new Class[]{f1},(o,m,a)->a[0]);
        check(marker.equals(credentialClass.getMethod("useToken",f1).invoke(loaded,extract)),"encrypted roundtrip");
        java.io.File file=new java.io.File(getTargetContext().getNoBackupFilesDir(),"credentials.v1");
        byte[] bytes=java.nio.file.Files.readAllBytes(file.toPath());
        check(bytes.length>32 && !new String(bytes,java.nio.charset.StandardCharsets.UTF_8).contains(marker),"plaintext on disk");
        java.security.KeyStore ks=java.security.KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        String alias="com.voxarium.remote.credentials.v1";
        check(ks.getKey(alias,null).getEncoded()==null,"exportable AES key");
        ks.deleteEntry(alias);
        try {vaultClass.getMethod("get",String.class).invoke(vault,id);throw new AssertionError("missing key accepted");}
        catch(InvocationTargetException expected){check(expected.getCause().getClass().getSimpleName().equals("SecureStoreException"),"unexpected failure");}
        check(!ks.containsAlias(alias),"silently regenerated missing key");
        check(file.delete(),"probe ciphertext cleanup");
      });
      test("native password IME / composing input / saved state / rotation clearing", () -> {
        String add=getTargetContext().getString(getTargetContext().getResources().getIdentifier("profile_add","string","com.voxarium.remote"));
        List<AccessibilityNodeInfo> nodes=Collections.emptyList();
        for(int retry=0;retry<70 && nodes.isEmpty();retry++) {
          AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
          if(root!=null) { AccessibilityNodeInfo found=findText(root,add); if(found!=null) nodes=Collections.singletonList(found); }
          if(nodes.isEmpty() && retry%5==4) scrollForward(root);
          if(nodes.isEmpty()) Thread.sleep(100);
        }
        if(nodes.isEmpty()) {
          AccessibilityNodeInfo debug=getUiAutomation().getRootInActiveWindow();
          results.add("INFO accessibility root="+debug+" wanted="+add);
        }
        check(!nodes.isEmpty(),"add profile accessibility action");
        AccessibilityNodeInfo node=nodes.get(0); while(node!=null && !node.isClickable()) node=node.getParent();
        check(node!=null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK),"click add profile");
        Thread.sleep(700);
        final EditText[] key={null}; main(()->key[0]=findEdit(activity.getWindow().getDecorView()));
        check(key[0]!=null,"native secret input");
        String marker="VS78-НЕСЕКРЕТ-😀";
        main(()->{
          EditText e=key[0];
          check(e.getTransformationMethod() instanceof PasswordTransformationMethod,"password masking");
          check(!e.isSaveEnabled() && !e.isSaveFromParentEnabled(),"hierarchy saving");
          check(e.getImportantForAutofill()==View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,"autofill");
          check((e.getInputType() & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)!=0,"suggestions");
          check((e.getImeOptions() & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)!=0,"learning");
          e.requestFocus();
          ((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(e,InputMethodManager.SHOW_IMPLICIT);
          InputConnection ic=e.onCreateInputConnection(new EditorInfo());
          check(ic.setComposingText(marker,1) && ic.finishComposingText(),"composing input");
          check(e.getText().toString().equals(marker),"Cyrillic/emoji input connection");
          Bundle saved=new Bundle(); callActivityOnSaveInstanceState(activity,saved);
          android.os.Parcel parcel=android.os.Parcel.obtain(); parcel.writeBundle(saved);
          byte[] raw=parcel.marshall(); parcel.recycle();
          check(!new String(raw,java.nio.charset.StandardCharsets.UTF_16LE).contains(marker),"secret in SavedState");
        });
        Thread.sleep(1000);
        main(()->check(activity.getWindow().getDecorView().getRootWindowInsets().isVisible(WindowInsets.Type.ime()),"real system IME did not appear"));
        scrollKeyVisible(key[0]);
        screenshot("password-ime");
        ActivityMonitor monitor=addMonitor("com.voxarium.remote.MainActivity",null,false);
        main(()->activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
        Activity rotated=waitForMonitorWithTimeout(monitor,7000); removeMonitor(monitor);
        check(rotated!=null && rotated!=activity,"configuration recreation"); activity=rotated;
        Thread.sleep(700);
        main(()->{
          check(key[0].getText().length()==0,"old secret not cleared on release");
          EditText replacement=findEdit(activity.getWindow().getDecorView());
          check(replacement!=null && replacement.getText().length()==0,"secret restored after rotation");
        });
        screenshot("password-rotation");
        // Close IME/form only. A second unconditional Back would finish the host Activity.
        for(int i=0;i<3;i++) {
          final boolean[] hasInput={false};main(()->hasInput[0]=findEdit(activity.getWindow().getDecorView())!=null);
          if(!hasInput[0])break;
          getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
          Thread.sleep(350);
        }
        check(!activity.isFinishing(),"host unexpectedly finished");
      });
      test("local asset bundle / native bridge / read-only no-write open / flush / dispose", () -> {
        Probe p=new Probe(true);
        try {
          Object ctx=cl.loadClass("com.voxarium.remote.data.room.EditorContext").getConstructor(long.class,long.class,String.class).newInstance(1L,1L,"device-only");
          String md="# Device probe\n\nExact **Markdown** — Русский 😀\n";
          p.submit("Prepare",ctx,1L,md);
          Object prepared=p.event("Prepared");
          String seed=(String)prepared.getClass().getMethod("getSeed").invoke(prepared);
          p.submit("Initialize",ctx,2L,md,seed,true);
          Object ready=p.event("Ready");
          Object snapshot=ready.getClass().getMethod("getSnapshot").invoke(ready);
          String baseline=(String)snapshot.getClass().getMethod("getMarkdown").invoke(snapshot);
          check(p.js("document.querySelector('.tiptap').contentEditable").equals("\"false\""),"read-only DOM");
          check(p.js("typeof window.voxariumBridge.postMessage").equals("\"function\""),"real message listener");
          check(p.js("document.getElementById('status').hidden && document.querySelector('.tiptap').innerText.includes('Device probe')").equals("true"),"ready DOM not displayed");
          p.visualReady();
          screenshot("readonly-webview");
          Thread.sleep(400);
          check(p.events.stream().noneMatch(e -> e.getClass().getSimpleName().equals("LocalUpdate") || e.getClass().getSimpleName().equals("Changed")),"write on open");
          p.submit("Flush",ctx,3L);
          Object flushed=p.event("Flushed");
          Object fs=flushed.getClass().getMethod("getSnapshot").invoke(flushed);
          check(baseline.equals(fs.getClass().getMethod("getMarkdown").invoke(fs)),"flush changes baseline");
          p.submit("Dispose",ctx,4L); p.event("Disposed");
          results.add("INFO WebView "+WebView.getCurrentWebViewPackage().versionName+"; baseline chars="+baseline.length());
        } finally { p.close(); }
      });
      if(localWritableIme) test("LOCAL-ONLY writable component: real WebView composing Cyrillic+emoji -> blur/typed Flush (not Remote durability)", () -> {
        Probe p=new Probe(true);
        try {
          Object ctx=cl.loadClass("com.voxarium.remote.data.room.EditorContext").getConstructor(long.class,long.class,String.class).newInstance(1L,1L,"local-ime-policy-only");
          String md="Local component baseline\n";
          p.submit("Prepare",ctx,1L,md);
          Object prepared=p.event("Prepared");
          String seed=(String)prepared.getClass().getMethod("getSeed").invoke(prepared);
          // Explicitly opted-in component policy exercise, not BuildConfig/coordinator override.
          // There is no Remote, transport, original document or persistence path in this Probe.
          p.submit("Initialize",ctx,2L,md,seed,false); p.event("Ready");
          check(p.js("document.querySelector('.tiptap').contentEditable").equals("\"true\""),"local DOM not writable");
          p.visualReady();
          main(()->{p.web.setFocusableInTouchMode(true);check(p.web.requestFocus(),"native WebView focus rejected");});
          check(p.js("(()=>{const e=document.querySelector('.tiptap');e.focus();const s=getSelection();s.selectAllChildren(e);s.collapseToEnd();return document.activeElement===e})()").equals("true"),"editable DOM focus rejected");
          Thread.sleep(350); // Chromium must acknowledge native+DOM focus before asking for IC.
          String composing="Привет😀";
          main(()->{
            ((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(p.web,InputMethodManager.SHOW_IMPLICIT);
          });
          Thread.sleep(350);
          main(()->{
            InputConnection ic=p.web.onCreateInputConnection(new EditorInfo());
            check(ic!=null,"WebView InputConnection unavailable after focus");
            check(ic.setComposingText(composing,1),"WebView InputConnection composition rejected");
            // Deliberately do not call finishComposingText: production flush must blur/commit it.
          });
          Thread.sleep(250);
          check(p.js("document.querySelector('.tiptap').innerText.includes('Привет😀')").equals("true"),"composed DOM text missing");
          p.submit("Flush",ctx,3L);
          Object flushed=p.event("Flushed");
          Object snapshot=flushed.getClass().getMethod("getSnapshot").invoke(flushed);
          String markdown=(String)snapshot.getClass().getMethod("getMarkdown").invoke(snapshot);
          check(markdown.contains(composing),"typed Flush lost composed Cyrillic/surrogate pair");
          check(p.js("document.querySelector('.tiptap').contentEditable").equals("\"false\""),"Flush did not freeze input");
          int updateIndex=-1,changedIndex=-1;
          for(int i=0;i<p.events.size();i++){String kind=p.events.get(i).getClass().getSimpleName();if(kind.equals("LocalUpdate") && updateIndex<0)updateIndex=i;if(kind.equals("Changed") && changedIndex<0)changedIndex=i;}
          check(updateIndex>=0 && changedIndex>updateIndex,"local event ordering");
          p.submit("Dispose",ctx,4L);p.event("Disposed");
          check(!cl.loadClass("com.voxarium.remote.BuildConfig").getField("EDITOR_WRITES_ENABLED").getBoolean(null),"application gate changed");
        } finally {p.close();}
      });
      test("actual WebSettings and denied request policy", () -> {
        Probe p=new Probe(true);
        try {
          main(() -> {
            WebSettings s=p.web.getSettings();
            check(s.getJavaScriptEnabled() && !s.getAllowFileAccess() && !s.getAllowContentAccess(),"file/content");
            check(!s.getAllowFileAccessFromFileURLs() && !s.getAllowUniversalAccessFromFileURLs(),"universal");
            check(s.getBlockNetworkLoads() && s.getBlockNetworkImage() && !s.getLoadsImagesAutomatically(),"network/images");
            check(!s.getDomStorageEnabled() && !s.getDatabaseEnabled(),"storage");
            check(!s.getJavaScriptCanOpenWindowsAutomatically() && !s.supportMultipleWindows(),"popups");
            check(s.getMixedContentMode()==WebSettings.MIXED_CONTENT_NEVER_ALLOW,"mixed content");
            for(String u: new String[]{"https://example.invalid/collect","file:///data/local/tmp/x","content://settings/system","https://appassets.androidplatform.net/assets/editor/../room.html"}) {
              WebResourceRequest req=new WebResourceRequest(){
                public Uri getUrl(){return Uri.parse(u);} public boolean isForMainFrame(){return false;}
                public boolean isRedirect(){return false;} public boolean hasGesture(){return false;}
                public String getMethod(){return "GET";} public Map<String,String> getRequestHeaders(){return Collections.emptyMap();}
              };
              check(p.web.getWebViewClient().shouldInterceptRequest(p.web,req).getStatusCode()==403,"not denied: "+u);
            }
          });
        } finally { p.close(); }
      });
      test("CSP blocks actual page fetch without external server", () -> {
        Probe p=new Probe(true);
        try {
          p.waitPage();
          p.js("window.probeFetch='pending';fetch('https://example.invalid/credential-probe').then(()=>window.probeFetch='ALLOWED').catch(()=>window.probeFetch='blocked');true");
          Thread.sleep(700);
          check(p.js("window.probeFetch").equals("\"blocked\""),"fetch not blocked");
        } finally { p.close(); }
      });
      test("malformed real bridge message fails closed once", () -> {
        Probe p=new Probe(true);
        try { p.waitPage(); p.js("window.voxariumBridge.postMessage('{}');true");
          check(p.failures.poll(5,TimeUnit.SECONDS).equals("INVALID_MESSAGE"),"wrong failure");
          check(p.js("true").equals("true"),"renderer unavailable");
          check(p.failures.poll(300,TimeUnit.MILLISECONDS)==null,"duplicate failure");
          check(p.events.isEmpty(),"synthetic event");
        } finally { p.close(); }
      });
      test("unsupported-feature path has no loaded page", () -> {
        Probe p=new Probe(false);
        try { check("UNSUPPORTED_WEBVIEW".equals(p.failures.poll(5,TimeUnit.SECONDS)),"unsupported reason");
          main(() -> check(p.web.getUrl()==null,"page loaded despite disabled feature"));
        } finally { p.close(); }
      });
      test("real renderer termination reports loss, never fake flush", () -> {
        Probe p=new Probe(true);
        try { p.waitPage(); main(() -> check(p.web.getWebViewRenderProcess()!=null && p.web.getWebViewRenderProcess().terminate(),"no separate renderer"));
          check("RENDERER_GONE".equals(p.failures.poll(10,TimeUnit.SECONDS)),"renderer loss reason");
          check(p.events.isEmpty(),"fake flushed/disposed");
        } finally { p.close(); }
      });
    } catch(Throwable t) { failed++; results.add("FATAL "+t); android.util.Log.e("VS78","fatal",t); }
    Bundle result=new Bundle(); result.putString("stream",String.join("\n",results)+"\nfailures="+failed+"\n");
    finish(failed==0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED,result);
  }
  EditText findEdit(View v) {
    if(v instanceof EditText) return (EditText)v;
    if(v instanceof ViewGroup) for(int i=0;i<((ViewGroup)v).getChildCount();i++) { EditText e=findEdit(((ViewGroup)v).getChildAt(i)); if(e!=null)return e; }
    return null;
  }
  AccessibilityNodeInfo findText(AccessibilityNodeInfo n,String text) {
    if(n==null)return null;
    if(text.contentEquals(n.getText()==null?"":n.getText()))return n;
    for(int i=0;i<n.getChildCount();i++) {AccessibilityNodeInfo found=findText(n.getChild(i),text);if(found!=null)return found;}
    return null;
  }
  boolean scrollForward(AccessibilityNodeInfo n) {
    if(n==null)return false;
    if(n.isScrollable() && n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))return true;
    for(int i=0;i<n.getChildCount();i++)if(scrollForward(n.getChild(i)))return true;
    return false;
  }
  void scrollKeyVisible(EditText key) throws Exception {
    for(int retry=0;retry<12;retry++) {
      int[] pos=new int[4];
      main(()->{
        int[] xy=new int[2];key.getLocationOnScreen(xy);
        pos[0]=xy[1];pos[1]=xy[1]+key.getHeight();
        View decor=activity.getWindow().getDecorView();
        pos[2]=decor.getHeight()-decor.getRootWindowInsets().getInsets(WindowInsets.Type.ime()).bottom;
        pos[3]=decor.getWidth()/2;
      });
      if(pos[0]>=70 && pos[1]<=pos[2])return;
      float from=(pos[2]+70)/2f;
      float delta=Math.max(-200,Math.min(200,(pos[0]+pos[1])/2f-from));
      float to=from-delta;
      long time=SystemClock.uptimeMillis();
      sendPointerSync(MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,pos[3],from,0));
      for(int i=1;i<=10;i++)sendPointerSync(MotionEvent.obtain(time,time+i*20,MotionEvent.ACTION_MOVE,pos[3],from+(to-from)*i/10,0));
      sendPointerSync(MotionEvent.obtain(time,time+220,MotionEvent.ACTION_UP,pos[3],to,0));
      Thread.sleep(300);
    }
    throw new AssertionError("password field cannot be scrolled above the real IME");
  }
  void screenshot(String name) throws Exception {
    android.graphics.Bitmap bitmap=getUiAutomation().takeScreenshot();
    check(bitmap!=null,"screenshot unavailable");
    java.io.File file=new java.io.File(getTargetContext().getExternalFilesDir(null),"vs78-"+name+".png");
    try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out),"screenshot save");}
    bitmap.recycle();
  }
  final class Probe {
    Object view; WebView web;
    final List<Object> events=new CopyOnWriteArrayList<>();
    final BlockingQueue<String> failures=new LinkedBlockingQueue<>();
    Probe(boolean support) throws Exception {
      main(() -> {
        Class<?> f1=cl.loadClass("kotlin.jvm.functions.Function1"), f0=cl.loadClass("kotlin.jvm.functions.Function0");
        Object event=Proxy.newProxyInstance(cl,new Class[]{f1},(o,m,a)->{if(m.getName().equals("invoke")) events.add(a[0]); return null;});
        Object failure=Proxy.newProxyInstance(cl,new Class[]{f1},(o,m,a)->{if(m.getName().equals("invoke")) failures.add(a[0].toString()); return null;});
        Object supports=Proxy.newProxyInstance(cl,new Class[]{f0},(o,m,a)->Boolean.valueOf(support));
        Class<?> v=cl.loadClass("com.voxarium.remote.ui.editor.bridge.SecureEditorView");
        view=v.getConstructor(Context.class,f1,f1,f0).newInstance(getTargetContext().getApplicationContext(),event,failure,supports);
        boolean dark=(getTargetContext().getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
        String language=getTargetContext().getResources().getConfiguration().getLocales().get(0).getLanguage().equals("ru")?"ru":"en";
        v.getMethod("configure",boolean.class,String.class).invoke(view,dark,language);
        Field f=v.getDeclaredField("webView"); f.setAccessible(true); web=(WebView)f.get(view);
        activity.addContentView((View)view,new ViewGroup.LayoutParams(-1,-1));
      });
    }
    void waitPage() throws Exception {
      for(int i=0;i<150;i++) { if(js("typeof window.voxariumEditorReceive").equals("\"function\"")) return; if(!failures.isEmpty()) throw new AssertionError(failures.toString()); Thread.sleep(100); }
      throw new AssertionError("page startup timeout");
    }
    String js(String expression) throws Exception {
      BlockingQueue<String> q=new LinkedBlockingQueue<>(); main(()->web.evaluateJavascript(expression,s->q.add(s==null?"null":s)));
      String r=q.poll(10,TimeUnit.SECONDS); check(r!=null,"JS callback timeout"); return r;
    }
    void visualReady() throws Exception {
      BlockingQueue<Boolean> q=new LinkedBlockingQueue<>();
      main(()->{check(((View)view).isShown(),"WebView host hidden");web.postVisualStateCallback(1,new WebView.VisualStateCallback(){public void onComplete(long id){q.add(true);}});web.invalidate();});
      check(q.poll(5,TimeUnit.SECONDS)!=null,"visual state timeout");
      Thread.sleep(200);
    }
    void submit(String kind,Object... args) throws Exception {
      Class<?> c=cl.loadClass("com.voxarium.remote.data.room.EditorCommand$"+kind);
      Object command=null; for(Constructor<?> cc:c.getConstructors()) if(cc.getParameterCount()==args.length) {command=cc.newInstance(args);break;}
      check(command!=null,"command constructor");
      view.getClass().getMethod("submit",cl.loadClass("com.voxarium.remote.data.room.EditorCommand")).invoke(view,command);
    }
    Object event(String kind) throws Exception {
      for(int i=0;i<300;i++) { for(Object e:events) if(e.getClass().getSimpleName().equals(kind)) return e;
        if(!failures.isEmpty()) throw new AssertionError("bridge failure "+failures); Thread.sleep(100); }
      throw new AssertionError("event timeout "+kind+" received="+events);
    }
    void close() throws Exception { main(()->{((ViewGroup)((View)view).getParent()).removeView((View)view); view.getClass().getMethod("destroy").invoke(view);}); }
  }
}
