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

/** Standalone instrumentation against the installed, unmodified, write-enabled APK.
 * No Remote, credentials, report or production source is injected. Writable component tests are explicitly local-only; coordinator uses installed BuildConfig=true. */
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
    catch(Throwable t) { failed++; results.add("FAIL "+name+": "+t); android.util.Log.e("VS84",name,t); }
  }
  @Override public void onCreate(Bundle args) { super.onCreate(args); localWritableIme="true".equals(args.getString("localWritableIme")); start(); }
  @Override public void onStart() {
    try {
      cl=getTargetContext().getClassLoader();
      getUiAutomation();
      activity=startActivitySync(new Intent().setClassName("com.voxarium.remote","com.voxarium.remote.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
      test("installed BuildConfig write gate is true", () -> check(cl.loadClass("com.voxarium.remote.BuildConfig").getField("EDITOR_WRITES_ENABLED").getBoolean(null), "gate not enabled"));
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
        String marker="VS84-SYNTHETIC-NOT-A-SERVER-KEY";
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
        String marker="VS84-НЕСЕКРЕТ-😀";
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
          main(()->check(activity.getWindow().getDecorView().getRootWindowInsets().isVisible(WindowInsets.Type.ime()),"real WebView system IME did not appear"));
          screenshot("webview-ime");
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
          check(cl.loadClass("com.voxarium.remote.BuildConfig").getField("EDITOR_WRITES_ENABLED").getBoolean(null),"application gate changed");
        } finally {p.close();}
      });
      test("VS82 actual WebView unsafe raw color Prepare is blocked before seed", () -> {
        for(String md: new String[]{"[nested ]{.gray}[emphasis]{.blue}[ span]{.gray}\n", "A [nested [emphasis]{.blue} span]{.gray}.\n", "[a\tb ]{.blue}\n"}) {
          Probe p=new Probe(true);
          try {
            Object ctx=make("data.room.EditorContext",1L,1L,"vs84-unsafe");
            p.submit("Prepare",ctx,1L,md);
            Object blocked=p.event("Blocked"), snapshot=call(blocked,"getSnapshot");
            check(md.equals(call(snapshot,"getMarkdown")),"original rejected Markdown not preserved");
            check(((Number)call(snapshot,"getGeneration")).longValue()==0L,"initial block generation");
            check(p.events.stream().noneMatch(e -> Arrays.asList("Prepared","Ready","Changed","LocalUpdate","Flushed").contains(e.getClass().getSimpleName())),"unsafe seed/publication escaped");
          } finally {p.close();}
        }
      });
      if(localWritableIme) test("VS82 local-only colored composing final whitespace blocks unsafe publication/Flush", () -> {
        Probe p=new Probe(true);
        try {
          Object ctx=make("data.room.EditorContext",1L,1L,"vs84-color-ime");
          String md="[цвет😀]{.blue}\n";
          p.submit("Prepare",ctx,1L,md);String seed=(String)call(p.event("Prepared"),"getSeed");
          p.submit("Initialize",ctx,2L,md,seed,false);p.event("Ready");p.visualReady();
          main(()->{p.web.setFocusableInTouchMode(true);check(p.web.requestFocus(),"color WebView focus rejected");});
          p.js("(()=>{const e=document.querySelector('.tiptap');e.focus();const s=getSelection();s.selectAllChildren(e);s.collapseToEnd();return true})()");
          Thread.sleep(350);
          main(()->((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(p.web,InputMethodManager.SHOW_IMPLICIT));
          Thread.sleep(350);
          int before=p.events.size();
          main(()->{InputConnection ic=p.web.onCreateInputConnection(new EditorInfo());check(ic!=null && ic.setComposingText(" ",1),"final composing whitespace rejected by IC");});
          Thread.sleep(150);
          p.submit("Flush",ctx,3L);Object block=p.event("Blocked");
          check(!((List)call(block,"getRaw")).isEmpty(),"unsafe composing raw unavailable");
          check(md.equals(call(call(block,"getSnapshot"),"getMarkdown")),"last valid colored snapshot changed");
          check(p.events.subList(before,p.events.size()).stream().noneMatch(e -> Arrays.asList("Changed","LocalUpdate","Flushed").contains(e.getClass().getSimpleName())),"unsafe final composition escaped");
          p.submit("Flush",ctx,4L);Thread.sleep(250);
          check(p.events.stream().noneMatch(e -> e.getClass().getSimpleName().equals("Flushed")),"repeated Flush bypassed block");
        } finally {p.close();}
      });
      test("ENABLED coordinator policy from installed APK: 10 clean open/close -> 0 update/PUT/DELETE", () -> enabledScenario("clean"));
      test("ENABLED coordinator: unsafe colored local IME -> Blocked/recovery, 0 update/PUT, no false Flush", () -> enabledScenario("unsafe"));
      test("ENABLED coordinator: safe real WebView IME update -> exact live PUT; no fake ACK/durability", () -> enabledScenario("safe"));
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
    } catch(Throwable t) { failed++; results.add("FATAL "+t); android.util.Log.e("VS84","fatal",t); }
    Bundle result=new Bundle(); result.putString("stream",String.join("\n",results)+"\nfailures="+failed+"\n");
    finish(failed==0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED,result);
  }

  Object make(String name,Object... args) throws Exception {
    Class<?> c=cl.loadClass("com.voxarium.remote."+name);
    for(Constructor<?> cc:c.getConstructors()) {
      if(cc.getParameterCount()!=args.length) continue;
      try {return cc.newInstance(args);} catch(IllegalArgumentException mismatch) {}
    }
    throw new AssertionError("constructor missing: "+name+" / "+args.length);
  }
  Object call(Object target,String name,Object... args) throws Exception {
    for(Method m:target.getClass().getMethods()) {
      if(!m.getName().equals(name)||m.getParameterCount()!=args.length)continue;
      try {return m.invoke(target,args);} catch(IllegalArgumentException mismatch) {}
    }
    throw new AssertionError("method missing: "+target.getClass()+"."+name);
  }
  Object singleton(String name,String field) throws Exception {return cl.loadClass("com.voxarium.remote."+name).getField(field).get(null);}
  Object enumValue(String name,String value) throws Exception {return Enum.valueOf((Class)cl.loadClass("com.voxarium.remote."+name),value);}
  String shaText(String value) throws Exception {
    byte[] raw=java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    StringBuilder b=new StringBuilder();for(byte n:raw)b.append(String.format(java.util.Locale.ROOT,"%02x",n&255));return b.toString();
  }
  org.json.JSONObject json(Object... pairs) throws Exception {
    org.json.JSONObject j=new org.json.JSONObject();for(int i=0;i<pairs.length;i+=2)j.put((String)pairs[i],pairs[i+1]==null?org.json.JSONObject.NULL:pairs[i+1]);return j;
  }

  void compose(Probe p,String text,boolean finish) throws Exception {
    p.visualReady();
    main(()->{p.web.setFocusableInTouchMode(true);check(p.web.requestFocus(),"coordinator WebView native focus");});
    check(p.js("(()=>{const e=document.querySelector('.tiptap');e.focus();const s=getSelection();s.selectAllChildren(e);s.collapseToEnd();return document.activeElement===e})()").equals("true"),"coordinator DOM focus");
    Thread.sleep(350);
    main(()->((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(p.web,InputMethodManager.SHOW_IMPLICIT));
    Thread.sleep(350);
    main(()->{
      InputConnection ic=p.web.onCreateInputConnection(new EditorInfo());
      check(ic!=null && ic.setComposingText(text,1),"coordinator composing input");
      if(finish)check(ic.finishComposingText(),"coordinator composition finish");
    });
  }
  void enabledScenario(String mode) throws Exception {
    int count=mode.equals("clean")?10:1;
    for(int turn=0;turn<count;turn++) {
      String source=mode.equals("unsafe")?"[цвет😀]{.blue}\n":"Clean original Русский 😀\n";
      EnabledCoordinator d=new EnabledCoordinator(source);
      try {
        d.mount();
        check(d.statuses.contains("EDITING") && !d.statuses.contains("WRITE_GATE_CLOSED"),"installed true policy not used");
        check(d.p.js("document.querySelector('.tiptap').contentEditable").equals("\"true\""),"enabled coordinator mounted read-only");
        d.assertNoPublications();
        if(mode.equals("clean")) {
          d.record(call(d.coordinator,"close"));d.drainUntil("Disposed");
          d.assertNoPublications();check(d.statuses.contains("CLOSED_CONFIRMED"),"clean close not settled");
        } else if(mode.equals("unsafe")) {
          compose(d.p," ",false);
          d.record(call(d.coordinator,"close"));
          d.drainUntil("Blocked");d.drainUntil("Disposed");
          d.assertNoPublications();
          check(d.p.events.stream().noneMatch(e->Arrays.asList("LocalUpdate","Changed","Flushed").contains(e.getClass().getSimpleName())),"unsafe publication or false Flush");
          check(d.statuses.contains("SCHEMA_BLOCKED") && !d.statuses.contains("CLOSED_CONFIRMED"),"unsafe close status");
          d.checkRecovery(source,true);
          results.add("INFO enabled unsafe guard: actual InputConnection, actual Blocked/raw recovery, zero network Update/Put/Delete; original rev/content unchanged");
        } else {
          String marker="Безопасно😀";
          compose(d.p,marker,true);d.drainUntil("Changed");
          Object changed=d.p.event("Changed");String exact=(String)call(call(changed,"getSnapshot"),"getMarkdown");
          check(exact.contains(marker) && !exact.equals(source),"safe IME marker missing");
          d.now[0]+=700;d.record(call(d.coordinator,"tick"));
          List<Object> puts=new ArrayList<>();int updates=0;
          for(Object frame:d.networks) {
            if(frame.getClass().getSimpleName().equals("Put"))puts.add(frame);
            if(frame.getClass().getSimpleName().equals("Update"))updates++;
          }
          check(updates>0 && puts.size()==1,"safe publication count");
          Object put=puts.get(0);
          check(exact.equals(call(put,"getContent")),"PUT bytes differ from real Changed snapshot");
          check(call(d.context,"getSid").equals(call(put,"getLive")),"PUT live does not equal current room sid");
          check(d.path.equals(call(put,"getPath")) && ((Number)call(put,"getBaseRev")).longValue()==1L,"PUT base/path");
          check(d.statuses.contains("SAVING") && !d.statuses.contains("REMOTE_SAVED"),"send mistaken for ACK");
          d.record(call(d.coordinator,"close"));d.drainUntil("Flushed");
          check(!d.statuses.contains("CLOSED_CONFIRMED"),"unacknowledged safe PUT close falsely confirmed");
          d.now[0]+=2500;d.record(call(d.coordinator,"tick"));d.drainUntil("Disposed");
          d.checkRecovery(exact,false);
          results.add("INFO enabled safe exact live PUT sha256="+shaText(exact)+"; updates="+updates+"; puts="+puts.size()+"; controlled ENQUEUED only, NO ACK; statuses="+d.statuses);
        }
        Object original=((Map)call(call(d.repository,"view"),"getDocuments")).get(d.path);
        check(source.equals(call(original,"getContent")) && ((Number)call(original,"getRev")).longValue()==1L,"controlled server mirror unexpectedly changed without ACK");
      } finally {d.p.close();}
    }
  }
  final class EnabledCoordinator {
    final String path="notes/vs84-enabled.md",source;
    final long[] now={1000L};
    final java.io.File root;
    final Object storage,recovery,repository,coordinator;
    final Probe p;
    final List<String> statuses=new ArrayList<>();final List<Object> networks=new ArrayList<>();
    Object context;int cursor;
    EnabledCoordinator(String source) throws Exception {
      this.source=source;
      root=new java.io.File(getTargetContext().getNoBackupFilesDir(),"vs84-enabled-"+UUID.randomUUID());
      storage=make("data.documents.AtomicDocumentStorage",root);
      recovery=make("data.room.RoomRecoveryStore",storage);
      Class<?> f0=cl.loadClass("kotlin.jvm.functions.Function0");
      Object clock=Proxy.newProxyInstance(cl,new Class[]{f0},(o,m,a)->Long.valueOf(now[0]));
      repository=make("data.documents.DocumentsRepository",storage,clock);
      Object endpoint=call(singleton("data.transport.RemoteEndpoint","Companion"),"create","https://example.invalid","vs84");
      long owner=((Number)call(repository,"select",endpoint,null)).longValue();
      boolean writes=cl.loadClass("com.voxarium.remote.BuildConfig").getField("EDITOR_WRITES_ENABLED").getBoolean(null);
      check(writes,"enabled driver requires installed APK policy=true");
      coordinator=make("data.room.RoomCoordinator",repository,owner,recovery,writes,clock,2000L,16*1024*1024);
      p=new Probe(true);
    }
    Object state(String phase) throws Exception {return make("data.transport.ConnectionEvent$State",1L,enumValue("data.transport.ConnectionPhase",phase),null,null);}
    void record(Object values) throws Exception {
      for(Object effect:(List)values) {
        String kind=effect.getClass().getSimpleName();
        if(kind.equals("Editor")) {
          Object command=call(effect,"getCommand");
          if(command.getClass().getSimpleName().equals("Prepare"))context=call(command,"getContext");
          call(p.view,"submit",command);
        } else if(kind.equals("Status"))statuses.add(call(effect,"getValue").toString());
        else if(kind.equals("Network")) {
          check(Boolean.TRUE.equals(call(coordinator,"isCurrent",effect)),"stale outgoing controlled frame");
          networks.add(call(effect,"getFrame"));
          record(call(coordinator,"onSendResult",effect,enumValue("data.transport.SendResult","ENQUEUED")));
        }
      }
    }
    void frame(org.json.JSONObject j) throws Exception {
      Object frame=call(singleton("data.transport.FrameCodec","INSTANCE"),"decode",j.toString());
      record(call(coordinator,"onConnectionEvent",make("data.transport.ConnectionEvent$Frame",1L,frame)));
    }
    void drainUntil(String wanted) throws Exception {
      for(int i=0;i<200;i++) {
        while(cursor<p.events.size()) {
          Object event=p.events.get(cursor++);record(call(coordinator,"onEditorEvent",event));
          if(event.getClass().getSimpleName().equals(wanted))return;
        }
        if(!p.failures.isEmpty())throw new AssertionError("bridge failure "+p.failures);
        Thread.sleep(50);
      }
      throw new AssertionError("enabled wait "+wanted+" statuses="+statuses+" events="+p.events);
    }
    void mount() throws Exception {
      record(call(coordinator,"onConnectionEvent",state("READY")));
      frame(json("t","hello","protocol",1,"user",json("id","vs84","name","Device","color","#123456"),"space",json("id","vs84-enabled-space","name","vs84"),"seq",1));
      frame(json("t","change","path",path,"rev",1,"hash",shaText(source),"deleted",false,"content",source,"by",null));
      frame(json("t","synced","seq",1));
      record(call(coordinator,"open",path));drainUntil("Prepared");
      frame(json("t","joined","sid",call(context,"getSid"),"path",path,"epoch","vs84-enabled-controlled-epoch","seeded",false,"state",null,"scribe",true,"dirty",false,"rev",1));
      frame(json("t","seeded","sid",call(context,"getSid"),"ok",true,"state",null));
      drainUntil("Ready");
    }
    void assertNoPublications() throws Exception {
      for(Object f:networks)check(!Arrays.asList("Put","Delete","Update").contains(f.getClass().getSimpleName()),"unexpected document publication "+f.getClass().getSimpleName());
    }
    void checkRecovery(String exact,boolean requireRaw) throws Exception {
      Object namespace=call(call(repository,"view"),"getNamespace");
      Object reread=make("data.room.RoomRecoveryStore",make("data.documents.AtomicDocumentStorage",root));
      List entries=(List)call(reread,"list",namespace);boolean found=false;
      for(Object entry:entries) {
        Object snapshot=call(entry,"getSnapshot");
        if(snapshot!=null && exact.equals(call(snapshot,"getMarkdown"))) {
          if(!requireRaw || !((List)call(entry,"getRaw")).isEmpty())found=true;
        }
      }
      check(found,"exact recovery after new storage object unavailable");
    }
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
    java.io.File file=new java.io.File(getTargetContext().getExternalFilesDir(null),"vs84-"+name+".png");
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
