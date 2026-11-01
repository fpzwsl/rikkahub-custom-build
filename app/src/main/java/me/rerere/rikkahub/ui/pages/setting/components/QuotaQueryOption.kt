package me.rerere.rikkahub.ui.pages.setting.components

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.QuotaQuery
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DEFAULT_PROVIDERS
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.JetbrainsMono

/** 自定义额度查询的设置项，只给 API Key 类提供商用 */
@Composable
fun SettingProviderQuotaQuery(
    provider: ProviderSetting.ApiKey,
    modifier: Modifier = Modifier,
    // 标题下方的说明，用来放查到的额度
    supportingContent: (@Composable () -> Unit)? = null,
    onEdit: (QuotaQuery) -> Unit,
) {
    val query = provider.quotaQuery
    var expand by remember { mutableStateOf(false) }
    val codeStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono)
    CardGroup(modifier = modifier) {
        formItem(
            headlineContent = { Text(stringResource(R.string.setting_provider_page_balance_info)) },
            supportingContent = supportingContent,
            trailingContent = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { expand = !expand }) {
                        Icon(
                            imageVector = if (expand) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                            contentDescription = null,
                        )
                    }
                    Switch(
                        checked = query.enabled,
                        onCheckedChange = { onEdit(query.copy(enabled = it)) },
                    )
                }
            },
        ) {
            AnimatedVisibility(visible = expand) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = query.javascript,
                        onValueChange = { onEdit(query.copy(javascript = it)) },
                        label = { Text("JavaScript extractor") },
                        placeholder = {
                            Text(
                                "({ request: { url: '{{baseUrl}}/user/balance', method: 'GET', " +
                                    "headers: { Authorization: 'Bearer {{apiKey}}' } }, " +
                                    "extractor: response => ({ remaining: response.balance, unit: 'USD' }) })"
                            )
                        },
                        minLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = codeStyle,
                    )
                    Text(
                        text = "脚本必须是用括号包裹的对象表达式。extractor(response) 可返回 " +
                            "isValid、invalidMessage、remaining、unit、planName、total、used、extra。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            R.string.setting_provider_page_quota_variables,
                            QuotaQuery.VARIABLES.joinToString { "{{$it}}" },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            val builtIn = DEFAULT_PROVIDERS.find { it.id == provider.id } as? ProviderSetting.ApiKey
                            onEdit(builtIn?.quotaQuery ?: provider.defaultQuotaQuery())
                        },
                    ) {
                        Text(stringResource(R.string.setting_model_page_reset_to_default))
                    }
                }
            }
        }
    }
}
// 鉴权头按类型不同，取各类型的默认配置
private fun ProviderSetting.ApiKey.defaultQuotaQuery(): QuotaQuery = when (this) {
    is ProviderSetting.OpenAI -> ProviderSetting.OpenAI().quotaQuery
    is ProviderSetting.Google -> ProviderSetting.Google().quotaQuery
    is ProviderSetting.Claude -> ProviderSetting.Claude().quotaQuery
}
