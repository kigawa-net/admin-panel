package net.kigawa.admin.servers

import androidx.compose.runtime.Composable
import com.varabyte.kobweb.compose.css.FontSize
import com.varabyte.kobweb.compose.css.FontWeight
import com.varabyte.kobweb.compose.foundation.layout.Arrangement
import com.varabyte.kobweb.compose.foundation.layout.Column
import com.varabyte.kobweb.compose.ui.Modifier
import com.varabyte.kobweb.compose.ui.graphics.Colors
import com.varabyte.kobweb.compose.ui.modifiers.*
import com.varabyte.kobweb.silk.components.text.SpanText
import net.kigawa.admin.infrastructure.HostSlotInventory
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.px

/**
 * ノードのスロット情報(VM系では空)の場合に表示を抑止する判定。
 * 仮想マシン上では物理スロットの概念がなく、空の区分だけが並ぶため非表示にする。
 */
fun shouldShowNodeSlots(s: HostSlotInventory): Boolean =
    !s.virtualized && (s.pciSlots.isNotEmpty() || s.memorySlots.isNotEmpty() || s.diskBays.populated.isNotEmpty())

/** 空きスロット調査結果の表示(admin-panel#156)。SSH未到達時は注意書きのみ出す。 */
@Composable
fun SlotInventorySection(slots: HostSlotInventory) {
    Column(modifier = Modifier.padding(top = 4.px), verticalArrangement = Arrangement.spacedBy(2.px)) {
        SpanText("空きスロット", modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Small))
        if (!slots.sshReachable) {
            SpanText(
                "スロット情報を取得できませんでした(SSH未設定またはホスト到達不可)",
                modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
            )
            return@Column
        }
        val pciFree = slots.pciSlots.count { it.free }
        val memFree = slots.memorySlots.count { it.free }
        SpanText(
            "PCIe空き: $pciFree/${slots.pciSlots.size} ・ メモリ空き: $memFree/${slots.memorySlots.size}" +
                (slots.diskBays.freeBays?.let { " ・ ディスクベイ空き: $it/${slots.diskBays.totalBays}" }
                    ?: " ・ ディスク搭載: ${slots.diskBays.populated.size}台(総ベイ数未設定)"),
            modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
        )
        slots.pciSlots.filter { it.free }.forEach { slot ->
            SpanText(
                "空き: ${slot.designation}${slot.type?.let { " ($it)" } ?: ""}",
                modifier = Modifier.color(Color("#008300")).fontSize(FontSize.Small)
            )
        }
        slots.memorySlots.filter { it.free }.forEach { slot ->
            SpanText(
                "空き: ${slot.locator} (メモリ)",
                modifier = Modifier.color(Color("#008300")).fontSize(FontSize.Small)
            )
        }
    }
}
