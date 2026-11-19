package com.lcs.hifi.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.lcs.hifi.R
import com.lcs.hifi.core.RawUsb
import com.lcs.hifi.core.UsbOut

/** USB 页的行构造。UI 很简单，不值得上 RecyclerView。 */
object UsbRows {

    fun row(
        ctx: Context,
        parent: ViewGroup,
        title: String,
        sub: String,
        cap: String,
        action: String,
        accent: Boolean = false,
        onClick: () -> Unit
    ): View {
        val v = LayoutInflater.from(ctx).inflate(R.layout.item_usb_row, parent, false)
        v.findViewById<TextView>(R.id.rowTitle).text = title
        v.findViewById<TextView>(R.id.rowSub).text = sub
        val c = v.findViewById<TextView>(R.id.rowCap)
        c.text = cap
        c.visibility = if (cap.isBlank()) View.GONE else View.VISIBLE
        val a = v.findViewById<TextView>(R.id.rowAction)
        a.text = action
        a.visibility = if (action.isBlank()) View.GONE else View.VISIBLE
        if (accent) {
            a.setTextColor(0xFFFF9A3C.toInt())
            v.setBackgroundResource(R.drawable.bg_card_hi)
        }
        v.setOnClickListener { onClick() }
        return v
    }

    fun audioRow(
        ctx: Context,
        parent: ViewGroup,
        out: UsbOut,
        selected: Boolean,
        onClick: () -> Unit
    ): View = row(
        ctx = ctx,
        parent = parent,
        title = out.name,
        sub = out.product.ifBlank { out.address } + (if (out.address.isNotBlank()) "  ${out.address}" else ""),
        cap = out.rateText + "  " + out.typeText,
        action = if (selected) "已选" else "使用",
        accent = selected,
        onClick = onClick
    )

    fun rawRow(
        ctx: Context,
        parent: ViewGroup,
        raw: RawUsb,
        selected: Boolean = false,
        onClick: () -> Unit
    ): View = row(
        ctx = ctx,
        parent = parent,
        title = raw.title,
        sub = raw.vidPid + "  " + raw.describe(),
        cap = if (raw.streamingInterface != null)
            "UAC Streaming ep 0x${Integer.toHexString(raw.streamingInterface!!.outEndpoint!!.address)}" +
                " · 包长 ${raw.streamingInterface!!.outEndpoint!!.maxPacketSize}"
        else "无 UAC streaming 端点（可能是 USB Audio 2.0 复合设备）",
        action = when {
            selected -> "直连中"
            raw.hasPermission -> "直连"
            else -> "授权"
        },
        accent = raw.hasPermission || selected,
        onClick = onClick
    )

    fun emptyRow(ctx: Context, parent: ViewGroup, text: String): View = row(
        ctx = ctx, parent = parent,
        title = text, sub = "", cap = "", action = "",
        onClick = {}
    )
}
