package com.telefarm.data.model

/** Type of a search result. */
enum class SearchResultKind { CHAT, MESSAGE }

/** One row of the search screen. */
data class SearchResultUi(
    val kind: SearchResultKind,
    val id: Long,
    val chatId: Long,
    val messageId: Long,
    val title: String,
    val subtitle: String?,
    val photo: FileRef?,
    val date: Int
)
