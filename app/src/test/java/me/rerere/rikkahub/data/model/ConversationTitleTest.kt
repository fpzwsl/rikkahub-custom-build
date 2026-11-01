package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationTitleTest {
    @Test
    fun `title uses the first sentence from the first user message`() {
        val conversation = Conversation.ofId(
            id = Uuid.random(),
            messages = listOf(
                MessageNode.of(UIMessage.user("  请帮我总结这篇文章。第二句话不应进入标题。")),
            ),
        )

        assertEquals("请帮我总结这篇文章。", conversation.deriveTitleFromFirstUserMessage())
    }

    @Test
    fun `title uses the complete user message when it has no sentence terminator`() {
        val conversation = Conversation.ofId(
            id = Uuid.random(),
            messages = listOf(MessageNode.of(UIMessage.user("帮我规划一个周末行程"))),
        )

        assertEquals("帮我规划一个周末行程", conversation.deriveTitleFromFirstUserMessage())
    }
}
