package com.seupainel.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTextTest {

    @Test fun `address shows only the host, never user, password, port or path`() {
        assertEquals("exemplo.test", SettingsText.hostOf("http://exemplo.test:8080/get.php?username=fulano&password=segredo&type=m3u"))
        assertEquals("exemplo.test", SettingsText.hostOf("  https://fulano:segredo@exemplo.test/lista.m3u  "))
        assertEquals("exemplo.test", SettingsText.hostOf("exemplo.test/epg.xml.gz"))
        assertEquals("[::1]", SettingsText.hostOf("http://[::1]:8080/x"))
        assertNull(SettingsText.hostOf(""))
        assertNull(SettingsText.hostOf(null))
        assertNull(SettingsText.hostOf("isto não é um endereço"))
    }

    @Test fun `summary of an address never leaks the credentials`() {
        val shown = SettingsText.urlSummary("http://exemplo.test/get.php?username=fulano&password=segredo")
        assertEquals("exemplo.test", shown)
        assertFalse(shown.contains("fulano"))
        assertFalse(shown.contains("segredo"))
        assertEquals("Não definida", SettingsText.urlSummary(null))
        assertEquals("Não definida", SettingsText.urlSummary("   "))
        assertEquals("Definida", SettingsText.urlSummary("texto solto com espaços"))
    }

    @Test fun `TMDB key state tells user key, factory key and none apart`() {
        assertEquals("Definida", SettingsText.tmdbState("abc", "fabrica"))
        assertEquals("De fábrica", SettingsText.tmdbState("", "fabrica"))
        assertEquals("De fábrica", SettingsText.tmdbState("  ", "fabrica"))
        assertEquals("Não definida", SettingsText.tmdbState("", ""))
        assertEquals("Não definida", SettingsText.tmdbState(null, null))
    }

    @Test fun `account is connected with a list or a config address`() {
        assertEquals("Conectada", SettingsText.accountState("http://exemplo.test/l.m3u", null))
        assertEquals("Conectada", SettingsText.accountState(null, "https://exemplo.test/c.txt"))
        assertEquals("Não configurada", SettingsText.accountState(null, null))
        assertEquals("Não configurada", SettingsText.accountState("", "  "))
    }

    @Test fun `spoken text is label and value, with on or off for switches`() {
        assertEquals("Áudio preferido, Português", SettingsText.describe("Áudio preferido", "Português"))
        assertEquals("Continuar de onde parou, ligado", SettingsText.describe("Continuar de onde parou", "Ligado", toggle = true))
        assertEquals("Continuar de onde parou, desligado", SettingsText.describe("Continuar de onde parou", "Desligado", toggle = true))
        assertEquals("Limpar cache", SettingsText.describe("Limpar cache", ""))
        assertEquals("Ligado", SettingsText.onOff(true))
        assertEquals("Desligado", SettingsText.onOff(false))
    }

    @Test fun `current choice label comes from the options, unknown saved value is called other`() {
        val sizes = listOf("Pequena" to 16f, "Média" to 20f, "Grande" to 26f)
        assertEquals("Média", SettingsText.choiceLabel(sizes, 20f))
        assertEquals(1, SettingsText.checkedIndex(sizes, 20f))
        assertEquals("Outro", SettingsText.choiceLabel(sizes, 22f))
        assertEquals(-1, SettingsText.checkedIndex(sizes, 22f))
    }

    @Test fun `options sharing a value are listed once so the saved value maps to one label`() {
        val speeds = listOf("Automática (10 Mbps)" to 10, "Lenta (3 Mbps)" to 3, "Boa (10 Mbps)" to 10)
        assertEquals(listOf("Automática (10 Mbps)" to 10, "Lenta (3 Mbps)" to 3), SettingsText.uniqueByValue(speeds))
    }

    @Test fun `refresh interval has the four plain choices and keeps an old odd value readable`() {
        assertEquals("A cada 6 horas", SettingsText.refreshLabel(6))
        assertEquals("A cada 12 horas", SettingsText.refreshLabel(12))
        assertEquals("Uma vez por dia", SettingsText.refreshLabel(24))
        assertEquals("Só quando eu pedir", SettingsText.refreshLabel(0))
        assertEquals("A cada 8 horas", SettingsText.refreshLabel(8))
        assertEquals("A cada hora", SettingsText.refreshLabel(1))
        // O cache trata zero ou menos como "só manual": o rótulo diz o mesmo.
        assertEquals("Só quando eu pedir", SettingsText.refreshLabel(-3))
        assertEquals(-1, SettingsText.checkedIndex(SettingsText.REFRESH_OPTIONS, 8))
    }

    @Test fun `player identification is a preset name, custom, or the app default`() {
        val presets = listOf("— Personalizado / vazio —" to "", "VLC" to "VLC/3.0", "Kodi" to "Kodi/20")
        assertEquals("VLC", SettingsText.userAgentLabel("VLC/3.0", presets))
        assertEquals("Personalizado", SettingsText.userAgentLabel("MeuPlayer/1.0", presets))
        assertEquals("Padrão do app", SettingsText.userAgentLabel(null, presets))
        assertEquals("Padrão do app", SettingsText.userAgentLabel("  ", presets))
        assertEquals(2, SettingsText.userAgentIndex("Kodi/20", presets))
        assertEquals(0, SettingsText.userAgentIndex("MeuPlayer/1.0", presets))
        assertEquals(0, SettingsText.userAgentIndex(null, presets))
    }

    @Test fun `a filled image address wins over the chosen wallpaper, like the Home does`() {
        assertEquals("custom", SettingsText.effectiveWallpaper("night", "https://exemplo.test/fundo.jpg"))
        assertEquals("custom", SettingsText.effectiveWallpaper("custom", ""))
        assertEquals("night", SettingsText.effectiveWallpaper("night", ""))
        assertEquals("night", SettingsText.effectiveWallpaper("night", "   "))
    }

    @Test fun `account status line says when it last updated and how many channels are cached`() {
        assertEquals("Última atualização: 13/11 09:30 • 1200 canais em cache", SettingsText.cacheStatus("13/11 09:30", 1200))
        assertEquals("Última atualização: nunca • sem cache", SettingsText.cacheStatus(null, 0))
    }

    @Test fun `typed text is kept, cleared or ignored depending on the field`() {
        assertEquals(SettingsText.TextEdit.Set("novo"), SettingsText.resolveText("  novo ", hadValue = true, secret = true))
        assertEquals(SettingsText.TextEdit.Clear, SettingsText.resolveText("", hadValue = true, secret = false))
        // Campo sigiloso abre vazio: Salvar sem digitar não pode apagar o que já está salvo.
        assertEquals(SettingsText.TextEdit.Keep, SettingsText.resolveText("  ", hadValue = true, secret = true))
        assertEquals(SettingsText.TextEdit.Clear, SettingsText.resolveText("", hadValue = false, secret = true))
    }

    @Test fun `the real option lists are consistent with what the screen shows`() {
        // Toda opção salva tem de voltar com o mesmo rótulo depois de reabrir a tela.
        val connection = SettingsText.uniqueByValue(AppSettings.CONNECTION_OPTIONS)
        connection.forEach { (label, mbps) -> assertEquals(label, SettingsText.choiceLabel(connection, mbps)) }
        AppSettings.WALLPAPERS.forEach { (label, key) ->
            assertEquals(label, SettingsText.choiceLabel(AppSettings.WALLPAPERS, SettingsText.effectiveWallpaper(key, "")))
        }
        assertTrue(AppSettings.WALLPAPERS.any { it.second == SettingsText.WALLPAPER_CUSTOM })
        SettingsText.REFRESH_OPTIONS.forEach { (label, h) -> assertEquals(label, SettingsText.refreshLabel(h)) }
    }
}
