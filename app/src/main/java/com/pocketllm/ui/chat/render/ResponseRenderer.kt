package com.pocketllm.ui.chat.render

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDirection
import com.pocketllm.ui.theme.Spacing

/**
 * Parses and renders an assistant reply. Only the changed message re-parses
 * (the caller memoises by content), and every block is plain Compose — no HTML,
 * no WebView, no execution of model output.
 */
/**
 * Parse the raw reply. Sanitization is deliberately per rendered text, never
 * applied to the whole message: doing that erased tags inside fenced code.
 */
fun parseResponse(text: String): List<RichBlock> = ResponseParser.parse(text.trim())

@Composable
fun ResponseRenderer(
    text: String,
    streaming: Boolean,
    onCodeCopied: () -> Unit,
) {
    val blocks = remember(text) { parseResponse(text) }
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.S),
    ) {
        blocks.forEach { block ->
            when (block) {
                is RichBlock.Heading -> Text(
                    text = stripTags(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    }.copy(textDirection = textDirectionOf(block.text)),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = if (containsRtlScript(block.text)) Modifier.fillMaxWidth() else Modifier,
                )

                is RichBlock.Paragraph -> RichText(block.text, MaterialTheme.typography.bodyLarge)

                is RichBlock.ListBlock -> ListBlockView(
                    items = block.items,
                    ordered = block.ordered,
                    style = MaterialTheme.typography.bodyLarge,
                )

                is RichBlock.Quote -> QuoteBlock(block.text, MaterialTheme.typography.bodyLarge)

                RichBlock.Divider -> HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.fillMaxWidth(),
                )

                is RichBlock.Code -> CodeBlock(
                    code = block.code,
                    language = block.language,
                    onCopied = onCodeCopied,
                )

                is RichBlock.Table -> TableBlock(block.header, block.rows)

                is RichBlock.Math -> if (block.block) {
                    BlockMath(block.tex)
                } else {
                    RichText("$${block.tex}$", MaterialTheme.typography.bodyLarge)
                }

                is RichBlock.Chart -> ChartBlock(block.spec)

                is RichBlock.Flow -> FlowBlock(block.lines, MaterialTheme.typography.bodyLarge)
            }
        }
        if (streaming) {
            val rtl = containsRtlScript(text)
            Text(
                text = "▍",
                style = MaterialTheme.typography.bodyLarge.copy(
                    textDirection = if (rtl) TextDirection.Rtl else TextDirection.Ltr,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (rtl) Modifier.fillMaxWidth() else Modifier,
            )
        }
    }
}
