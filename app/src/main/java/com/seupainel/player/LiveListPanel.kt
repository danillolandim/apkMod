package com.seupainel.player

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.seupainel.player.meta.Epg
import com.seupainel.player.meta.Guide
import com.seupainel.player.playlist.LiveChannel
import com.seupainel.player.playlist.LiveGroup

/**
 * Lista de canais por cima do vídeo (ao vivo): categorias e canais à esquerda, e a programação do canal em foco
 * embaixo à direita. Só desenha e navega; quem sintoniza é o player, avisado por [onPick] com o índice do canal na
 * lista "Todos" (o mesmo número do zapping) e por [onFocus] a cada movimento do foco entre os canais (o player
 * decide se e quando mostrar o canal em foco).
 *
 * No celular (Device.isTv = falso) não há foco: tocar na categoria mostra os canais dela, tocar no canal sintoniza, e
 * as linhas das categorias ganham a altura mínima de 48dp. Na TV nada disso muda.
 */
class LiveListPanel(
    private val activity: FragmentActivity,
    private val onPick: (Int) -> Unit,
    private val onFocus: () -> Unit,
) {
    private val root: View = activity.findViewById(R.id.live_list)
    private val groupsView: ListView = activity.findViewById(R.id.live_groups)
    private val channelsView: ListView = activity.findViewById(R.id.live_channels)
    private val empty: TextView = activity.findViewById(R.id.live_empty)
    private val guide: View = activity.findViewById(R.id.live_guide)
    private val guideName: TextView = activity.findViewById(R.id.live_guide_name)
    private val guideNow: TextView = activity.findViewById(R.id.live_guide_now)
    private val guideProgress: ProgressBar = activity.findViewById(R.id.live_guide_progress)
    private val guideNextLabel: TextView = activity.findViewById(R.id.live_guide_next_label)
    private val guideNext: TextView = activity.findViewById(R.id.live_guide_next)

    private var channels: List<LiveChannel> = emptyList()
    private var groups: List<LiveGroup> = emptyList()
    private var shownGroup = -1
    private var playing = -1
    private val rows = RowAdapter()
    private val phone = !Device.isTv(activity)

    val isOpen: Boolean get() = root.visibility == View.VISIBLE

    /** Categoria que a lista está mostrando (índice em groups), ou -1 antes de abrir. */
    val currentGroup: Int get() = shownGroup
    val groupsHaveFocus: Boolean get() = groupsView.hasFocus()
    val channelsHaveFocus: Boolean get() = channelsView.hasFocus()

    init {
        channelsView.adapter = rows
        // Mover o foco entre categorias já mostra os canais dela, sem precisar do OK (como na tela de canais).
        groupsView.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = showGroup(position)
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        // Toque (ou OK) numa categoria: mostra os canais dela e leva o foco a eles. Na TV o foco já mostrou a
        // categoria, então showGroup não faz nada de novo.
        groupsView.setOnItemClickListener { _, _, position, _ -> showGroup(position); focusChannels() }
        channelsView.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                renderGuide(rows.liveIndexAt(position))
                // Passar por várias linhas não pode pedir a programação de cada uma: só a do canal que parou.
                channelsView.removeCallbacks(requestGuide)
                channelsView.postDelayed(requestGuide, GUIDE_REQUEST_DELAY_MS)
                onFocus()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        channelsView.setOnItemClickListener { _, _, position, _ -> rows.liveIndexAt(position)?.let(onPick) }
        if (phone) {
            // Os cantos vazios do painel e a grade de programação não podem "vazar" o toque para o vídeo, que fecha a lista
            activity.findViewById<View>(R.id.live_left_panel).apply { isClickable = true; isFocusable = false }
            guide.apply { isClickable = true; isFocusable = false }
        }
    }

    /** Abre na categoria [startGroup], com o foco no canal [playingIndex] (índice na lista "Todos"). */
    fun open(channels: List<LiveChannel>, groups: List<LiveGroup>, playingIndex: Int, startGroup: Int) {
        this.channels = channels
        this.groups = groups
        playing = playingIndex
        shownGroup = -1
        val names = groups.map { "${it.name}  (${it.indices.size})" }
        groupsView.adapter =
            if (phone) PhoneGroupAdapter(names) else ArrayAdapter(activity, R.layout.item_group, R.id.group_text, names)
        root.visibility = View.VISIBLE
        selectGroup(startGroup.coerceIn(0, groups.lastIndex.coerceAtLeast(0)))
        focusChannels()
    }

    fun close() {
        channelsView.removeCallbacks(requestGuide)
        root.visibility = View.GONE
    }

    private val requestGuide = Runnable {
        val ch = focusedChannel()?.let { channels.getOrNull(it) } ?: return@Runnable
        Guide.request(ch.tvgId, ch.apiId)
    }

    /** A programação de algum canal chegou: as linhas e a grade do canal em foco se redesenham. */
    fun refresh() {
        if (!isOpen) return
        rows.notifyDataSetChanged()
        renderGuide(focusedChannel())
    }

    /** O canal tocando mudou (CH+/CH−, número, último canal): a marca e a seleção vão junto. */
    fun follow(liveIndex: Int) {
        playing = liveIndex
        if (shownGroup < 0) return
        var pos = groups[shownGroup].positionOf(liveIndex)
        if (pos < 0) {   // a categoria aberta não tem esse canal: volta para "Todos", que sempre tem
            selectGroup(0)
            pos = groups[0].positionOf(liveIndex)
        }
        rows.playing = liveIndex
        rows.notifyDataSetChanged()
        if (pos >= 0) centerOn(pos)
    }

    /** Canal em foco na lista (índice em "Todos"), ou null se a categoria está vazia. */
    fun focusedChannel(): Int? = rows.liveIndexAt(channelsView.selectedItemPosition)

    /** O player passou a tocar [liveIndex] sem o usuário ter pedido pela seleção: só a marca "No ar" muda de linha. */
    fun markPlaying(liveIndex: Int) {
        playing = liveIndex
        rows.playing = liveIndex
        rows.notifyDataSetChanged()
    }

    fun focusGroups() {
        groupsView.requestFocus()
    }

    fun focusChannels() {
        val pos = groups.getOrNull(shownGroup)?.positionOf(playing)?.coerceAtLeast(0) ?: 0
        channelsView.requestFocus()
        // Depois do layout: com a lista recém-exibida ainda não há altura para centralizar.
        channelsView.post { centerOn(pos) }
    }

    private fun selectGroup(position: Int) {
        groupsView.setItemChecked(position, true)
        groupsView.setSelection(position)
        showGroup(position)
    }

    private fun showGroup(position: Int) {
        if (position == shownGroup || position !in groups.indices) return
        shownGroup = position
        groupsView.setItemChecked(position, true)   // categoria atual fica marcada mesmo com o foco nos canais
        rows.set(groups[position].indices, playing)
        empty.visibility = if (rows.count == 0) View.VISIBLE else View.GONE
        channelsView.setSelection(0)
        renderGuide(rows.liveIndexAt(0))
    }

    private fun centerOn(position: Int) {
        val row = channelsView.getChildAt(0)?.height ?: (ROW_FALLBACK_DP * activity.resources.displayMetrics.density).toInt()
        channelsView.setSelectionFromTop(position, ((channelsView.height - row) / 2).coerceAtLeast(0))
    }

    private fun renderGuide(liveIndex: Int?) {
        val ch = liveIndex?.let { channels.getOrNull(it) }
        if (liveIndex == null || ch == null) {
            guide.visibility = View.GONE
            return
        }
        guide.visibility = View.VISIBLE
        guideName.text = "${liveIndex + 1}  ${ch.name}"
        val now = System.currentTimeMillis()
        val listing = Guide.peek(ch.tvgId, ch.apiId, UPCOMING, now)
        val progress = listing.agenda.current?.let { Epg.progressOf(it, now) }
        guideNow.text = Guide.nowLine(listing)
        guideProgress.visibility = if (progress == null) View.GONE else View.VISIBLE
        guideProgress.progress = ((progress ?: 0f) * 1000).toInt()
        val upcoming = listing.agenda.upcoming
        guideNext.text = upcoming.joinToString("\n") { "${Epg.hhmm(it.start)}  ${it.title}" }
        guideNext.visibility = if (upcoming.isEmpty()) View.GONE else View.VISIBLE
        guideNextLabel.visibility = guideNext.visibility
    }

    /** Categorias no celular: a linha da TV tem uns 41dp; no toque o alvo precisa de pelo menos 48dp. */
    private inner class PhoneGroupAdapter(names: List<String>) :
        ArrayAdapter<String>(activity, R.layout.item_group, R.id.group_text, names) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = super.getView(position, convertView, parent)
            (v as? TextView)?.apply {
                minHeight = (TOUCH_MIN_DP * activity.resources.displayMetrics.density).toInt()
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
            }
            return v
        }
    }

    private inner class RowAdapter : BaseAdapter() {
        private var indices: List<Int> = emptyList()
        var playing = -1

        fun set(newIndices: List<Int>, playingIndex: Int) {
            indices = newIndices
            playing = playingIndex
            notifyDataSetChanged()
        }

        fun liveIndexAt(position: Int): Int? = indices.getOrNull(position)

        override fun getCount() = indices.size
        override fun getItem(position: Int) = indices[position]
        override fun getItemId(position: Int) = position.toLong()

        private inner class Holder(root: View) {
            val bar: View = root.findViewById(R.id.live_item_bar)
            val number: TextView = root.findViewById(R.id.live_item_num)
            val logo: ImageView = root.findViewById(R.id.live_item_logo)
            val name: TextView = root.findViewById(R.id.live_item_name)
            val epg: TextView = root.findViewById(R.id.live_item_epg)
            val now: TextView = root.findViewById(R.id.live_item_now)
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: activity.layoutInflater.inflate(R.layout.item_live_channel, parent, false)
                .also { it.tag = Holder(it) }
            val h = v.tag as Holder
            val liveIndex = indices[position]
            val ch = channels[liveIndex]
            val isPlaying = liveIndex == playing
            val program = Guide.nowText(ch.tvgId, ch.apiId)   // só o que já está em cache: nada de pedido por linha

            h.number.text = (liveIndex + 1).toString()
            h.name.text = ch.name
            h.epg.text = program
            h.epg.visibility = if (program.isBlank()) View.GONE else View.VISIBLE
            h.bar.visibility = if (isPlaying) View.VISIBLE else View.INVISIBLE
            h.now.visibility = if (isPlaying) View.VISIBLE else View.GONE
            v.contentDescription = listOfNotNull(
                "Canal ${liveIndex + 1}", ch.name, program.ifBlank { null }, if (isPlaying) "no ar" else null,
            ).joinToString(", ")

            if (ch.logo.isBlank()) h.logo.setImageDrawable(null)
            else coil.Coil.imageLoader(activity).enqueue(
                coil.request.ImageRequest.Builder(activity).data(ch.logo).target(h.logo).crossfade(true).build()
            )
            return v
        }
    }

    private companion object {
        const val UPCOMING = 3
        const val GUIDE_REQUEST_DELAY_MS = 350L
        const val ROW_FALLBACK_DP = 56
        const val TOUCH_MIN_DP = 48
    }
}
