package com.arin.llm

/** Where replies come from. [Free] needs no key; the others use the person's own API key. */
enum class Provider(val label: String, val needsKey: Boolean, val keyHint: String) {
    Free("Free models", needsKey = false, keyHint = ""),
    Claude("Claude", needsKey = true, keyHint = "console.anthropic.com → API keys"),
    Gemini("Gemini", needsKey = true, keyHint = "aistudio.google.com → Get API key"),
    ChatGPT("ChatGPT", needsKey = true, keyHint = "platform.openai.com → API keys"),
}

/** One model a provider offers. */
data class ModelOption(val id: String, val label: String)

/** A turn sent to a model. */
data class Turn(val fromUser: Boolean, val text: String)

/** A piece of a streaming reply: visible text, or the model's reasoning. */
sealed interface Delta {
    data class Text(val text: String) : Delta
    data class Thinking(val text: String) : Delta
}

/** A failed request, with a message that can be shown to the person as it is. */
class LlmException(message: String) : Exception(message)

/** Built-in model lists, used until (or when) the provider's own list can't be loaded. */
object DefaultModels {
    val byProvider: Map<Provider, List<ModelOption>> = mapOf(
        Provider.Free to listOf(ModelOption("openai", "GPT-OSS (free)")),
        Provider.Claude to listOf(
            ModelOption("claude-sonnet-5-5", "Claude Sonnet 5.5"),
            ModelOption("claude-opus-5-5", "Claude Opus 5.5"),
            ModelOption("claude-haiku-5-5", "Claude Haiku 5.5"),
        ),
        Provider.Gemini to listOf(
            ModelOption("gemini-2.5-flash", "Gemini 2.5 Flash"),
            ModelOption("gemini-2.5-pro", "Gemini 2.5 Pro"),
        ),
        Provider.ChatGPT to listOf(
            ModelOption("gpt-5", "GPT-5"),
            ModelOption("gpt-5-mini", "GPT-5 mini"),
        ),
    )
}

const val ARIN_SYSTEM_PROMPT =
    "You are Arin, a helpful, friendly assistant in an Android app. Answer clearly and concisely. " +
        "Use Markdown for structure when it helps: short headings, lists, and fenced code blocks with a language."
