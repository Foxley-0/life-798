package com.life798.mini.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.life798.mini.net.PgSku
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 胖乖洗衣的「启动设备」页。
 *
 * 顶部按当前 App 的 798 启动页设计：居中「启动设备」标题 + 左侧返回箭头，
 * 不套官方 App 的运营商标题 / 渐变背景。
 *
 * 主体按参考图：设备信息行 → 程序卡列表 → 底部应付金额 + 「选好了」。
 * 参考图里的「联系商家/故障上报」「积分周卡横幅」「运营商标题」三块已删除。
 *
 * 容器与控件全部用 miuix：SmallTopAppBar / Card / CardDefaults / Button /
 * ButtonDefaults / Icon / Text / MiuixTheme；选中描边用 Compose Modifier.border
 * （miuix Card 没有 border 参数），列表滚动用 LazyColumn。
 *
 * 不默认选中任何程序：selectedSkuId 为 null 时「选好了」禁用。
 */
@Composable
fun PangguaiStartScreen(
    state: UiState,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onSelectSku: (String) -> Unit = {},
    onAlipayResult: (Long) -> Unit = {},
) {
    val cs = MiuixTheme.colorScheme
    val washer = state.pangguaiWasher
    val ready = washer?.ready
    val skus = ready?.skus.orEmpty()

    PangguaiAlipayEffect(request = washer?.alipayLaunch, onResult = onAlipayResult)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        SmallTopAppBar(
            title = "启动设备",
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = MiuixIcons.Back,
                        contentDescription = "返回",
                        tint = cs.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        )

        // 设备信息行：只保留图标 + 设备名 + 场所
        if (ready != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.defaultColors(color = cs.surfaceContainer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = MiuixIcons.Scan,
                        contentDescription = null,
                        tint = cs.primary,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            text = ready.goods.name.ifBlank { "洗衣设备" },
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = cs.onSurface
                        )
                        if (ready.goods.orgName.isNotBlank()) {
                            Text(
                                text = ready.goods.orgName,
                                fontSize = 12.sp,
                                color = cs.onSurfaceVariantSummary
                            )
                        }
                    }
                }
            }
        }

        // 程序卡：名称 / 价格 / 约 N 分钟 / 说明，选中项整卡蓝色描边
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(skus, key = { it.skuId }) { sku ->
                ProgramCard(
                    sku = sku,
                    selected = sku.skuId == washer?.selectedSkuId,
                    enabled = washer?.busy != true && washer?.launched == null,
                    onClick = { onSelectSku(sku.skuId) }
                )
            }
        }

        val status: Pair<String, Boolean>? = when {
            washer?.launched != null ->
                "已启动 · 订单号 ${washer.launched!!.orderNo.takeLast(10)}" to false

            washer?.error != null -> washer.error!! to true
            !washer?.stage.isNullOrBlank() -> washer!!.stage to false
            washer?.selectedSkuId == null -> "请选择洗衣程序" to false
            else -> null
        }
        status?.let { (msg, isError) ->
            Text(
                text = msg,
                fontSize = 12.sp,
                color = if (isError) cs.error else cs.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
            )
        }

        // 底部固定栏：应付价 + 「选好了」
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        washer?.quoteLoading == true -> "读取价格…"
                        washer?.payable != null -> "¥${washer.payable}"
                        else -> "—"
                    },
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onStart,
                    enabled = washer?.selectedSkuId != null &&
                        washer?.quoteLoading == false &&
                        washer?.busy == false &&
                        washer?.launched == null,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.height(56.dp)
                ) {
                    Text(
                        text = if (washer?.busy == true) "处理中…" else "选好了",
                        fontSize = 16.sp
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "放好衣物并关好门后再支付。",
                fontSize = 12.sp,
                color = cs.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ProgramCard(
    sku: PgSku,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.border(2.dp, cs.primary, shape) else Modifier)
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.defaultColors(color = cs.surfaceContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = sku.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "¥${sku.price}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (selected) cs.primary else cs.onSurface
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "约 ${sku.unitMinutes} 分钟",
                fontSize = 12.sp,
                color = cs.onSurfaceVariantSummary
            )
            if (sku.feature.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = sku.feature,
                    fontSize = 13.sp,
                    color = cs.onSurfaceVariantSummary
                )
            }
        }
    }
}
