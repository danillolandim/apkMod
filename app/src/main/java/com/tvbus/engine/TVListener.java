package com.tvbus.engine;

/**
 * Interface de callback do motor P2P TVBus.
 * Assinatura extraída do APK oficial (io.tvs.android.one 11.6.0).
 *
 * ESTE ARQUIVO É APENAS O CONTRATO. A implementação nativa vem no
 * libtvcore.so do SEU provedor de painel.
 */
public interface TVListener {
    void onInfo(String s);
    void onInited(String s);
    void onPrepared(String s);
    void onQuit(String s);
    void onStart(String s);
    void onStop(String s);
}
