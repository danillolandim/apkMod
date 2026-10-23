package com.tvbus.engine;

import android.content.Context;

/**
 * Ponte JNI para o motor P2P TVBus (libtvcore.so).
 *
 * Reconstruída a partir do APK oficial (io.tvs.android.one 11.6.0) e dos
 * símbolos exportados por libtvcore.so. O .so liga os métodos nativos pelo
 * nome JNI padrão: Java_com_tvbus_engine_TVCore_<metodo>. Por isso o pacote
 * e a classe PRECISAM ser exatamente com.tvbus.engine.TVCore.
 *
 * Estrutura real do motor:
 *   - os métodos nativos são privados e recebem o handle nativo (long);
 *   - initialise() cria o handle; os públicos são wrappers que repassam o handle.
 *
 * Só declaramos nativos cujos símbolos existem de fato em libtvcore.so.
 * Binários ficam em app/src/main/jniLibs/<abi>/ (reaproveitados do APK).
 */
public class TVCore {
    public static final int RM_STANDALONE = 0;
    public static final int RM_MASTER = 1;
    public static final int RM_CLIENT = 2;

    private static TVCore inst;
    private static long nativeHandle;

    private TVCore() {}

    public static synchronized TVCore getInstance() {
        if (inst == null) {
            TVCore c = new TVCore();
            inst = c;
            try {
                nativeHandle = c.initialise();
                if (nativeHandle == 0) { inst = null; return null; }
            } catch (Throwable t) {
                inst = null;
                return null;
            }
        }
        return inst;
    }

    // ---- nativos reais (confirmados nos símbolos de libtvcore.so) ----
    private native long initialise();
    private native int init(long h, Context context);
    private native int run(long h);
    private native void quit(long h);
    private native String getVersion(long h);
    private native String getVersion2(long h);
    private native String description(long h);
    private native void diagnose(long h);

    private native void setListener(long h, TVListener l);
    private native void setRunningMode(long h, int mode);
    private native void setPlayPort(long h, int port);
    private native int getPlayPort(long h);
    private native void setServPort(long h, int port);
    private native void setSockPath(long h, String path);
    private native String getSockPath(long h);

    private native void setAuthUrl(long h, String url);
    private native void setUrl(long h, String url);
    private native void setUsername(long h, String u);
    private native void setPassword(long h, String p);
    private native void setMKBroker(long h, String b);
    private native void setDomainSuffix(long h, String s);
    private native void setOption(long h, String k, String v);
    private native void setTCPOnly(long h, boolean on);
    private native void setInboundConns(long h, int n);
    private native void setTotalConns(long h, int n);

    private native void start(long h, String address);
    private native void start2(long h, String a, String b);
    private native void stop(long h);
    private native void stop2(long h, int i);

    // ---- API pública (wrappers) ----
    public int init(Context context)            { return init(nativeHandle, context); }
    public int run()                            { return run(nativeHandle); }
    public void quit()                          { quit(nativeHandle); }
    public String getVersion()                  { return getVersion(nativeHandle); }
    public String getVersion(int i)             { return getVersion2(nativeHandle); }
    public String description()                 { return description(nativeHandle); }
    public void diagnose()                      { diagnose(nativeHandle); }

    public void setTVListener(TVListener l)      { setListener(nativeHandle, l); }
    public void setRunningMode(int mode)         { setRunningMode(nativeHandle, mode); }
    public void setPlayPort(int port)            { setPlayPort(nativeHandle, port); }
    public int getPlayPort()                     { return getPlayPort(nativeHandle); }
    public void setServPort(int port)            { setServPort(nativeHandle, port); }
    public void setSockPath(String p)            { setSockPath(nativeHandle, p); }
    public String getSockPath()                  { return getSockPath(nativeHandle); }

    public void setAuthUrl(String url)           { setAuthUrl(nativeHandle, url); }
    public void setUrl(String url)               { setUrl(nativeHandle, url); }
    public void setUsername(String u)            { setUsername(nativeHandle, u); }
    public void setPassword(String p)            { setPassword(nativeHandle, p); }
    public void setMKBroker(String b)            { setMKBroker(nativeHandle, b); }
    public void setDomainSuffix(String s)        { setDomainSuffix(nativeHandle, s); }
    public void setOption(String k, String v)    { setOption(nativeHandle, k, v); }
    public void setTCPOnly(boolean on)           { setTCPOnly(nativeHandle, on); }
    public void setInboundConns(int n)           { setInboundConns(nativeHandle, n); }
    public void setTotalConns(int n)             { setTotalConns(nativeHandle, n); }

    public void start(String address)            { start(nativeHandle, address); }
    public void start(String a, String b)        { start2(nativeHandle, a, b); }
    public void stop()                           { stop(nativeHandle); }
    public void stop(int i)                      { stop2(nativeHandle, i); }

    static {
        // libtvcore é autocontido (Go estático). libgojni é carregado antes
        // por segurança, caso o motor o procure em runtime.
        try { System.loadLibrary("gojni"); } catch (Throwable ignored) {}
        System.loadLibrary("tvcore");
    }
}
